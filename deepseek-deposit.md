# DeepSeek Deposit — 동시성 제어 분석

## 문제: 현재 코드는 모든 연산이 read-then-write

```java
// DepositEventApplication.charge() / withdraw() / refund()
@Transactional
public DepositHistory charge(String userCode, DepositHistoryType type, Long amount) {
    Deposit deposit = depositPersistencePort.getDepositByUserCode(userCode)  // ① SELECT
        .orElseThrow(...);
    deposit.charge(amount);           // ② Java 메모리에서 연산
    depositCommandPort.saveDeposit(deposit);  // ③ UPDATE (DepositEventAdapter)
}

// DepositEventAdapter.saveDeposit()
public Deposit saveDeposit(Deposit deposit) {
    DepositEntity entity = depositJpaRepository.findByCode(deposit.getCode())  // ④ SELECT 재조회
        .map(d -> { d.updateBalance(deposit.getBalance()); return d; })
        .orElseGet(() -> DepositEntity.from(deposit));
    return DepositMapper.toDomain(depositJpaRepository.save(entity));  // ⑤ UPDATE
}
```

**동시성 문제**: ①과 ⑤ 사이에 다른 트랜잭션이 같은 row를 변경하면 lost update 발생.

---

## 분석: 연산별로 필요한 동시성 제어가 다르다

| 연산 | 잔액 검증 필요? | 동시 충돌 시 결과 | 적합한 방식 |
|---|---|---|---|
| **charge** (충전) | ❌ 없음 (그냥 더함) | lost update → 금액 누락 | Atomic UPDATE |
| **refund** (환불) | ❌ 없음 (그냥 더함) | lost update → 금액 누락 | Atomic UPDATE |
| **withdraw** (출금) | ✅ `balance >= amount` | 초과 인출 | Pessimistic Lock 또는 Atomic UPDATE + WHERE |
| **payment** (결제) | ✅ `balance >= amount` | 초과 인출 | Pessimistic Lock 또는 Atomic UPDATE + WHERE |
| **delete** (삭제) | ❌ 없음 | — | 락 불필요 |

---

## 방식 A: 비관적 락 (현재 구현) — SELECT FOR UPDATE

```sql
SELECT ... FROM deposit WHERE user_code = ? FOR UPDATE;  -- row lock
-- Java 에서 balance 검증 및 연산
UPDATE deposit SET balance = ? WHERE code = ?;
COMMIT;  -- lock 해제
```

**장점**:
- 잔액 검증 + 업데이트가 트랜잭션 내에서 보장됨
- JPA 친화적 (엔티티 로드 → 수정 → save)

**단점**:
- **charge/refund 에도 락을 걸고 있음** — 불필요한 경합 발생
- 락 획득 대기 시간만큼 전체 처리량 감소
- `saveDeposit()`가 `findByCode()`로 **재조회**해서 persistence context 캐시에 의존 (깨지기 쉬움)

---

## 방식 B: Atomic UPDATE (권장)

```sql
-- charge / refund: 그냥 더한다
UPDATE deposit SET balance = balance + ? WHERE code = ?;
-- 영향받은 row 0개 → 예외

-- withdraw / payment: 잔액 조건 포함
UPDATE deposit SET balance = balance - ? WHERE code = ? AND balance >= ?;
-- 영향받은 row 0개 → 잔액 부족 예외
```

**장점**:
- **charge/refund 에 락 불필요** — 완전 병렬 처리 가능
- **withdraw/payment 에도 락 필요 없음** — DB 레벨에서 atomic 하게 `balance >= amount` 검증
- read-then-write 갭 없음 (단일 SQL 문)
- JPA 재조회 문제도 사라짐

**단점**:
- JPA 더티체킹 방식과 다름 (native query 또는 `@Modifying` 필요)
- Domain 로직(`deposit.charge()`, `deposit.withdraw()`)을 거치지 않게 됨
- 이력 저장용 `balanceAfter` 값을 별도로 계산해야 함 (SELECT로 재조회하거나 `RETURNING` 절 사용)

---

## 방식 C: 낙관적 락 (Optimistic Lock)

```sql
-- @Version 필드 추가
UPDATE deposit SET balance = ?, version = version + 1 WHERE code = ? AND version = ?;
-- 영향받은 row 0개 → OptimisticLockException → 재시도
```

**장점**: 락 대기 없음, 읽기 전용 트랜잭션에 영향 없음

**단점**: 충돌 시 재시도 필요, 충돌 빈도 높으면 여러 번 재시도

---

## 결론: 연산별로 다르게 적용해야 한다

```
┌──────────────┬──────────────────────────┬─────────────────────┐
│ 연산           │ 현재 (비관적 락)            │ 권장                   │
├──────────────┼──────────────────────────┼─────────────────────┤
│ charge       │ SELECT FOR UPDATE → UPDATE │ Atomic UPDATE       │
│ refund       │ SELECT FOR UPDATE → UPDATE │ Atomic UPDATE       │
│ withdraw     │ SELECT FOR UPDATE → UPDATE │ Atomic UPDATE + 조건 │
│ payment      │ SELECT FOR UPDATE → UPDATE │ Atomic UPDATE + 조건 │
│ delete       │ SELECT FOR UPDATE → DELETE │ SELECT → DELETE     │
└──────────────┴──────────────────────────┴─────────────────────┘
```

**핵심**: charge/refund는 **읽을 필요 자체가 없다**. `balance = balance + amount` 만 하면 된다. 읽고 락 걸고 쓰는 건 불필요한 오버헤드다.

withdraw/payment는 잔액 검증이 필요하지만, 이것도 `WHERE balance >= ?` 조건으로 DB가 atomic 하게 처리할 수 있어서 굳이 SELECT FOR UPDATE 할 필요가 없다.

---

## 구현: JPA `@Modifying` Atomic UPDATE

### 왜 `clearAutomatically = true` 가 필수인가

`@Modifying` JPQL 은 영속성 컨텍스트를 거치지 않고 DB 로 직행한다. 쿼리 실행 후에도 영속성 컨텍스트에는 **변경 전 엔티티가 그대로 남아있다**. `clearAutomatically = true` 가 없으면:

```
① UPDATE deposit SET balance = balance + 1000 WHERE code = 'A'  -- DB: 1000 → 2000
② 영속성 컨텍스트에 아직 balance=1000 인 DepositEntity 남아있음
③ 같은 트랜잭션에서 findByCode('A') 호출 → DB 안 가고 캐시에서 balance=1000 반환
④ 이후 save() 호출 시 balance=1000 으로 UPDATE → DB 도 1000 으로 롤백됨 (lost update)
```

`clearAutomatically = true` 를 붙이면 UPDATE 직후 영속성 컨텍스트가 클리어되어, 이후 조회 시 DB 에서 새 값을 읽어온다.

### JPA Repository

```java
// DepositJpaRepository.java

/**
 * 충전/환불용 — 락 없이 atomic increment.
 * clearAutomatically: UPDATE 후 영속성 컨텍스트 클리어 → 이후 조회는 DB 에서 재조회.
 */
@Modifying(clearAutomatically = true)
@Query("UPDATE DepositEntity d SET d.balance = d.balance + :amount WHERE d.code = :code")
int addBalance(@Param("code") String code, @Param("amount") Long amount);

/**
 * 출금/결제용 — 락 없이 atomic decrement + 잔액 검증.
 * WHERE balance >= :amount 로 DB 가 검증 → 영향받은 row 0개면 잔액 부족.
 */
@Modifying(clearAutomatically = true)
@Query("UPDATE DepositEntity d SET d.balance = d.balance - :amount WHERE d.code = :code AND d.balance >= :amount")
int subtractBalance(@Param("code") String code, @Param("amount") Long amount);
```

### Adapter (DepositEventAdapter)

```java
public void addBalance(String code, Long amount) {
    int updated = depositJpaRepository.addBalance(code, amount);
    if (updated == 0) {
        throw new ServiceException(DEPOSIT_NOT_FOUND);
    }
}

public void subtractBalance(String code, Long amount) {
    int updated = depositJpaRepository.subtractBalance(code, amount);
    if (updated == 0) {
        throw new ServiceException(INSUFFICIENT_BALANCE);  // 잔액 부족 or 계좌 없음
    }
}
```

### Service 호출부 (DepositEventApplication)

```java
// charge — 락 없이 atomic UPDATE
@Transactional
public DepositHistory charge(String userCode, DepositHistoryType type, Long amount) {
    String depositCode = depositPersistencePort.getDepositByUserCode(userCode)
        .orElseThrow(() -> new ServiceException(DEPOSIT_NOT_FOUND))
        .getCode();

    depositCommandPort.addBalance(depositCode, amount);  // atomic — 락 없음

    // balanceAfter 용 재조회 (clearAutomatically 덕분에 DB 에서 가져옴)
    Deposit deposit = depositPersistencePort.getDeposit(depositCode).orElseThrow();

    DepositHistory history = DepositHistory.builder()
        .userCode(userCode).deposit(deposit)
        .payerDeposit(deposit).payeeDeposit(deposit)
        .amount(amount).type(type)
        .description(generateDescription(type, amount))
        .build();
    return depositHistoryCommandPort.saveDepositHistory(history);
}

// withdraw — 락 없이 DB 가 잔액 검증
@Transactional
public DepositHistory withdraw(String userCode, DepositHistoryType type, Long amount) {
    String depositCode = depositPersistencePort.getDepositByUserCode(userCode)
        .orElseThrow(() -> new ServiceException(DEPOSIT_NOT_FOUND))
        .getCode();

    depositCommandPort.subtractBalance(depositCode, amount);  // DB 가 balance >= amount 검증

    Deposit deposit = depositPersistencePort.getDeposit(depositCode).orElseThrow();

    DepositHistory history = DepositHistory.builder()
        .userCode(userCode).deposit(deposit)
        .payerDeposit(deposit).payeeDeposit(deposit)
        .amount(amount).type(type)
        .description(generateDescription(type, amount))
        .build();
    return depositHistoryCommandPort.saveDepositHistory(history);
}

// refund — charge 와 동일하게 addBalance
@Transactional
public DepositHistory refund(String userCode, DepositHistoryType type, Long amount) {
    String depositCode = depositPersistencePort.getDepositByUserCode(userCode)
        .orElseThrow(() -> new ServiceException(DEPOSIT_NOT_FOUND))
        .getCode();

    depositCommandPort.addBalance(depositCode, amount);  // 원자적 충전

    Deposit deposit = depositPersistencePort.getDeposit(depositCode).orElseThrow();

    DepositHistory history = DepositHistory.builder()
        .userCode(userCode).deposit(deposit)
        .payerDeposit(deposit).payeeDeposit(deposit)
        .amount(amount).type(type)
        .description(generateDescription(type, amount))
        .build();
    return depositHistoryCommandPort.saveDepositHistory(history);
}
```

### PaymentServiceImpl.process()

```java
@Transactional
public Payment process(String userCode, PaymentConfirmRequest request) {
    String depositCode = depositPersistencePort.getDepositByUserCode(userCode)
        .orElseThrow(() -> new ServiceException(DEPOSIT_NOT_FOUND))
        .getCode();

    // DB 가 atomic 하게 잔액 검증 + 차감
    depositCommandPort.subtractBalance(depositCode, request.amount());

    // balanceAfter 용 재조회
    Deposit deposit = depositPersistencePort.getDeposit(depositCode).orElseThrow();
    long balanceAfter = deposit.getBalance();

    Payment payment = Payment.builder()
        .userCode(userCode).orderCode(request.orderCode())
        .amount(request.amount()).build();
    payment.setPaymentStatus(PAYMENT_COMPLETED);
    paymentRepository.save(payment);

    historyRecorder.recordPaymentHistory(userCode, depositCode, request.amount(), balanceAfter);
    return payment;
}
```

### 제거할 항목

이 방식으로 바꾸면 아래 항목이 **더 이상 필요 없어진다**:

```
DepositJpaRepository.findByUserCodeForUpdate()     ← 제거
DepositPersistencePort.getDepositByUserCodeForUpdate() ← 제거
DepositPersistenceAdapter.getDepositByUserCodeForUpdate() ← 제거
```

`@Lock(PESSIMISTIC_WRITE)` 자체를 프로젝트에서 없애도 된다.

---

## 현재 구현 vs 제안 비교

```
[현재 - 전부 비관적 락]
charge  → SELECT FOR UPDATE → Java 연산 → UPDATE (다른 트랜잭션 대기)
refund  → SELECT FOR UPDATE → Java 연산 → UPDATE (다른 트랜잭션 대기)
withdraw → SELECT FOR UPDATE → Java 검증 → UPDATE (다른 트랜잭션 대기)
payment → SELECT FOR UPDATE → Java 검증 → UPDATE (다른 트랜잭션 대기)

[제안 - Atomic UPDATE]
charge  → UPDATE ... SET balance = balance + ?
refund  → UPDATE ... SET balance = balance + ?
withdraw → UPDATE ... SET balance = balance - ? WHERE balance >= ?
payment → UPDATE ... SET balance = balance - ? WHERE balance >= ?
                                    ↑
                               DB가 atomic 하게 처리, 락 없음
```
