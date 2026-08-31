# 예치금 동시성 A/B 테스트 가이드

> 목적: 이력서/면접 자료의 **"동시 요청 N건 부하 테스트에서 lost update M건 → 0건"** 문장에
> 실측 수치를 채워 넣기 위한 테스트. **실행 완료 — 결과는 4장.**
>
> 작성일 2026-08-29 · 실행일 2026-08-31 · 브랜치 `feat/async-payment-kafka` · 기준 커밋 `6a6add3`

---

## 1. 지금 상태

| 항목 | 상태 |
|---|---|
| 테스트 코드 작성 | 완료 |
| `./gradlew :payments:compileTestJava` | 통과 확인 |
| 테스트 실행 / 수치 확보 | **완료 (2026-08-31, 3회 실행 전부 동일)** |
| 커밋 | 안 함 (working tree 에만 있음) |

수정한 파일은 하나뿐이다.

```
payments/src/test/java/io/devground/payments/payment/service/PaymentProcessIntegrationTest.java
```

기존 5스레드 회귀 테스트(`process_concurrent_pessimisticLock_preventsLostUpdate`)는
**그대로 두고**, A/B 측정용 테스트를 같은 파일에 추가했다.

---

## 2. 무엇을 측정하나

동일한 시나리오를 **조회 방식만 바꿔** 두 번 돌린다.

| | A. 락 없음 (비교군) | B. 비관적 락 (운영 코드) |
|---|---|---|
| 조회 | `getDepositByUserCode()` | `getDepositByUserCodeForUpdate()` → `SELECT ... FOR UPDATE` |
| 실행 주체 | `UnlockedPaymentProcessor` (테스트 파일 하단 nested class) | `PaymentServiceImpl.process()` 그대로 |
| 나머지 처리 순서 | 동일 | 동일 |

A 비교군은 운영 코드와 **조회 한 줄만** 다르다. 나머지(잔액 검증 → `withdraw` → `saveDeposit`
→ `Payment` 저장 → 이력 저장)는 순서까지 같게 맞춰 놨다.

### 파라미터

```java
AB_CONCURRENCY     = 100      // 동시 요청 건수
AB_UNIT_AMOUNT     = 1,000원  // 건당 결제 금액
AB_INITIAL_BALANCE = 100,000원 // = 100 x 1,000, 전 건 성공 시 잔액이 정확히 0
```

초기 잔액을 `요청 수 x 단가` 로 딱 맞춘 이유: 잔액 부족 실패를 0건으로 만들어
**잔액 차이가 곧 lost update** 가 되게 하려고.

### 계산식

```
lost update 건수 = (기대 차감액 − 실제 차감액) / 단가
```

덮어쓰기가 일어나면 실제로는 돈이 **덜** 빠져나간다. 그 차이를 단가로 나누면
사라진 차감 건수가 된다.

---

## 3. 실행 방법

Docker(Testcontainers 로 MySQL 8.0 + Kafka 컨테이너를 띄운다)가 실행 중이어야 한다.

```bash
./gradlew :payments:test \
  --tests '*PaymentProcessIntegrationTest.concurrentDeduction_withoutLock_vs_withPessimisticLock' \
  -i
```

컨테이너 최초 다운로드가 없으면 대략 1~2분.

---

## 4. 실측 결과 (2026-08-31)

macOS / Docker Desktop 29.3.1 / Testcontainers MySQL 8.0 에서 **3회 실행, 전 회차 동일**.

```
[A. 락 없음] 동시요청=100건, 단가=1,000원, 초기잔액=100,000원
  성공=100건 / 실패=0건
  기대 차감액=100,000원, 실제 차감액=4,000원, 최종 잔액=96,000원
  lost update=96건, 예치금 이력=100건

[B. 비관적 락] 동시요청=100건, 단가=1,000원, 초기잔액=100,000원
  성공=100건 / 실패=0건
  기대 차감액=100,000원, 실제 차감액=100,000원, 최종 잔액=0원
  lost update=0건, 예치금 이력=100건

=> lost update: 96건 → 0건
```

| 항목 | A. 락 없음 | B. 비관적 락 |
|---|---|---|
| 성공 / 실패 | 100 / 0 | 100 / 0 |
| 기대 차감액 | 100,000원 | 100,000원 |
| 실제 차감액 | **4,000원** | 100,000원 |
| lost update | **96건** | **0건** |
| 예치금 이력 | 100건 | 100건 |

### 읽는 법

A는 **100건 전부 "결제 성공" 으로 응답하고 이력도 100건 남겼는데 실제로 빠져나간 돈은 4,000원**이다.
100개 트랜잭션이 같은 잔액(100,000)을 읽고 각자 99,000을 계산해 덮어썼기 때문에,
마지막에 커밋한 소수의 트랜잭션 결과만 남는다.
즉 A의 피해는 잔액 96,000원 과다 잔존인 동시에 **감사 기록(이력 100건)과 잔액(4건분)의 불일치**다.

B는 `SELECT ... FOR UPDATE` 로 read-then-write 구간이 직렬화되어
차감액·이력·잔액 세 값이 전부 일치한다 (100,000원 = 100건 x 1,000원, 이력 100건, 잔액 0원).

### 재현성

A의 96건은 3회 모두 같은 값이 나왔다. 다만 원리상 머신 속도에 따라 흔들릴 수 있는 값이므로,
면접에서는 "100건 중 96건" 보다 **"거의 전부가 유실됐다"** 는 성질을 앞세우는 편이 안전하다.
B의 0건은 락으로 보장되는 결정론적 값이다.

---

## 5. 검증 항목 ↔ 이력서 문구 대응

테스트의 단언을 이력서 세 줄과 1:1로 맞춰 놨다.

| 이력서 문구 | 테스트 단언 |
|---|---|
| 동시 요청 N건 부하 테스트에서 lost update M건 → 0건 | A: `lostUpdates > 0` / B: `lostUpdates == 0` |
| 총 차감액 = 결제 건수 x 단가 일치 | `actualDeducted == success * AB_UNIT_AMOUNT` |
| 예치금 이력 건수 = 결제 건수 (이중 차감 0건) | `historyCount == success` |

부가로 `success == 100`, `finalBalance == 0` 도 검증한다.

---

## 6. 곁들여 손댄 것 (이유 포함)

면접에서 물어볼 수 있는 지점이라 남긴다.

### 6-1. Hikari 풀을 32로 올림

```java
@DynamicPropertySource
static void poolProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
    registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
}
```

기본값 10이면 요청이 DB 앞 커넥션 풀에서 줄을 서느라 실제 DB 동시성이 사라진다.
그러면 A 비교군에서도 lost update 가 안 나와 A/B 차이 자체가 관측되지 않는다.

### 6-2. A 경로에만 `entityManager.flush()`

`saveDeposit()` 직후에 넣어 **예치금 UPDATE 를 이력 INSERT 보다 먼저 확정**시킨다.

순서가 뒤집히면 두 트랜잭션이 각각 FK 검사용 S락을 부모 행에 잡은 채 서로 X락을 기다려
**deadlock** 이 난다. 그건 lost update 와 다른 현상이라 측정에서 걷어내려는 것.
B(운영 코드)는 `SELECT ... FOR UPDATE` 로 이미 X락을 먼저 잡으므로 이 문제가 없다 —
즉 flush 는 A를 **더** 유리하게 해줄 뿐, lost update 를 인위적으로 늘리지 않는다.

---

## 7. 주의 / 트러블슈팅

- **A 비교군의 `lostUpdates > 0` 단언은 타이밍 의존이라 흔들릴 수 있다.**
  머신이 느리거나 부하가 약하면 0건이 나와 테스트가 실패한다.
  그럴 땐 `AB_CONCURRENCY` 를 200, 300으로 올려서 다시 잡으면 된다.
  (B 쪽 단언은 결정론적이라 흔들리지 않는다.)
  2026-08-31 기준 이 머신에서는 3회 모두 96건으로 안정적이었다.
- A/B 두 arm 은 **같은 Spring 컨텍스트, 서로 다른 `userCode`** 를 쓴다
  (`user-ab-nolock` / `user-ab-lock`). 서로 간섭하지 않는다.
- 이 테스트 클래스에 nested `@TestConfiguration` 이 생겼으므로 컨텍스트가
  다른 테스트 클래스와 분리되어 캐시된다. 전체 테스트 실행 시간이 조금 늘 수 있다.

---

## 8. 남은 일

1. ~~Docker 켜고 3장 명령으로 테스트 실행~~ (2026-08-31 완료)
2. ~~4장 로그에서 `실제 차감액`, `lost update` 수치 확보~~ (96건 → 0건)
3. ~~이력서/면접 자료의 `N건`, `M건` 자리에 실측값 기입~~ (N=100, M=96)
4. (선택) 테스트 코드 커밋

### 관련 코드 위치

| 대상 | 경로 |
|---|---|
| 테스트 | `payments/.../payment/service/PaymentProcessIntegrationTest.java` |
| 운영 결제 로직 | `payments/.../payment/service/PaymentServiceImpl.java` (`process()`) |
| 락 쿼리 | `payments/.../deposit/infrastructure/adapter/out/persistence/DepositJpaRepository.java` |
| 이력 저장 | `payments/.../payment/service/DepositHistoryRecorder.java` |
| 테스트 베이스 | `payments/src/test/java/io/devground/payments/support/PaymentIntegrationTestBase.java` |
