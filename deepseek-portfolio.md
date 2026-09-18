# DeepSeek Portfolio — MSA 질문: 예치금·정산의 롤백 트랜잭션과 재시작

---

## Q-1. Feign 동기 호출 시 네트워크 장애 — 스레드는 어떻게 되는가?

### 답변: **타임아웃이 전혀 설정되어 있지 않아, 스레드가 무한정 블로킹됩니다. 스레드 풀 고갈로 전체 서비스가 마비될 수 있습니다.**

### 현재 상태 — 타임아웃 미설정

`PaymentFeignClient`는 OpenFeign 동기 호출로 payments 모듈의 `/api/payments/process`를 호출합니다. 그러나 **어디에도 타임아웃이 설정되어 있지 않습니다**.

```java
// PaymentFeignClient.java — 타임아웃 설정 없음
@FeignClient(
    name = "order-to-payment",
    url = "${external.payment-url:http://localhost:8085}",
    path = "/api/payments"
)
public interface PaymentFeignClient {
    @PostMapping("/process")
    BaseResponse<PaymentFeignResponse> processPayment(...);
}
```

**확인 결과**:
| 확인 항목 | 결과 |
|---|---|
| `application.yml` Feign timeout 설정 | ❌ 없음 |
| `FeignConfig` / `FeignConfiguration` 클래스 | ❌ 없음 |
| Apache HttpClient5 / OkHttp 의존성 | ❌ 없음 (`java.net.HttpURLConnection` 기본 사용) |
| Resilience4j / Circuit Breaker | ❌ 없음 |

### 네트워크 장애 시나리오

```
[사용자 요청]
  │
  ▼
Tomcat Worker Thread (예: http-nio-8081-exec-5)
  │
  ├── OrderApplication.createOrderByOne()  [@Transactional]
  │     ├── ① 주문 DB 저장 ✅ (DB 커넥션 1개 점유)
  │     ├── ② FeignPaymentAdapter.processPayment()
  │     │     └── paymentFeignClient.processPayment(userCode, request)
  │     │           │
  │     │           │  POST http://localhost:8085/api/payments/process
  │     │           │  → Payments 서비스 다운 / 네트워크 단절
  │     │           │
  │     │           ▼
  │     │     ████████  무한정 대기 (BLOCKED)  ████████
  │     │     █ java.net.HttpURLConnection 의 █
  │     │     █ default connectTimeout = 0   █
  │     │     █ default readTimeout = 0       █
  │     │     █ (0 = infinite)                █
  │     │     ██████████████████████████████████
  │     │
  │     └── ③ 응답 반환 ← 절대 도달하지 않음
  │
  └── DB 트랜잭션 커넥션도 반환되지 않음
```

### 연쇄 장애 시나리오

```
1차: Payments 서비스 멈춤
  │
  ▼
2차: Commerce Tomcat 스레드 풀 고갈
  │  - 기본 max-threads: 200
  │  - 요청 1건당 1개 스레드가 무한정 점유
  │  - 200개 요청이 쌓이면 모든 스레드가 BLOCKED
  │
  ▼
3차: Commerce 서비스 응답 불능 (503)
  │  - 신규 요청 → Tomcat acceptor queue 가득 참 → reject
  │
  ▼
4차: DB 커넥션 풀 고갈
  │  - @Transactional 블로킹된 스레드들이 DB 커넥션 점유
  │  - HikariCP 기본 maximumPoolSize: 10
  │  - 10개 요청만 블로킹되어도 DB 커넥션 풀 고갈
  │  - Commerce 내 다른 API (주문 조회 등)도 DB 커넥션 못 얻어서 실패
  │
  ▼
5차: 전체 장애로 번짐
```

### `FeignPaymentAdapter` 의 예외 처리도 동작하지 않음

```java
// FeignPaymentAdapter.java — 타임아웃이 없으면 catch 블록에 도달하지 않음
public PaymentResult processPayment(...) {
    try {
        // ...feign 호출 → 여기서 무한정 블로킹
        return PaymentResult.success(orderCode, totalAmount);
    } catch (Exception e) {
        // 타임아웃이 없으면 이 블록에 영원히 도달하지 않음
        return PaymentResult.fail(orderCode, "결제 서비스 호출 실패: " + e.getMessage());
    }
}
```

### 개선 방안

```java
// 방안 1: OpenFeign 타임아웃 설정 (application.yml)
spring.cloud.openfeign.client.config.order-to-payment:
  connectTimeout: 3000     // 연결 타임아웃 3초
  readTimeout: 5000        // 응답 타임아웃 5초

// 방안 2: Apache HttpClient5 전환 + 세밀한 타임아웃
// build.gradle
implementation 'io.github.openfeign:feign-hc5'

// 방안 3: Resilience4j Circuit Breaker
// 일정 횟수 실패 시 회로 차단 → 빠른 실패 (fail-fast)
// build.gradle
implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j'

// 방안 4: Kafka 전략으로 전환 (이미 구현됨)
order.payment.strategy: kafka  // 비동기로 전환하면 호출 스레드가 블로킹되지 않음
```

> **현재 즉시 적용 가능한 완화책**: `order.payment.strategy: kafka` 로 전환하면 Feign 호출 자체를 하지 않으므로, payments 모듈 장애가 commerce 모듈의 스레드 풀 고갈로 이어지지 않습니다.

---

## Q-2. DLT(Dead Letter Topic) — 5회 재시도 후 DLT 적재가 실제로 구성되어 있는가?

### 답변: **✅ 구성됨. payments 모듈의 모든 Kafka Consumer 는 5회 재시도 후 DLT 로 이동합니다. 단, DLT 를 소비하는 재처리기는 없습니다.**

### 2-1. DLT 구성 코드 검증

`KafkaErrorHandler.java` (payments 모듈):

```java
@Bean
public CommonErrorHandler commonErrorHandler() {
    // 1. DLT Recovery: {원본토픽}.DLT 로 전송
    DeadLetterPublishingRecoverer recover = new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        (record, exception) -> {
            String dltTopic = record.topic() + ".DLT";  // 예: deposits-commands.DLT
            return new TopicPartition(dltTopic, record.partition());
        }
    );

    // 2. Retry: Exponential Backoff, 최대 5회
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
    backOff.setInitialInterval(1000L);   // 1초 → 2초 → 4초 → 8초 → 16초
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(20000L);      // 최대 20초

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recover, backOff);

    // 3. 역직렬화 실패 → 재시도 없이 즉시 DLT
    errorHandler.addNotRetryableExceptions(DeserializationException.class);

    return errorHandler;
}

// 4. 모든 @KafkaListener 에 자동 적용
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(...) {
    factory.setCommonErrorHandler(commonErrorHandler);
    return factory;
}
```

### 2-2. 적용 대상

`kafkaListenerContainerFactory` 빈 이름이 Spring Kafka 기본값이므로, payments 모듈의 모든 Consumer 에 적용:

| Consumer | 수신 토픽 | Retry 5회 | DLT |
|---|---|---|---|
| `DepositKafkaConsumer` | `deposits-commands`, `deposits-purchase-commands`, `deposits-join-commands` | ✅ | `*.DLT` |
| `PaymentKafkaHandler` | `payments-purchase-commands`, `payments-events`, `deposits-payment-events` | ✅ | `*.DLT` |
| `SettlementEventHandler` | `deposits-events` | ✅ | `*.DLT` |

### 2-3. Producer 측 재시도 (별도)

```yaml
# application.yml — Kafka Producer retry는 DLT와 별개로 동작
acks: all
delivery.timeout.ms: 120000   # 최대 2분까지 Producer 자체 재전송
enable.idempotence: true      # 중복 방지
```

### 2-4. DLT 재처리 — ❌ 없음

```java
// 프로젝트 전체에서 *.DLT 토픽을 소비하는 Consumer 없음
// → DLT 적재 후 수동 개입 필요
```

> **면접 답변**: *"Kafka Consumer 처리 실패 시 Exponential Backoff 로 최대 5회 재시도하고, 5회 모두 실패하면 `{원본토픽}.DLT` Dead Letter Topic 에 적재합니다. 역직렬화 예외는 재시도 의미가 없으므로 즉시 DLT 로 보냅니다. Producer 측에서도 `delivery.timeout.ms` 2분 + `acks=all` + 멱등성으로 재전송을 보장합니다. 다만 DLT 적재 후 자동 재처리기는 아직 없어 운영자가 수동으로 확인 후 재처리해야 합니다."*

---

## Q-3. 예치금·정산에서 롤백(보상 트랜잭션)이 누락된 곳은 어디인가?

### 답변: **6개 Command 핸들러 중 2곳에서 보상 소비자가 없고, 정산 Saga 3곳에서 보상이 누락되었습니다.**

### 3-1. 전체 실패 경로 — 소비자 존재 여부

```
DepositKafkaConsumer — 6개 Command 핸들러
│
├── ① CreateDeposit       실패 → DepositCreateFailed     → UserSaga 소비     ✅
├── ② ChargeDeposit (Toss) 실패 → DepositChargeFailed     → PaymentKafkaHandler 소비 ✅
├── ② ChargeDeposit (정산) 실패 → DepositChargeFailed     → 소비자 없음        ❌ #1
├── ③ WithdrawDeposit     실패 → DepositWithdrawFailed    → OrderSaga 소비    ✅
├── ④ RefundDeposit       실패 → DepositRefundFailed     → 소비자 없음        ❌ #2
├── ⑤ DeleteDeposit       실패 → DepositDeleteFailed     → UserSaga 소비     ✅
└── ⑥ SettlementCharge    실패 → DepositChargeFailed (재사용) → 소비자 없음    ❌ #1

Settlement Saga — 3개 실패 지점
│
├── ⑦ Kafka send 실패 (start)   → Saga FAILED → Settlement 상태 그대로  ❌ #3
├── ⑧ Kafka send 실패 (success) → Saga FAILED → Settlement 상태 그대로  ❌ #4
└── ⑨ Batch Writer Skip         → item.fail() → 재처리 불가            ❌ #5
```

### 3-2. 누락 상세

#### ❌ #1 정산 입금 실패 — 소비자 단절 (가장 심각)

```java
// DepositKafkaConsumer.java — 정산 충전 실패 시
} catch (Exception e) {
    // TODO: SettlementDepositChargeFailed 이벤트 생성 필요 ← 아직 안 만듦
    kafkaTemplate.send(depositsEventTopicName,
        new DepositChargeFailed(...));  // deposits-events 토픽으로 발행
}
```

`SettlementEventHandler`는 `SettlementDepositChargedSuccess` 만 처리:

```java
@KafkaListener(topics = {"${deposits.event.topic.name}"})  // deposits-events
public class SettlementEventHandler {
    @KafkaHandler
    public void handleEvent(@Payload SettlementDepositChargedSuccess event) { ... }
    // DepositChargeFailed 는 핸들링하지 않음!
}
```

`SettlementSagaOrchestrator.handleDepositChargeFailure()` 메서드는 존재하지만 **호출 경로가 완전히 단절**되어 있습니다.

#### ❌ #2 예치금 환불 실패 — 무응답

```java
// DepositKafkaConsumer.java
@KafkaHandler
public void handleRefundCommand(@Payload RefundDeposit command) {
    try { ... } catch (Exception e) {
        kafkaTemplate.send(depositsPaymentEventTopicName,
            new DepositRefundFailed(...));  // deposits-payment-events 토픽
    }
}
```

`PaymentKafkaHandler`가 `deposits-payment-events` 를 소비하지만 `DepositRefundFailed` 는 처리하지 않음. 사용자는 이미 "주문 취소 완료" 응답을 받은 후라 환불 누락을 인지하지 못합니다.

#### ❌ #3, #4 Saga Orchestrator Kafka 발행 실패

```java
// SettlementSagaOrchestrator.java
try {
    kafkaTemplate.send(depositsCommandTopicName, command);  // 비동기 → catch 못 잡음
    sagaService.updateStep(sagaId, SETTLEMENT_COMMAND_SENT);
} catch (Exception e) {  // 직렬화 오류만 잡힘, 네트워크 실패는 못 잡음
    sagaService.updateToFail(sagaId, "...");  // Saga FAILED
    // Settlement 상태는 SETTLEMENT_CREATED 그대로 → 불일치 발생
}
```

#### ❌ #5 Settlement 배치 Skip → 영구 누락

```java
// SettlementStepListener.java
@Override public void onSkipInWrite(Settlement item, Throwable t) {
    item.fail();  // SETTLEMENT_FAILED
    // TODO: 실패한 항목을 별도 테이블에 저장하여 추후 재처리
}
```

`SettlementDepositReader`는 `SETTLEMENT_CREATED`만 조회 → FAILED 건은 영원히 재처리 안 됨.

### 3-3. 요약

```
┌──────────────────────────────────────┬────────┬──────────────────────┐
│ 실패 경로                              │ 보상    │ 결과                   │
├──────────────────────────────────────┼────────┼──────────────────────┤
│ CreateDeposit 실패                    │   ✅   │ 회원가입 롤백            │
│ ChargeDeposit 실패 (Toss)             │   ✅   │ 토스 결제 취소 API       │
│ WithdrawDeposit 실패                  │   ✅   │ 주문 취소 + 결제 취소    │
│ DeleteDeposit 실패                    │   ✅   │ 회원탈퇴 롤백 알림       │
├──────────────────────────────────────┼────────┼──────────────────────┤
│ 정산 ChargeDeposit 실패               │   ❌   │ 판매자 입금 영구 누락     │
│ RefundDeposit 실패                    │   ❌   │ 사용자 환불 영구 누락     │
│ Saga Kafka send 실패                  │   ❌   │ Saga-Settlement 불일치  │
│ Settlement Write Skip                │   ❌   │ 판매자 입금 영구 누락     │
└──────────────────────────────────────┴────────┴──────────────────────┘
```

> **면접 답변**: *"예치금 충전/출금/생성/삭제는 실패 이벤트 발행 → 상위 Saga 소비의 보상 체인이 연결되어 있습니다. 하지만 정산 입금 실패와 예치금 환불 실패는 이벤트 소비자가 없어 복구되지 않습니다. 정산 입금 쪽은 전용 Failed 이벤트 타입도 아직 정의되지 않았고, SettlementEventHandler 가 성공 이벤트만 처리하고 있습니다. 이 부분들은 2차 고도화 대상으로 코드에 TODO 가 명시되어 있습니다."*

---

## Q0. 결제 완료 후 비동기 후속 처리 최적화

### 포트폴리오 문구 (수정 제안)

> 결제 완료 후 후속 작업(장바구니 삭제, 주문 상태 변경, **상품 판매완료 처리**)을
> `@TransactionalEventListener(AFTER_COMMIT)` + Kafka 비동기 이벤트로 분리하여,
> API 응답 경로에서 제거했습니다.

### 실제 비동기로 분리된 후속 작업 — 3건 (기존 포트폴리오에서는 2건만 언급)

결제 성공 후 `OrderApplication.handlePaymentResult()` (Feign 전략 기준) 에서 Kafka로 비동기 발행되는 Command 목록:

| # | Kafka Command | 소비 주체 | 실제 수행 작업 | 대상 데이터 |
|---|---|---|---|---|
| 1 | `CompleteOrderCommand` | `OrderCommandConsumer` | `orderUseCase.paidOrder()` → 주문 상태를 PAID로 변경 | `order` 1건 UPDATE |
| 2 | `DeleteCartItemsCommand` | `CartCommandConsumer` | `cartUseCase.removeCartItems()` → 장바구니 상품 삭제 | `cart_items` N건 DELETE |
| 3 | **`ProductSoldCommand`** ⚡ | **`ProductKafkaListener`** | **`updateStatusToSoldByOrder()` → 상품 상태를 SOLD로 변경** | **`product` N건 UPDATE** |

> ⚡ **3번(상품 판매완료 처리)은 기존 포트폴리오에서 누락되어 있습니다.** 실제 코드 기준으로 3개의 Kafka 비동기 작업이 분리되어 있습니다.

### 코드 추적

```
[동기 경로 - API 응답 전 완료]
OrderApplication.createOrderByOne/Selected()
  ├── ① 주문 DB 저장 (Order + OrderItems INSERT)
  ├── ② Feign: PaymentServiceImpl.process()
  │       ├── 예치금 차감 (Deposit UPDATE)
  │       ├── 결제 저장 (Payment INSERT)
  │       └── 이력 저장 (DepositHistory INSERT, REQUIRES_NEW)
  └── ③ 응답 반환 ←── 여기까지 약 302ms

[비동기 경로 - AFTER_COMMIT + Kafka]
  ├── ④ Kafka: CompleteOrderCommand
  │       → OrderCommandConsumer → paidOrder() → order.status UPDATE
  ├── ⑤ Kafka: DeleteCartItemsCommand
  │       → CartCommandConsumer → removeCartItems() → cart_items DELETE
  └── ⑥ Kafka: ProductSoldCommand
          → ProductKafkaListener → updateStatusToSoldByOrder() → product.status UPDATE
```

### 동기 vs 비동기 비교

| 구분 | 작업 | 예상 소요 시간 |
|---|---|---|
| **동기** | ① 주문 저장 + ② 결제 처리(예치금 차감+결제 저장) | ~302ms |
| **비동기 #1** | ④ 주문 상태 PAID 변경 (1건 UPDATE) | ~DB 시간 |
| **비동기 #2** | ⑤ 장바구니 상품 삭제 (N건 DELETE) | ~DB 시간 |
| **비동기 #3** ⚡ | ⑥ 상품 상태 SOLD 변경 (N건 UPDATE) | ~DB 시간 |
| **합계** | 동기 + 비동기 전체 | ~340ms (동기로 처리 시) |

> **기존 포트폴리오 수정 포인트**: "장바구니 삭제, 주문 상태 변경" → "장바구니 삭제, 주문 상태 변경, **상품 판매완료 처리**"

---

## Q1. 예치금(Deposit)에서 롤백 트랜잭션(보상 트랜잭션)을 구성했는가?

### 답변: **부분적으로 구성됨. 이벤트 기반 보상 패턴은 존재하나 자동화된 Saga 보상은 미완성.**

### 1-1. 구성된 부분

**(a) Kafka 실패 이벤트 기반 보상 (DepositKafkaConsumer)**

`DepositKafkaConsumer`의 모든 Command Handler는 try-catch 로 감싸져 있으며, 실패 시 Failed 이벤트를 상위 Saga에 발행하여 보상 처리를 트리거합니다.

```java
// DepositKafkaConsumer.java — 패턴
try {
    DepositHistory history = depositEventApplication.charge(...);
    kafkaTemplate.send(successTopic, successEvent);    // 성공 이벤트
} catch (Exception e) {
    kafkaTemplate.send(failureTopic, failureEvent);    // 실패 이벤트 → 상위 Saga 보상
}
```

이 패턴이 적용된 Command:
| Command | 실패 이벤트 | 보상 주체 |
|---|---|---|
| `ChargeDeposit` | `DepositChargeFailed` | `PaymentKafkaHandler` → `tossRefund()` |
| `WithdrawDeposit` | `DepositWithdrawFailed` | `OrderSaga` → `CancelCreatePayment` + `NotifyOrderCreateFailed` |
| `RefundDeposit` | `DepositRefundFailed` | (발행만 되고 처리 주체 없음) |

**(b) 트랜잭션 롤백 (`PaymentServiceImpl.process()`)**

Feign 전략에서 예치금 차감 + 결제 저장은 하나의 `@Transactional`로 원자 처리됩니다. 잔액 부족이나 DB 오류 발생 시 전체가 롤백되어 데이터 정합성을 보장합니다.

```java
@Transactional
public Payment process(String userCode, PaymentConfirmRequest request) {
    Deposit deposit = depositPersistencePort.getDepositByUserCode(userCode)...;
    if (deposit.getBalance() < request.amount()) throw IllegalStateException;  // → 롤백
    deposit.withdraw(request.amount());
    depositCommandPort.saveDeposit(deposit);    // 예치금 차감
    paymentRepository.save(payment);            // 결제 저장
    // ↑ 둘 다 같은 트랜잭션 — 하나라도 실패하면 전체 롤백
}
```

**(c) Saga Entity의 보상 구조 (사용되지 않음)**

`SagaService`에는 보상 트랜잭션을 위한 메서드가 정의되어 있으나, 실제로 **호출되는 곳은 없습니다**.

```java
// SagaService.java — 정의만 있고 호출되지 않음
public void updateToCompensating(String sagaId) { ... }   // 사용 안 됨
public void updateToCompensated(String sagaId, ...) { ... } // 사용 안 됨
```

### 1-2. 구성되지 않은 부분

| 누락된 부분 | 상세 |
|---|---|
| **Deposit 이력 기반 롤백** | `DepositHistory`는 모든 거래를 기록하지만, 이 기록을 기반으로 자동 롤백하는 로직은 없음 |
| **잔액 불일치 복구** | Deposit.balance 와 DepositHistory.balanceAfter 간 크로스체크/복구 로직 없음 |
| **RefundDeposit 실패 처리** | `DepositRefundFailed` 이벤트를 발행만 하고 소비하는 핸들러가 없음 (무응답) |

---

## Q2. 정산(Settlement)에서 롤백 트랜잭션(보상 트랜잭션)을 구성했는가?

### 답변: **구조만 설계되어 있고, 실제 구현은 TODO 상태. 정산 실패 시 수동 복구 필요.**

### 2-1. 설계된 구조

`SettlementSagaOrchestrator`는 Orchestration Saga 패턴으로 설계되었으며, 3단계로 구성됩니다:

```
INIT → SETTLEMENT_COMMAND_SENT → DEPOSIT_CHARGE_SUCCESS → SETTLEMENT_EVENT_PUBLISHED → SUCCESS
                                   ↘ (실패 시)
                                     FAILED  (※ 보상 없음)
```

### 2-2. 실제 구현 상태

```java
// SettlementSagaOrchestrator.java:132
public void handleDepositChargeFailure(String orderCode, String errorMessage) {
    try {
        Saga saga = sagaService.findLatestSagaByReferenceCode(orderCode);
        String sagaId = saga.getSagaId();
        // ...
        sagaService.updateToFail(sagaId, "정산 입금 실패: " + errorMessage);
        
        // TODO: 보상 트랜잭션 2차 때 구현합니다. : Settlement 상태 되돌리기 등
        //       ↑↑↑ 실제 보상 로직이 없음 ↑↑↑
        
    } catch (Exception e) {
        log.error("정산 입금 실패 처리 중 오류...", e);
    }
}
```

**현재 동작**: 정산 입금 실패 시 Saga 상태만 `FAILED`로 변경되고, 아래 항목들은 그대로 남습니다:
- 판매자의 예치금은 충전되지 않음 (Kafka 발행 실패 또는 Deposit 처리 실패)
- Settlement 상태는 `SETTLEMENT_CREATED` 그대로 (변경되지 않음)
- `SETTLEMENT_FAILED` 상태는 `SettlementStepListener.onSkipInWrite()` 에서는 설정되지만 Orchestrator에서는 설정되지 않음

### 2-3. Skip Listener의 부분적 보상

`SettlementStepListener.onSkipInWrite()`에서 Writer 실패 시 Settlement 상태를 FAILED로 변경합니다:

```java
@Override
public void onSkipInWrite(Settlement item, Throwable t) {
    item.fail();  // SettlementStatus → SETTLEMENT_FAILED
    // TODO: 실패한 항목을 별도 테이블에 저장하여 추후 재처리
}
```

---

## Q3. 재시작(Restart) 메커니즘은 구성했는가?

### 답변: **3계층에서 재시도를 구성했으나, 자동 재시작(복구)은 미완성.**

### 3-1. Kafka 레벨 재시도 — ✅ 구성됨

`KafkaErrorHandler`에서 소비자 재시도 정책 적용:

```yaml
# application.yml → KafkaErrorHandler.java
전략: Exponential Backoff + DLT
- 최대 Retry: 5회
- 초기 간격: 1초 (배율 2.0x)
- 최대 간격: 20초
- Non-Retryable: DeserializationException → 즉시 DLT
- Retry 실패: Dead Letter Topic (*.DLT) 전송
```

Producer 측 내구성 설정:
```yaml
acks: all                    # 모든 복제본에 쓰기 완료까지 대기
enable.idempence: true       # 중복 메시지 방지
delivery.timeout.ms: 120000  # 최대 2분까지 전송 재시도
```

### 3-2. Spring Batch 레벨 재시도 — ✅ 구성됨

```java
// SettlementStepConfiguration.java
settlementStep:
  Skip: IllegalArgumentException, NullPointerException → 최대 10회
  Retry: DataAccessException, KafkaException → 최대 3회

depositStep:
  Retry: KafkaException → 최대 3회
```

### 3-3. Saga 레벨 복구 쿼리 — ⚠️ 쿼리만 있고 자동 실행 안 됨

`SagaRepository`에 복구를 위한 쿼리가 정의되어 있으나, 이를 주기적으로 실행하는 스케줄러는 없습니다:

```java
// SagaRepository.java
// 장시간 IN_PROCESS 상태인 Saga 조회 (멈춘 Saga 감지용)
List<Saga> findSagasByCurrentStepInAndStartedAtBefore(
    List<SagaStep> steps, LocalDateTime startedAt);

// 오래된 Saga 정리용
List<Saga> findAllBySagaStatusInAndUpdatedAtBefore(
    List<SagaStatus> statuses, LocalDateTime updatedAt);
```

### 3-4. 정산 배치 재시작 — ⚠️ 실패 건 자동 재처리 안 됨

**현재 동작**:
- `SettlementDepositReader`는 `SETTLEMENT_CREATED` 상태만 조회
- 실패한 Settlement(`SETTLEMENT_FAILED`)는 **영원히 재처리되지 않음**
- `SettlementJobScheduler`는 매달 2일 새벽 2시에만 실행 (cron)
- 수동으로 배치를 재실행해도 `SETTLEMENT_FAILED` 건은 제외됨

**Spring Batch 재시작**:
- `JobParameters`에 `executeTime`이 포함되어 매 실행마다 새로운 JobInstance 생성
- 동일 파라미터로 재실행 시 Spring Batch가 자동으로 실패 지점부터 재시작 가능
- 그러나 cron 스케줄로만 실행되므로 수동 재시작은 별도 API나 관리자 호출 필요

### 3-5. 예치금 결제 재시작 — ❌ 없음

- Feign 전략: HTTP 요청 실패 시 `PaymentResult.fail()` 반환 → 주문 취소. 재시도 없음.
- Kafka 전략: 메시지 소비 실패 시 KafkaErrorHandler의 Retry/DLT에 의존. 메시지 손실 가능성.

### 3-6. 실패 데이터 보관

| 실패 종류 | 보관 위치 | 재처리 가능 여부 |
|---|---|---|
| Kafka 소비 실패 | DLT 토픽 (`*.DLT`) | 수동 (DLT 소비자 없음) |
| 배치 Skip | 로그만 남고 데이터는 유실 | ❌ (`TODO: 실패한 항목을 별도 테이블에 저장`) |
| 정산 입금 실패 | Saga 테이블 (FAILED 상태) | ❌ (FAILED Saga 조회/재처리 스케줄러 없음) |
| 예치금 거래 실패 | DepositHistory 없음, 로그만 | ❌ |
| Toss 결제 실패 | Payment (PAYMENT_REFUNDED), 로그 | △ (환불은 되지만 재시도는 수동) |

---

## 요약: 롤백 트랜잭션 & 재시작 성숙도 평가

```
┌─────────────────────────────────────────────────────────────────┐
│                     롤백 트랜잭션 평가                             │
├────────────────────┬──────────────┬─────────────────────────────┤
│ 예치금 이벤트 보상   │      △       │ 실패 이벤트 발행까지는 함          │
│                    │              │ DepostRefundFailed 소비자 없음  │
├────────────────────┼──────────────┼─────────────────────────────┤
│ 결제 트랜잭션 롤백   │      ✅      │ @Transactional 원자 처리         │
├────────────────────┼──────────────┼─────────────────────────────┤
│ 정산 Saga 보상      │      ❌      │ TODO 주석만 있고 구현 안 됨       │
├────────────────────┼──────────────┼─────────────────────────────┤
│ SagaEntity 보상     │      ❌      │ 메서드만 정의, 호출부 없음         │
├────────────────────┼──────────────┼─────────────────────────────┤
│ 이력 기반 복구       │      ❌      │ DepositHistory 기록만, 복구 로직 없음│
└────────────────────┴──────────────┴─────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       재시작 평가                                 │
├────────────────────┬──────────────┬─────────────────────────────┤
│ Kafka Retry + DLT  │      ✅      │ 최대 5회, DLT 적재             │
├────────────────────┼──────────────┼─────────────────────────────┤
│ Batch Retry/Skip   │      ✅      │ Retry 3회, Skip 10회          │
├────────────────────┼──────────────┼─────────────────────────────┤
│ DLT 재처리          │      ❌      │ DLT 소비자 없음                │
├────────────────────┼──────────────┼─────────────────────────────┤
│ 실패 Saga 자동 복구  │      ❌      │ 복구 쿼리만 있고 스케줄러 없음    │
├────────────────────┼──────────────┼─────────────────────────────┤
│ 실패 Settlement 재처리│     ❌      │ FAILED 상태 제외하고 읽음        │
├────────────────────┼──────────────┼─────────────────────────────┤
│ 결제 실패 자동 재시도 │      ❌      │ Feign: 없음, Kafka: DLT 의존   │
├────────────────────┼──────────────┼─────────────────────────────┤
│ Producer 멱등성     │      ✅      │ enable.idempotence=true       │
└────────────────────┴──────────────┴─────────────────────────────┘
```

### 향후 개선이 필요한 지점

1. **정산 Saga 보상 트랜잭션**: `handleDepositChargeFailure()` 에서 Settlement 상태를 FAILED로 변경하고, 필요 시 재처리 대상으로 별도 관리
2. **DLT 재처리기**: Dead Letter Topic을 소비하여 재처리하거나 관리자 알림을 보내는 소비자 구현
3. **실패 Saga 자동 복구 스케줄러**: `findSagasByCurrentStepInAndStartedAtBefore()` 쿼리를 주기적으로 실행하여 멈춘 Saga를 재시작하거나 알림
4. **실패 Settlement 재처리**: `SETTLEMENT_FAILED` 상태 건도 배치 대상에 포함하거나 별도 재처리 배치 구현
5. **실패 데이터 영속화**: Skip/실패 시 로그뿐 아니라 별도 테이블에 저장하여 추적 및 재처리 가능하게 개선
6. **결제 재시도**: Feign 전략에서 일시적 오류에 대한 Retry (Resilience4j 등) 도입