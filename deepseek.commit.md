# Commit Plan

## Commit 1: feat: 정산 Saga 보상 트랜잭션 구현

**설명**: 정산 입금 실패 시 보상 트랜잭션 체인 연결. SettlementDepositChargeFailed 이벤트 신규 생성,
SettlementEventHandler 가 이를 소비하여 SettlementSagaOrchestrator 로 전달,
Orchestrator 는 Saga FAILED 처리 + Settlement 상태를 FAILED 로 변경.

**파일**:

```
NEW  core/src/main/java/io/devground/core/event/deposit/SettlementDepositChargeFailed.java
MOD  payments/src/main/java/io/devground/payments/deposit/infrastructure/adapter/in/messaging/DepositKafkaConsumer.java
MOD  payments/src/main/java/io/devground/payments/settlement/service/handler/SettlementEventHandler.java
MOD  payments/src/main/java/io/devground/payments/settlement/saga/SettlementSagaOrchestrator.java
MOD  payments/src/main/java/io/devground/payments/settlement/repository/SettlementRepository.java
MOD  payments/src/main/java/io/devground/payments/settlement/model/entity/Settlement.java
```

**변경 상세**:

- `SettlementDepositChargeFailed.java` — 신규 이벤트 record (userCode, amount, orderCode, msg)
- `DepositKafkaConsumer.java` — handleSettlementChargeCommand() catch 블록에서 DepositChargeFailed 대신 SettlementDepositChargeFailed 발행
- `SettlementEventHandler.java` — SettlementDepositChargeFailed 핸들러 추가, sagaOrchestrator.handleDepositChargeFailure() 호출
- `SettlementSagaOrchestrator.java` — SettlementRepository 주입, handleDepositChargeFailure() 내 Settlement.fail() 보상 추가, startSettlementDepositChargeSaga() / handleDepositChargeSuccess() catch 블록에도 failSettlement() 보상 추가, failSettlement() private 메서드 추출
- `SettlementRepository.java` — findByOrderCode() 쿼리 메서드 추가
- `Settlement.java` — setDepositHistoryCode() 추가

---

## Commit 2: fix: 예치금 환불 실패 이벤트 소비자 누락 수정

**설명**: DepositKafkaConsumer 가 발행하는 DepositRefundFailed 이벤트를 소비하는 핸들러가
존재하지 않아 환불 실패가 무시되고 있었음. PaymentKafkaHandler 에 핸들러 추가.

**파일**:

```
MOD  payments/src/main/java/io/devground/payments/payment/saga/PaymentKafkaHandler.java
```

**변경 상세**:

- `PaymentKafkaHandler.java` — DepositRefundFailed 핸들러 추가, 실패 로깅 및 TODO 주석

---

## Commit 3: feat: 예치금 동시성 제어를 위한 비관적 락 적용

**설명**: 예치금 충전/출금/환불/결제 시 read-then-write 로 인한 lost update 방지를 위해
PESSIMISTIC_WRITE 락 적용. 쓰기 경로에서 Deposit 조회 시 SELECT FOR UPDATE 사용.
userCode 컬럼에 unique 제약 추가로 row lock 범위 최적화.

**파일**:

```
MOD  payments/src/main/java/io/devground/payments/deposit/infrastructure/adapter/out/persistence/DepositJpaRepository.java
MOD  payments/src/main/java/io/devground/payments/deposit/application/port/out/DepositPersistencePort.java
MOD  payments/src/main/java/io/devground/payments/deposit/infrastructure/adapter/out/persistence/DepositPersistenceAdapter.java
MOD  payments/src/main/java/io/devground/payments/deposit/application/service/DepositEventApplication.java
MOD  payments/src/main/java/io/devground/payments/payment/service/PaymentServiceImpl.java
MOD  payments/src/main/java/io/devground/payments/deposit/infrastructure/model/persistence/DepositEntity.java
```

**변경 상세**:

- `DepositJpaRepository.java` — findByUserCodeForUpdate() 추가 (@Lock(PESSIMISTIC_WRITE) + @Query)
- `DepositPersistencePort.java` — getDepositByUserCodeForUpdate() 추가
- `DepositPersistenceAdapter.java` — getDepositByUserCodeForUpdate() 구현
- `DepositEventApplication.java` — charge/withdraw/refund/deleteDeposit 에서 getDepositByUserCodeForUpdate() 사용
- `PaymentServiceImpl.java` — process() 에서 getDepositByUserCodeForUpdate() 사용
- `DepositEntity.java` — userCode 컬럼에 unique = true 추가
