# DeepSeek Kafka 메시지 멱등성 분석

## 개요

현재 Kafka 메시징은 **Producer 레벨의 멱등성은 적용되어 있지만, Consumer 레벨의 중복 제거는 전혀 구성되어 있지 않다**.
리밸런스나 크래시로 인해 동일 메시지가 두 번 소비되면 그대로 두 번 처리되어, 예치금 이중 충전/출금이나 결제 중복이 발생할 수 있다.

---

## Producer — 멱등적 프로듀서 적용 완료

```yaml
# payments/src/main/resources/application.yml
spring.kafka.producer:
  acks: all                       # 모든 복제본 쓰기 완료까지 대기
  enable.idempotence: true        # 단일 프로듀서 세션 내 중복 제거
```

브로커는 `ProducerID + SequenceNumber` 조합으로 동일 프로듀서 세션에서 재전송된 중복 메시지를 탐지하여 폐기한다.
네트워크 타임아웃으로 프로듀서가 `send()` 를 재시도해도, 브로커가 "이미 받은 메시지"임을 인지하고 버린다.

**범위**: 단일 프로듀서 세션 내에서만 유효하다 (PID/epoch 기준). 애플리케이션 재시작 이후의 중복은 방지하지 못한다.

---

## Consumer — 중복 제거 장치 없음

```yaml
# payments/src/main/resources/application.yml
spring.kafka.consumer:
  group-id: payments-consumer-group
  # enable.auto.commit: true        (기본값, 주기적으로 offset 자동 커밋)
  # isolation.level: read_uncommitted (기본값, 커밋되지 않은 메시지도 읽음)
```

기본 설정인 `enable.auto.commit = true` 는 일정 주기로 offset 을 자동 커밋한다.
컨슈머가 메시지를 처리한 직후, offset 커밋이 실행되기 전에 크래시가 발생하면
재시작 후 동일한 offset 부터 다시 읽기 때문에 같은 메시지가 재전달된다.
이때 중복 여부를 판단하는 애플리케이션 로직이 없으므로 그대로 두 번 처리된다.

### 시나리오: 컨슈머 처리 중 크래시

```
1. 컨슈머가 ChargeDeposit(userCode=A, amount=10000) 를 폴링
2. DepositEventApplication.charge() 실행 (잔액: 0 → 10000)
3. auto-commit 실행 전 컨슈머 크래시
4. 컨슈머 재시작, 마지막 커밋된 offset 부터 다시 읽음
5. 동일한 ChargeDeposit 메시지를 다시 소비
6. DepositEventApplication.charge() 재실행 (잔액: 10000 → 20000)
=> 이중 충전 발생
```

---

## 애플리케이션 레벨 멱등성: 확보된 곳과 누락된 곳

### SagaService.startSaga() — DB 유니크 제약으로 중복 방지

```java
// SagaService.java
public String startSaga(String referenceCode, SagaType sagaType) {
    return sagaRepository.findFirstByReferenceCodeAndSagaTypeAndSagaStatusOrderByStartedAtDesc(
            referenceCode, sagaType, SagaStatus.IN_PROCESS
        ).map(Saga::getSagaId)
        .orElseGet(() -> {
            Saga saga = Saga.builder()...build();
            return sagaRepository.save(saga).getSagaId();
        });
}
```

`(referenceCode, sagaType, sagaStatus)` 유니크 제약 덕분에 동일한 IN_PROCESS Saga 가 중복 생성되지 않는다.
동시에 두 요청이 동일 Saga 를 시작하려 해도, 먼저 도달한 쪽만 INSERT 에 성공하고
다른 쪽은 `DataIntegrityViolationException` 이 발생한다.

**payments 모듈에서 멱등성이 보장된 유일한 지점이다.**

### 예치금 연산 — 중복 방지 없음

`DepositKafkaConsumer` 의 모든 핸들러는 중복 확인 없이 명령을 처리한다:

| 명령 핸들러 | 중복 시 동작 | 영향 |
|---|---|---|
| `handleChargeCommand` | `deposit.charge(amount)` 2회 실행 | 이중 충전 |
| `handleWithdrawCommand` | `deposit.withdraw(amount)` 2회 실행 | 이중 출금 (잔액 마이너스 가능) |
| `handleRefundCommand` | `deposit.charge(amount)` 2회 실행 | 이중 환불 |
| `handleSettlementChargeCommand` | 정산 입금 2회 실행 | 판매자에게 이중 지급 |

### 결제 연산 — 중복 방지 없음

`PaymentServiceImpl.process()` 는 호출될 때마다 새로운 `Payment` row 를 생성한다.
`Payment.orderCode` 에 유니크 제약이 없어, 동일 주문에 대해 여러 결제 레코드가 생성될 수 있다.

### 예치금 이력 — code(UUID)만 유니크

`DepositHistoryEntity` 는 `code` 컬럼(UUID)에만 유니크 제약이 있다. 호출마다 새 UUID 가 생성되므로
중복된 비즈니스 데이터도 무조건 INSERT 에 성공한다.

---

## 요약

```
Producer:   enable.idempotence=true                   -> 세션 내 중복 차단
Consumer:   enable.auto.commit=true (기본값)            -> at-least-once, 중복 제거 없음
애플리케이션: SagaService.startSaga() 유니크 제약         -> 유일하게 보호된 지점
            DepositKafkaConsumer (6개 핸들러)           -> 전부 중복 처리 가능
            PaymentServiceImpl.process()               -> 중복 결제 가능
            DepositHistory                             -> 비즈니스 키 유니크 제약 없음
```

---

## 개선 방안

### 최소한의 조치: DB 유니크 제약 추가

중복된 INSERT 를 DB 레벨에서 차단하여 멱등성을 확보한다:

```sql
-- 동일 주문에 대한 중복 결제 방지
ALTER TABLE payment ADD CONSTRAINT uk_payment_order_code UNIQUE (order_code);

-- 예치금 이력 중복 방지
ALTER TABLE deposit_history ADD CONSTRAINT uk_history_dedup
    UNIQUE (deposit_code, type, amount, created_at);
```

애플리케이션에서는 `DataIntegrityViolationException` 을 캐치하여
이미 처리된 메시지로 간주하고 무시(skip) 처리한다.

### 중간 수준: 컨슈머에서 멱등성 키 체크

처리 전에 멱등성 키를 확인하여 중복을 건너뛴다:

```java
// Redis 또는 로컬 캐시로 처리 완료된 키 관리
private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

@KafkaHandler
public void handleChargeCommand(@Payload ChargeDeposit command) {
    String idempotencyKey = command.userCode() + ":" + command.type() + ":" + command.amount();
    if (!processedKeys.add(idempotencyKey)) {
        log.warn("중복 메시지 skip: key={}", idempotencyKey);
        return;
    }
    // 처리 로직...
}
```

### 완전한 조치: Kafka Transactions + Exactly-Once

프로듀서에 `transactional.id` 를 설정하고 컨슈머에 `isolation.level: read_committed` 를 적용하여
end-to-end exactly-once 의미를 보장한다. 인프라 복잡도와 처리량 영향이 있으므로 신중하게 도입해야 한다.