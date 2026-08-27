# DeepSeek Saga Information

## 1. Saga 패턴 개요

이 프로젝트에서는 **두 가지 Saga 구현 방식**이 사용되고 있습니다:

| 구분 | Commerce OrderSaga | Payments SettlementSaga |
|---|---|---|
| **방식** | Choreography (이벤트 협업) | Orchestration (중앙 제어) |
| **상태 관리** | Saga Entity 없음 (Kafka 토픽으로만 흐름 제어) | Saga Entity로 명시적 상태 추적 |
| **보상 트랜잭션** | Kafka 실패 이벤트로 암시적 처리 | SagaService + 보상 상태(COMPENSATING) 명시적 지원 |
| **멱등성** | Kafka offset 기반 | referenceCode + sagaType + sagaStatus Unique Key |

---

## 2. Core Saga 프레임워크 (`payments/common/saga/`)

### 2.1 Saga Entity (`entity/Saga.java`)

정산 Saga의 상태를 추적하는 JPA 엔티티입니다.

```
필드:
- id (PK, auto-increment)
- sagaId (UUID, unique)       ← 외부에 노출되는 Saga 식별자
- sagaType (enum)             ← Saga 유형 (SETTLEMENT_DEPOSIT_CHARGE 등)
- referenceCode (String)      ← 비즈니스 식별자 (예: orderCode)
- sagaStatus (enum)           ← 현재 상태
- currentStep (enum)          ← 현재 단계
- lastErrorMessage (String)   ← 실패 메시지
- version (Long)              ← Optimistic Lock
```

**핵심 제약조건**: `(referenceCode, sagaType, sagaStatus)` Unique → 동일 비즈니스 건에 대해 같은 유형의 IN_PROCESS Saga가 중복 생성되지 않도록 방지 (멱등성).

### 2.2 SagaStatus (`vo/SagaStatus.java`)

```
                    ┌─────────────┐
                    │ IN_PROCESS  │
                    └──────┬──────┘
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    ┌──────────┐   ┌──────────────┐   ┌──────────┐
    │ SUCCESS  │   │ COMPENSATING │   │  FAILED  │
    └──────────┘   └──────┬───────┘   └──────────┘
                          ▼
                   ┌──────────────┐
                   │ COMPENSATED  │
                   └──────────────┘

Terminal 상태: SUCCESS, FAILED, COMPENSATED
```

### 2.3 SagaStep (`vo/SagaStep.java`)

Saga가 현재 어느 단계까지 진행되었는지 나타냅니다.

```
공통 단계:
  INIT → ... → COMPLETE / FAILED / COMPENSATING → COMPENSATED

정산 Saga 단계:
  INIT → SETTLEMENT_COMMAND_SENT → DEPOSIT_CHARGE_SUCCESS → SETTLEMENT_EVENT_PUBLISHED → COMPLETE
```

### 2.4 SagaType (`vo/SagaType.java`)

```
- PRODUCT_IMAGE_REGIST / UPDATE / DELETE  (상품 이미지 Saga - 현재 사용 안 함)
- SETTLEMENT_DEPOSIT_CHARGE               (정산 입금 Saga - 활성)
```

### 2.5 SagaService (`service/SagaService.java`)

Saga 생명주기를 관리하는 핵심 서비스입니다.

| 메서드 | 설명 |
|---|---|
| `startSaga(referenceCode, sagaType)` | 멱등적 Saga 생성 - 기존 IN_PROCESS가 있으면 반환, 없으면 새로 생성 |
| `updateStep(sagaId, step)` | 단계 갱신 - 종료/보상 중인 Saga는 갱신 거부 |
| `updateToSuccess(sagaId)` | 성공 처리 (step=COMPLETE, status=SUCCESS) |
| `updateToFail(sagaId, msg)` | 실패 처리 (step=FAILED, status=FAILED) |
| `updateToCompensating(sagaId)` | 보상 시작 (step=COMPENSATING, status=COMPENSATING) |
| `updateToCompensated(sagaId, msg)` | 보상 완료 (step=COMPENSATED, status=COMPENSATED) |
| `findLatestSagaByReferenceCode(refCode)` | referenceCode로 최신 Saga 조회 |
| `getSaga(sagaId)` | sagaId로 조회 |

---

## 3. 정산( Settlement) Saga - Orchestration 방식

### 3.1 전체 흐름도

```
Spring Batch Job (settlementJob)
│
├── Step 1: settlementStep
│   Reader:  UnsettledOrderItemReader  ← 미정산 주문 조회 (commerce 모듈 Feign 호출)
│   Processor: SettleConvertProcessor  ← UnsettledOrderItem → Settlement Entity 변환
│   Writer:  SettlementDataWriter      ← Settlement DB 저장
│
└── Step 2: depositStep
    Reader:  SettlementDepositReader   ← SETTLEMENT_CREATED 상태 Settlement 조회
    Processor: SettlementDepositProcessor ← Settlement → SettlementChargeDeposit Command
    Writer:  SettlementDepositWriter   ← Saga Orchestrator 호출
              │
              ▼
    SettlementSagaOrchestrator.startSettlementDepositChargeSaga()
              │
              ├── ① Saga 생성 (SagaService.startSaga)
              ├── ② Kafka: SettlementChargeDeposit → deposits.command.topic
              └── ③ Step 갱신: SETTLEMENT_COMMAND_SENT
                        │
                        ▼ (Kafka)
              DepositKafkaConsumer.handleSettlementChargeCommand()
                        │
                        ├── ④ DepositEventApplication.charge() → 예치금 충전
                        └── ⑤ Kafka: SettlementDepositChargedSuccess → deposits.event.topic
                              │
                              ▼ (Kafka)
              SettlementEventHandler.handleEvent()
                        │
                        ▼
              SettlementSagaOrchestrator.handleDepositChargeSuccess()
                        │
                        ├── ⑥ Step 갱신: DEPOSIT_CHARGE_SUCCESS
                        ├── ⑦ Kafka: SettlementCreatedSuccess → settlements.event.topic
                        ├── ⑧ Step 갱신: SETTLEMENT_EVENT_PUBLISHED
                        └── ⑨ Saga 완료: SUCCESS

[실패 경로]
  Kafka 발행 실패 → sagaService.updateToFail()
  Deposit 충전 실패 → sagaService.updateToFail()
  ※ 보상 트랜잭션(COMPENSATING)은 2차 고도화 예정 (TODO 주석)
```

### 3.2 핵심 파일

| 파일 | 역할 |
|---|---|
| `SettlementSagaOrchestrator.java` | 정산 Saga의 중앙 Orchestrator. 명령 발행, 이벤트 처리, 상태 관리 |
| `SettlementEventHandler.java` | Kafka 이벤트를 수신하여 Orchestrator에 전달하는 진입점 |
| `SettlementDepositWriter.java` | Batch Writer에서 Orchestrator를 호출하여 Saga 시작 |
| `SettlementDepositProcessor.java` | Settlement → SettlementChargeDeposit Command 변환 |
| `DepositKafkaConsumer.java` | `handleSettlementChargeCommand()` 로 예치금 충전 수행 |

### 3.3 정산 요금 정책

`Settlement.java` 에서 `applySettlementPolicy()` 호출 시 계산됨:
- **정산 수수료** = totalAmount - settlementBalance
- **정산 잔액** = totalAmount × settlementRate (설정값 `custom.settlement.rate`)

---

## 4. 예치금(Deposit) 처리 흐름

### 4.1 Deposit Domain (`deposit/domain/deposit/Deposit.java`)

```java
Deposit {
    code, userCode, balance, createdAt, updatedAt
    
    charge(amount)     // 잔액 증가 (amount > 0 검증)
    withdraw(amount)   // 잔액 감소 (잔액 부족 시 예외)
}
```

### 4.2 DepositEventApplication

예치금의 CUD 작업 + 이력 저장을 담당하는 서비스:

| 메서드 | 설명 |
|---|---|
| `createDeposit(userCode)` | 신규 예치금 계좌 생성 (잔액 0원) |
| `charge(userCode, type, amount)` | 충전: deposit.charge() + 이력 저장 |
| `withdraw(userCode, type, amount)` | 출금: deposit.withdraw() + 이력 저장 |
| `refund(userCode, type, amount)` | 환불: deposit.charge() + 이력 저장 (충전과 동일 로직) |
| `deleteDeposit(userCode)` | 예치금 계좌 삭제 |

### 4.3 DepositKafkaConsumer - Command Handler

```
소비 Topic:
  deposits.command.topic.name       ← ChargeDeposit, WithdrawDeposit, RefundDeposit, SettlementChargeDeposit
  deposits.command.topic.join       ← CreateDeposit, DeleteDeposit
  deposits.command.topic.purchase   ← WithdrawDeposit

각 Command 처리 패턴:
  ① 정상 처리 → Success 이벤트 발행
  ② 예외 발생 → Failed 이벤트 발행
```

### 4.4 DepositHistoryType

```
CHARGE_TRANSFER   - 계좌 이체 충전
CHARGE_TOSS       - 토스 결제 충전
PAYMENT_TOSS      - 토스 결제
PAYMENT_INTERNAL  - 예치금 결제
REFUND_INTERNAL   - 예치금 환불
REFUND_TOSS       - 토스 환불
SETTLEMENT        - 정산 입금
```

---

## 5. 결제(Payment) - Kafka Handler (`PaymentKafkaHandler`)

### 5.1 Kafka 비교 전략

```java
@KafkaHandler
public void handleEvent(PaymentCreateCommand command) {
    // 결제 처리 (예치금 차감 + 결제 저장 - 원자적 @Transactional)
    Payment payment = paymentService.process(command.userCode(), request);
    
    // 성공 이벤트 → OrderSaga 가 후속 처리
    kafkaTemplate.send(paymentPurchaseEventTopic, event);
    // 실패 이벤트 → OrderSaga 가 주문 취소
    kafkaTemplate.send(paymentPurchaseEventTopic, failedEvent);
}
```

### 5.2 예치금 충전 콜백 (Toss)

```java
@KafkaHandler
public void handleEvent(DepositChargedSuccess event) {
    paymentService.applyDepositCharge(userCode);  // PAYMENT_PENDING → PAYMENT_COMPLETED
}

@KafkaHandler
public void handleEvent(DepositChargeFailed event) {
    paymentService.tossRefund(request);  // 토스 결제 취소 API 호출
}
```

### 5.3 PaymentServiceImpl.process() - 핵심 결제 트랜잭션

```
@Transactional (원자적 처리)
① Deposit 조회 (depositPersistencePort)
② 잔액 검증 → 부족 시 IllegalStateException (전체 롤백)
③ deposit.withdraw(amount) → 예치금 차감
④ deposit 저장
⑤ Payment 저장 (PAYMENT_COMPLETED)
⑥ 예치금 이력 저장 → REQUIRES_NEW 트랜잭션 (실패해도 핵심 트랜잭션 영향 없음)
```

---

## 6. Commerce OrderSaga - Choreography 방식

Commerce 모듈의 `OrderSaga.java`는 Kafka 이벤트 기반의 **Choreography Saga**입니다. 명시적인 Saga Entity 없이 토픽 간 이벤트 연쇄로 흐름을 제어합니다.

### 6.1 이벤트 흐름도

```
OrderCreatedEvent (주문 생성)
  │
  ▼
OrderSaga → PaymentCreateCommand 발행 → payments.command.topic.purchase
  │
  ├─ 성공 시:
  │  PaymentCreatedEvent ← payments.event.topic.purchase
  │    │
  │    ▼
  │  OrderSaga → WithdrawDeposit 발행 → deposits.command.topic.purchase
  │    │
  │    ├─ 성공 시:
  │    │  DepositWithdrawnSuccess ← deposits.event.topic.purchase
  │    │    │
  │    │    ▼
  │    │  OrderSaga → 4가지 후속 처리:
  │    │    ① 주문 완료 (publishDepositSuccessCompleteOrder)
  │    │    ② 장바구니 삭제 (publishDepositSuccessCompleteDeleteCart)
  │    │    ③ 상품 판매완료 (publishDepositSuccessCompleteProduct)
  │    │    ④ 결제 완료 처리 (publishDepositSuccessCompletePayment)
  │    │
  │    └─ 실패 시:
  │       DepositWithdrawFailed ← deposits.event.topic.purchase
  │         │
  │         ▼
  │       OrderSaga → Payment 환불 (DepositRefundCommand)
  │                    ※ 보상 트랜잭션
  │
  └─ 실패 시:
     PaymentCreatedFailed ← payments.event.topic.purchase
       │
       ▼
     OrderSaga → 주문 취소 (publishPaymentFailedToOrder)
```

### 6.2 결제 전략 패턴

`OrderApplication`은 두 가지 결제 어댑터를 전략 패턴으로 사용합니다:

| 전략 | 구현체 | 동작 방식 |
|---|---|---|
| **Feign** | `feignPaymentAdapter` | 동기 호출 → payments 모듈에서 즉시 결제+예치금 차감 원자 처리 → 후속 이벤트 즉시 발행 |
| **Kafka** | `kafkaPaymentAdapter` | 비동기 메시지 → PaymentCreateCommand 발행 → OrderSaga가 이벤트 받아 후속 처리 |

설정 `order.payment.strategy` 값으로 전환 가능 (기본값: `feign`).

---

## 7. Kafka 인프라 & 에러 처리

### 7.1 공통 에러 처리 (`KafkaErrorHandler`)

```
전략: Retry → DLT (Dead Letter Topic)

- 재시도: Exponential Backoff (최대 5회)
  - 초기 간격: 1초
  - 배율: 2.0x
  - 최대 간격: 20초
- Non-Retryable: DeserializationException → 즉시 DLT
- DLT: {원본토픽}.DLT 로 전송
```

### 7.2 주요 Kafka 토픽

```
[예치금]
- deposits.command.topic.name       ← 예치금 명령 (충전/출금/환불/정산)
- deposits.command.topic.join       ← 회원가입 연동 (생성/삭제)
- deposits.command.topic.purchase   ← 구매 연동 (출금)
- deposits.event.topic.name         ← 예치금 이벤트 (정산 충전 성공 등)
- deposits.event.topic.payment      ← 결제 연동 이벤트 (충전 성공/실패)
- deposits.event.topic.join         ← 회원가입 연동 이벤트
- deposits.event.topic.purchase     ← 구매 연동 이벤트 (출금 성공/실패)

[결제]
- payments.command.topic.purchase   ← 결제 명령 (Kafka 전략)
- payments.event.topic.name         ← 결제 이벤트
- payments.event.topic.purchase     ← 결제 결과 이벤트

[주문]
- orders.event.topic.purchase       ← 주문 이벤트

[정산]
- settlements.event.topic.name      ← 정산 완료 이벤트
```

---

## 8. 설계 관찰 사항

### 8.1 장점
- **멱등성**: SagaService.startSaga()가 Unique Key 제약으로 중복 생성을 방지
- **Optimistic Locking**: Saga Entity의 `@Version` 필드로 동시 업데이트 충돌 방지
- **트랜잭션 분리**: PaymentServiceImpl.process()에서 예치금 이력 저장을 `REQUIRES_NEW`로 분리해 핵심 트랜잭션 보호
- **전략 패턴**: Feign vs Kafka 결제 전략을 설정으로 전환 가능

### 8.2 현재 한계 / TODO
- **정산 Saga 보상 트랜잭션 미구현** (`SettlementSagaOrchestrator.java:132`): Settlement 상태 되돌리기 등 보상 로직이 TODO로 남아 있음
- **SettlementDepositChargeFailed 이벤트 미생성** (`DepositKafkaConsumer.java:257`): 정산 실패 시 일반 DepositChargeFailed로 대체 사용 중
- **상품 이미지 Saga 타입**은 정의되어 있으나 현재 사용되지 않음 (`PRODUCT_IMAGE_REGIST/UPDATE/DELETE`)
- **OrderSaga**는 Kafka Choreography 방식으로, 명시적 Saga Entity가 없어 실패 추적/복구가 제한적

### 8.3 Saga 비교 요약

```
┌────────────────────────────────────────────────────────────┐
│                  Orchestration (정산)                       │
│  SettlementSagaOrchestrator                                │
│    ├── 중앙 제어, 명시적 상태 관리                             │
│    ├── Saga Entity로 단계별 추적 가능                         │
│    ├── 보상 트랜잭션 구조화 가능                              │
│    └── 단일 실패 지점 (Orchestrator)                         │
├────────────────────────────────────────────────────────────┤
│                  Choreography (주문)                        │
│  OrderSaga (Kafka Listener)                                │
│    ├── 이벤트 기반 분산 협업                                  │
│    ├── Saga Entity 없음 (Kafka 토픽이 흐름 제어)              │
│    ├── 장애 추적/복구 어려움                                  │
│    └── 모듈 간 결합도 낮음                                    │
└────────────────────────────────────────────────────────────┘
```