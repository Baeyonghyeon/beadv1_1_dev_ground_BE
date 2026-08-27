# DeepSeek Payment Information

## 1. 결제 흐름 개요

현재 프로젝트에는 **두 가지 결제 전략**이 공존하며, `order.payment.strategy` 설정으로 전환할 수 있습니다 (기본값: `feign`).

| 구분 | Feign 전략 (TO-BE) | Kafka 전략 (AS-IS, 비교 벤치마크용) |
|---|---|---|
| **방식** | 동기 호출 (OpenFeign) | 비동기 메시지 (Kafka) |
| **응답** | 결제 완료 후 응답 | 즉시 응답 (실제 처리는 Saga에서 비동기) |
| **트랜잭션** | 예치금 차감+결제 저장 원자적 처리 | 각 단계 분산 처리 |
| **후속 처리** | OrderApplication 에서 직접 Kafka Command 발행 | OrderSaga 가 이벤트 수신하여 발행 |

---

## 2. Feign 전략 결제 흐름 (기본값)

### 2.1 전체 시퀀스 다이어그램

```
Client
  │
  │  POST /api/orders (commerce)
  ▼
OrderController
  │
  ▼
OrderApplication.createOrderByOne/Selected()
  │
  ├─ ① 주문 정보 생성 및 DB 저장
  │     orderPersistencePort.createSingleOrder/SelectedOrder()
  │     → Order + OrderItems INSERT
  │
  ├─ ② Spring Event 발행 (트랜잭션 커밋 후)
  │     orderPublishEventPort.publishEvent()
  │     → ApplicationEventPublisher.publishEvent(OrderCreatedEvent)
  │     → @TransactionalEventListener(AFTER_COMMIT)
  │       → OrderCreatedEventPublisher.onOrderCreated()
  │         → Kafka: PaymentCreateCommand → payments.command.topic.purchase
  │           (※ Kafka 전략용 보조 경로, Feign 에서는 OrderSaga 측 중복 호출 가능성 있음)
  │
  ├─ ③ 결제 처리 - FeignPaymentAdapter
  │     PaymentFeignClient.processPayment()                    [OpenFeign]
  │       │
  │       │  POST /api/payments/process (payments 모듈)
  │       ▼
  │     PaymentController.processPayment()
  │       │
  │       ▼
  │     PaymentServiceImpl.process(userCode, request)          [@Transactional]
  │       │
  │       ├─ a. Deposit 조회
  │       │     depositPersistencePort.getDepositByUserCode(userCode)
  │       │     → 없으면 DEPOSIT_NOT_FOUND 예외
  │       │
  │       ├─ b. 잔액 검증
  │       │     deposit.getBalance() < amount
  │       │     → 부족 시 IllegalStateException (전체 롤백)
  │       │
  │       ├─ c. 예치금 차감
  │       │     deposit.withdraw(amount)  → balance -= amount
  │       │
  │       ├─ d. 예치금 저장
  │       │     depositCommandPort.saveDeposit(deposit)
  │       │     → UPDATE deposit SET balance = ?
  │       │
  │       ├─ e. 결제 내역 저장
  │       │     Payment.builder()...build()
  │       │     payment.setPaymentStatus(PAYMENT_COMPLETED)
  │       │     paymentRepository.save(payment)
  │       │     → INSERT INTO payment
  │       │
  │       └─ f. 예치금 이력 저장 (별도 트랜잭션)
  │             DepositHistoryRecorder.recordPaymentHistory()   [REQUIRES_NEW]
  │             → 실패해도 핵심 트랜잭션(c~e)에 영향 없음
  │
  └─ ④ 결제 결과에 따른 후속 처리
        handlePaymentResult(paymentResult, order, productCodes)
          │
          ├─ [성공] "feign" 전략 → 직접 Kafka Command 발행:
          │     orderKafkaEventPort.publishDepositSuccessCompleteOrder()
          │       → Kafka: CompleteOrderCommand → orders.command.topic.purchase
          │     orderKafkaEventPort.publishDepositSuccessCompleteDeleteCart()
          │       → Kafka: DeleteCartItemsCommand → carts.command.topic.purchase
          │     orderKafkaEventPort.publishDepositSuccessCompleteProduct()
          │       → Kafka: ProductSoldCommand → products.command.purchase
          │
          └─ [실패] → 주문 취소:
                order.cancel()
                orderPersistencePort.cancel(orderCode)
```

### 2.2 결제 트랜잭션 상세 (`PaymentServiceImpl.process()`)

```java
@Transactional  // 하나의 트랜잭션으로 원자 처리
public Payment process(String userCode, PaymentConfirmRequest request) {

    // ① Deposit 직접 조회 (Feign 호출 아님, 같은 DB)
    Deposit deposit = depositPersistencePort.getDepositByUserCode(userCode)
        .orElseThrow(() -> new ServiceException(DEPOSIT_NOT_FOUND));

    // ② 잔액 검증 → 부족 시 IllegalStateException (전체 롤백)
    if (deposit.getBalance() < request.amount()) {
        throw new IllegalStateException("예치금이 부족하여 결제를 진행할 수 없습니다.");
    }

    // ③ 예치금 차감
    deposit.withdraw(request.amount());

    // ④ 예치금 저장
    depositCommandPort.saveDeposit(deposit);

    // ⑤ 결제 내역 저장 (PAYMENT_COMPLETED)
    Payment payment = Payment.builder()...build();
    payment.setPaymentStatus(PaymentStatus.PAYMENT_COMPLETED);
    paymentRepository.save(payment);

    // ⑥ 예치금 이력 저장 (REQUIRES_NEW)
    historyRecorder.recordPaymentHistory(userCode, deposit.getCode(), amount, balanceAfter);

    return payment;
}
```

### 2.3 예치금 이력 저장 분리 (`DepositHistoryRecorder`)

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public boolean recordPaymentHistory(...) {
    try {
        // DepositHistory 생성 및 저장
        // 실패 시 로그만 남기고 false 반환 (핵심 트랜잭션 영향 없음)
    } catch (Exception e) {
        log.error("예치금 이력 저장 실패 (결제는 정상 처리됨): ...");
        return false;
    }
}
```

> **설계 의도**: 예치금 이력은 감사(audit) 용도이므로, 이력 저장 실패가 결제 자체를 롤백해서는 안 됩니다.

---

## 3. Kafka 전략 결제 흐름 (비교 벤치마크용)

### 3.1 전체 시퀀스 다이어그램

```
Client
  │
  │  POST /api/orders (commerce)
  ▼
OrderApplication.createOrderByOne/Selected()
  │
  ├─ ① 주문 정보 생성 및 DB 저장
  │     (Feign 전략과 동일)
  │
  ├─ ② Spring Event 발행 (트랜잭션 커밋 후)
  │     OrderCreatedEventPublisher.onOrderCreated()
  │       → Kafka: PaymentCreateCommand → payments.command.topic.purchase (중복①)
  │
  ├─ ③ 결제 처리 - KafkaPaymentAdapter
  │     Kafka: PaymentCreateCommand → payments.command.topic.purchase (중복②)
  │     → 즉시 PaymentResult.success() 반환 (실제 결과는 Saga에서 비동기 처리)
  │
  └─ ④ handlePaymentResult() → "kafka" 전략 → 아무것도 하지 않음
        (OrderSaga 가 이벤트 받아 후속 처리하므로 중복 발행 방지)

────────────────────── (여기서부터 비동기 Saga) ──────────────────────

PaymentKafkaHandler (payments 모듈)
  │
  │  PaymentCreateCommand 수신 (중복①+② 모두 동일 처리)
  ▼
  handleEvent(PaymentCreateCommand)
    │
    ├─ 성공:
    │   paymentService.process() → 예치금 차감 + 결제 저장
    │   Kafka: PaymentCreatedEvent → payments.event.topic.purchase
    │
    └─ 실패:
        Kafka: PaymentCreatedFailed → payments.event.topic.purchase

OrderSaga (commerce 모듈)
  │
  │  PaymentCreatedEvent 수신
  ▼
  handleEvent(PaymentCreatedEvent)
    │
    └─ Kafka: WithdrawDeposit → deposits.command.topic.purchase

DepositKafkaConsumer (payments 모듈)
  │
  │  WithdrawDeposit 수신
  ▼
  handleWithdrawCommand(WithdrawDeposit)
    │
    ├─ 성공:
    │   depositEventApplication.withdraw() → 예치금 차감 + 이력 저장
    │   Kafka: DepositWithdrawnSuccess → deposits.event.topic.purchase
    │
    └─ 실패:
        Kafka: DepositWithdrawFailed → deposits.event.topic.purchase

OrderSaga (commerce 모듈)
  │
  │  DepositWithdrawnSuccess 수신
  ▼
  handleEvent(DepositWithdrawnSuccess)
    │
    └─ 최종 처리 (4개 Kafka Command 발행):
        ├─ CompletePaymentCommand → payments.command.topic.purchase
        ├─ CompleteOrderCommand → orders.command.topic.purchase
        ├─ DeleteCartItemsCommand → carts.command.topic.purchase
        └─ ProductSoldCommand → products.command.purchase
```

### 3.2 Kafka 전략 보상 트랜잭션 (실패 경로)

```
[PaymentCreatedFailed]
  │
  ▼
OrderSaga → publishPaymentFailedToOrder()
  → Kafka: NotifyOrderCreateFailedAlertCommand → orders.command.topic.purchase
  → 주문 취소 처리

[DepositWithdrawFailed]
  │
  ▼
OrderSaga → publishDepositFailedToPayment()
  → Kafka: CancelCreatePaymentCommand → payments.command.topic.purchase
  → Payment 상태 → PAYMENT_CANCELLED

OrderSaga → publishDepositFailedToOrder()
  → Kafka: NotifyOrderCreateFailedAlertCommand → orders.command.topic.purchase
  → 주문 취소 처리
```

### 3.3 중복 발행 이슈

Kafka 전략에서 `PaymentCreateCommand` 가 두 번 발행됩니다:

| 발행 위치 | 트리거 |
|---|---|
| `OrderCreatedEventPublisher.onOrderCreated()` | Spring Event (AFTER_COMMIT) → PaymentCreateCommand |
| `KafkaPaymentAdapter.processPayment()` | OrderApplication 에서 직접 호출 → PaymentCreateCommand |

현재는 `PaymentKafkaHandler` 가 동일하게 처리하므로 2회 결제가 발생할 수 있는 구조입니다. (멱등성 처리는 명시적으로 구현되어 있지 않습니다.)

---

## 4. 결제 유형별 흐름

### 4.1 예치금 결제 (`PaymentType.DEPOSIT`)

```
PaymentController.depositPayment()  →  /api/payments/deposit
  │
  ▼
PaymentServiceImpl.pay(request)
  │
  └─ handleDepositPayment(userCode, orderCode, amount)
       ├─ Payment 생성 (PAYMENT_COMPLETED)
       └─ paymentRepository.save(payment)
```

> **참고**: 예치금 차감 없이 결제 내역만 저장됩니다. 실제 잔액 차감은 `process()` 또는 `DepositEventApplication.withdraw()` 에서 수행됩니다.

### 4.2 토스 결제 (`PaymentType.TOSS_PAYMENT`)

```
PaymentController.tossPayment()  →  /api/payments/toss
  │
  ▼
PaymentServiceImpl.pay(request)
  │
  └─ handleTossPayment(userCode, orderCode, paymentKey, amount)
       │
       ├─ ① processTossPayment() → 토스페이먼츠 API 호출
       │     POST https://api.tosspayments.com/v1/payments/confirm
       │     Header: Authorization: Basic {secretKey}
       │
       ├─ ② 성공 시 → Kafka: ChargeDeposit (예치금 충전)
       │     kafkaTemplate.send(depositsCommandTopic, command)
       │     → DepositKafkaConsumer.handleChargeCommand()
       │       → depositEventApplication.charge()
       │
       ├─ ③ Payment 저장 (PAYMENT_PENDING)
       │     → 추후 DepositChargedSuccess 콜백 → PAYMENT_COMPLETED
       │     → 또는 DepositChargeFailed 콜백 → 토스 환불
       │
       └─ ④ 실패 시 → IllegalStateException
```

#### 토스 결제 콜백 흐름

```
[예치금 충전 성공]
DepositChargedSuccess → deposits.event.topic.payment
  │
  ▼
PaymentKafkaHandler.handleEvent(DepositChargedSuccess)
  │
  └─ paymentService.applyDepositCharge(userCode)
       → Payment 상태: PAYMENT_PENDING → PAYMENT_COMPLETED

[예치금 충전 실패]
DepositChargeFailed → deposits.event.topic.payment
  │
  ▼
PaymentKafkaHandler.handleEvent(DepositChargeFailed)
  │
  └─ paymentService.tossRefund(request)
       → POST https://api.tosspayments.com/v1/payments/{paymentKey}/cancel
       → Payment 상태: PAYMENT_PENDING → PAYMENT_REFUNDED
```

---

## 5. 모듈 간 통신 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                        Commerce 모듈                              │
│                                                                   │
│  OrderController → OrderApplication                               │
│       │                    │                                      │
│       │          ┌────────┴────────┐                              │
│       │          │  PaymentAdapter │  (전략 패턴)                   │
│       │          │  ├── Feign      │──OpenFeign──┐                │
│       │          │  └── Kafka      │──Kafka──┐   │                │
│       │          └─────────────────┘         │   │                │
│       │                                      │   │                │
│  OrderSaga (Kafka Listener)                  │   │                │
│    - PaymentCreatedEvent 수신                 │   │                │
│    - DepositWithdrawnSuccess 수신             │   │                │
│    - PaymentCreatedFailed 수신               │   │                │
│    - DepositWithdrawFailed 수신              │   │                │
└──────────────────────┬───────────────────────┼───┼────────────────┘
                       │ Kafka                  │   │ OpenFeign
                       ▼                       │   │
┌──────────────────────────────────────────────┼───┼────────────────┐
│                   Payments 모듈               │   │                │
│                                              │   │                │
│  PaymentController ←─────────────────────────┘   │                │
│       │                                           │                │
│       ▼                                           │                │
│  PaymentServiceImpl.process()                     │                │
│    - 예치금 차감 + 결제 저장 (@Transactional)       │                │
│    - DepositHistoryRecorder (REQUIRES_NEW)        │                │
│                                                   │                │
│  PaymentKafkaHandler ←────────────────────────────┘                │
│    - PaymentCreateCommand 수신                                    │
│    - DepositChargedSuccess/DepositChargeFailed 수신               │
│    - DepositRefundCommand 수신                                    │
│                                                                   │
│  DepositKafkaConsumer                                             │
│    - CreateDeposit / ChargeDeposit / WithdrawDeposit              │
│    - RefundDeposit / SettlementChargeDeposit                      │
│                                                                   │
│  DepositEventApplication                                          │
│    - charge() / withdraw() / refund() / createDeposit()           │
└───────────────────────────────────────────────────────────────────┘
```

---

## 6. Payment 엔티티 상태 머신

```
                  ┌─────────────────────┐
                  │   PAYMENT_ACCEPTED  │
                  └────────┬────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
    ┌─────────────┐ ┌──────────┐ ┌─────────────┐
    │  COMPLETED  │ │ CANCELLED│ │   REFUNDED  │
    └─────────────┘ └──────────┘ └─────────────┘
                              ▲
                              │
                        ┌─────┴──────┐
                        │   PENDING  │  ← 토스 결제 대기
                        └────────────┘
```

| 상태 | 설정 위치 | 설명 |
|---|---|---|
| `PAYMENT_PENDING` | `handleTossPayment()` | 토스 결제 요청 후 콜백 대기 |
| `PAYMENT_COMPLETED` | `process()`, `handleDepositPayment()`, `applyDepositCharge()` | 결제 완료 |
| `PAYMENT_CANCELLED` | `cancelDepositPayment()` | 결제 취소 |
| `PAYMENT_REFUNDED` | `refund()`, `tossRefund()` | 환불 완료 |

---

## 7. 예치금 충전 흐름 (Toss)

```
사용자
  │
  │  POST /api/payments/toss (예치금 충전)
  ▼
PaymentController.tossPayment()
  │
  ▼
PaymentServiceImpl.pay() → handleTossPayment()
  │
  ├─ ① Toss Payments API: POST /v1/payments/confirm
  │     → 성공 시 계속
  │
  ├─ ② Kafka: ChargeDeposit → deposits.command.topic
  │     (TOSS 타입으로 예치금 충전 커맨드)
  │
  ├─ ③ Payment 저장 (PAYMENT_PENDING)
  │
  └─ ④ 응답: "토스 결제가 요청되었습니다."

──────────────── (비동기) ────────────────

DepositKafkaConsumer.handleChargeCommand()
  │
  ├─ depositEventApplication.charge(userCode, CHARGE_TOSS, amount)
  │   ├─ deposit.charge(amount) → balance += amount
  │   ├─ deposit 저장
  │   └─ DepositHistory 저장 (CHARGE_TOSS)
  │
  ├─ [성공] Kafka: DepositChargedSuccess → deposits.event.topic.payment
  │   └─ PaymentKafkaHandler → applyDepositCharge() → PAYMENT_COMPLETED
  │
  └─ [실패] Kafka: DepositChargeFailed → deposits.event.topic.payment
      └─ PaymentKafkaHandler → tossRefund() → Toss 결제 취소 API
```

---

## 8. 환불 흐름

### 8.1 주문 취소 환불

```
OrderApplication.cancelOrder()
  │
  ▼
orderPublishEventPort.publishRefundEvent(userCode, amount, orderCode)
  │
  ▼
Spring Event: DepositRefundCommand
  │
  ▼
OrderCreatedEventPublisher.onRefundCreated()  [AFTER_COMMIT]
  │
  ▼
orderEventPort.publishDepositRefundCreated()
  │
  ▼
Kafka: DepositRefundCommand → payments.command.topic.purchase
  │
  ▼
PaymentKafkaHandler.handleEvent(DepositRefundCommand)
  │
  ├─ paymentService.refund(request)
  │   └─ Payment 저장 (PAYMENT_REFUNDED)
  │
  └─ Kafka: RefundDeposit → deposits.command.topic
      │
      ▼
      DepositKafkaConsumer.handleRefundCommand()
        └─ depositEventApplication.refund()
            ├─ deposit.charge(amount) → 예치금 환불
            └─ DepositHistory 저장 (REFUND_INTERNAL)
```

### 8.2 토스 결제 실패 환불

```
DepositChargeFailed 수신
  │
  ▼
PaymentKafkaHandler.handleEvent(DepositChargeFailed)
  │
  ▼
PaymentServiceImpl.tossRefund(request)
  │
  ├─ Toss API: POST /v1/payments/{paymentKey}/cancel
  │   Body: {"cancelReason": "예치금 충전 불가"}
  │
  └─ Payment 상태: PAYMENT_PENDING → PAYMENT_REFUNDED
```

---

## 9. 핵심 설계 포인트

| 설계 요소 | 내용 |
|---|---|
| **전략 패턴** | `OrderPaymentPort` 인터페이스, `FeignPaymentAdapter` / `KafkaPaymentAdapter` 구현체 |
| **원자적 트랜잭션** | Feign 전략에서 예치금 차감+결제 저장이 `@Transactional` 로 원자 처리 |
| **트랜잭션 분리** | 예치금 이력은 `REQUIRES_NEW` 로 분리하여 감사 데이터 손실을 허용 |
| **비동기 후처리** | `@TransactionalEventListener(AFTER_COMMIT)` 로 트랜잭션 커밋 후 Kafka 발행 |
| **보상 트랜잭션** | Kafka 전략: 실패 이벤트 → OrderSaga → CancelCreatePayment / RefundDeposit |
| **DTO/Record** | `PaymentResult` (record), `PaymentFeignRequest/PaymentFeignResponse` (record) |
| **Kafka 토픽 분리** | Command 토픽(명령)과 Event 토픽(결과)을 분리하여 CQRS 유사 패턴 |