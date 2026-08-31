# 주문·결제 오케스트레이션 사가 설계안

> 현재 주문·결제는 **코레오그래피**(이벤트만 주고받음)다. 이를 **오케스트레이션**으로 바꾼다.
> 정산(`SETTLEMENT_DEPOSIT_CHARGE`)·상품이미지(`PRODUCT_IMAGE_*`)와 같은 방식으로 맞춘다.

---

## 1. 왜 바꾸나 — 지금의 문제

| 문제 | 현재 |
|---|---|
| 진행 상황이 흩어짐 | `Orders.orderStatus` + `Payment` 테이블 + 로그를 대조해야 어디까지 갔는지 앎 |
| 실패 책임자가 코드에 안 드러남 | `DepositRefundFailed` 핸들러가 `// TODO: 관리자 알림` 으로 비어 있음 |
| 회수 대상 탐색이 비쌈 | `findTimedOutAwaitingPayment` 가 **Orders 전체**를 훑음 |
| 규칙이 컨슈머마다 흩어짐 | 멱등·순서·보상 판정이 각 핸들러에 중복 |

정산·이미지는 `Saga` 테이블 한 곳만 보면 되는데 **결제만 안 된다.** 이 비대칭을 없앤다.

---

## 2. 핵심 설계 판단 — 예치금 차감은 별도 단계가 아니다

**사가 단계의 경계 = 로컬 트랜잭션 경계.** 업무 개념 단위가 아니다.

```java
// PaymentServiceImpl.process() — 하나의 @Transactional
deposit.withdraw(amount);
depositCommandPort.saveDeposit(deposit);   // 예치금 차감
paymentRepository.save(payment);           // 결제 저장
```

차감과 결제 저장은 **한 커밋**이라 하나만 성공할 수 없다. 따라서 **한 단계**다.

> ⚠️ 이걸 두 단계로 쪼개면 *"돈은 빠졌는데 결제 기록이 없음"* 이라는,
> 지금은 물리적으로 불가능한 상태를 새로 만든다. **없던 장애 모드를 만드는 퇴보다.**

단, **보상 대상인 것은 맞다.** 이 단계가 성공한 뒤 사가가 실패하면 환불이 보상이다 (§5).

---

## 3. 사가 정의

### 3.1 SagaType

```java
ORDER_PAYMENT   // referenceCode = orderCode
```

### 3.2 단계

```
INIT
  └ 주문 PAYMENT_PENDING 생성 + 사가 시작            [commerce · 한 트랜잭션]
PAYMENT_COMMAND_SENT
  └ PaymentRequestedCommand 발행 완료                [commerce]
PAYMENT_SUCCEEDED
  └ OrderPaymentCompleted 수신                       [payments → commerce]
COMPLETE
  └ 주문 PAID 전이 + 후처리 커맨드 발행              [commerce · 한 트랜잭션]

─── 실패 경로 ───
FAILED         결제 거절(잔액부족·예치금없음). 차감 없음 → 보상 불필요
COMPENSATING   결과 불명. 차감 여부 판정 중
COMPENSATED    환불 완료 또는 "미차감" 확인 완료
```

### 3.3 단계 ↔ 트랜잭션 ↔ 소유 서비스

| 사가 단계 | 로컬 트랜잭션 | 소유 |
|---|---|---|
| ① 주문 생성 | `Orders` INSERT + `OrderPaymentSaga` INSERT | commerce |
| ② **결제 실행 (예치금 차감 포함)** | `DepositEntity` UPDATE + `Payment` INSERT | payments |
| ③ 주문 확정 | `Orders` UPDATE + `OrderPaymentSaga` UPDATE | commerce |

**예치금 차감은 ②의 내용물이다.** 별도 행이 필요 없다.

---

## 4. 오케스트레이터 위치

**commerce 에 둔다.** 사가를 시작하는 쪽이 오케스트레이터다.

```
commerce/.../order/application/saga/
    OrderPaymentSagaOrchestrator
commerce/.../order/infrastructure/saga/
    entity/OrderPaymentSaga · repository · service/OrderPaymentSagaService
```

### 왜 core 로 올리지 않나

기존 코드가 이미 **모듈마다 사가 기계를 각자 소유**한다:

- `payments/.../common/saga/` — 정산용
- `product/.../infrastructure/saga/` — 상품이미지용

core 로 올려 공유하면 두 서비스가 **같은 테이블을 쓰게 되어** 논리적 분리가 깨진다. 기존 패턴을 따른다.

### ⚠️ 테이블명을 반드시 명시할 것

`payments`·`product` 의 `Saga` 엔티티는 둘 다 `@Table` 에 이름이 없어 테이블명이 `Saga` 다.
현재 세 모듈이 **같은 DB(`dbay_db`)** 를 쓰므로 이미 한 테이블을 공유하고 있다.

```java
@Table(name = "OrderPaymentSaga", ...)   // 필수
```

---

## 5. 보상 설계

### 5.1 보상이 필요한 경우는 하나뿐

| 상황 | 차감됐나 | 처리 |
|---|---|---|
| `INSUFFICIENT_BALANCE` · `DEPOSIT_NOT_FOUND` | 아니오 (롤백) | `FAILED` — 주문만 접음 |
| `INTERNAL_ERROR` | **모름** | `COMPENSATING` → 판정 |
| 응답 없음 (타임아웃·DLT) | **모름** | `COMPENSATING` → 판정 |
| ③ 주문 확정 실패 | 예 | 조건부 UPDATE 재시도로 해결. 사실상 발생 안 함 |

### 5.2 판정 절차 (회수 스케줄러)

```
COMPENSATING 사가 발견
  └ payments 에 GET /api/payments/order/{orderCode}
      ├ 결제 있음  → 주문 PAID 복구 → COMPLETE
      └ 결제 없음  → 주문 PAYMENT_FAILED → COMPENSATED
```

차감됐는데 주문을 살릴 수 없는 경우에만 `DepositRefundCommand` 를 발행한다.
**환불 실패(`DepositRefundFailed`)는 사가를 `FAILED` 로 남긴다** — 지금처럼 로그만 찍고 사라지지 않는다.

---

## 6. 회수 스케줄러가 좋아진다

```java
// 현재 — Orders 전체 스캔
findTimedOutAwaitingPayment(...)

// 변경 — 미결 사가만
findByStatusAndStartedAtBefore(IN_PROCESS, now.minusMinutes(5))
```

주문은 계속 쌓이지만 **미결 사가는 수십 건 수준**이라 스캔 대상이 작다.

---

## 7. 비용 — 측정된 병목 위에 얹는다

주문당 DB 쓰기가 늘어난다. 이 경로가 바로 `Hikari 10` 병목으로 측정된 경로다.

| 방식 | 추가 쓰기/주문 | 비고 |
|---|---:|---|
| 전 단계 기록 | 4회 | 정산·이미지와 동일. 가장 비쌈 |
| **시작·종료만 기록** | **2회** | 중간 단계는 주문 상태로 알 수 있음 |
| 시작·종료 + **성공 시 삭제** | 2회 + DELETE | 테이블에 미결 건만 남음 |

**권장: 시작·종료만 기록.** `PAYMENT_COMMAND_SENT` / `PAYMENT_SUCCEEDED` 는
`Orders.orderStatus` 로 이미 알 수 있어 중복이다.

> ① 주문 INSERT 와 사가 INSERT 는 **같은 트랜잭션**에 넣는다.
> 나누면 "주문은 있는데 사가가 없는" 고아 주문이 생긴다.

---

## 8. 이 변경이 기존 측정치에 미치는 영향

`claude-결제-비동기화-측정.md` 의 수치는 **사가 테이블 없이** 잰 값이다.

| 지표 | 영향 |
|---|---|
| 접수 p95 357ms | **나빠질 수 있음** — 접수 트랜잭션에 INSERT 1회 추가 |
| 스파이크 흡수 | 나빠질 수 있음 — 같은 이유 |
| 장애 격리 생존율 100% | **영향 없음** — 브로커 보관 여부와 무관 |
| 완결 처리량 | 소폭 하락 |

**재측정 없이 기존 수치를 그대로 인용하면 안 된다.**

---

## 9. 구현 순서

1. `OrderPaymentSaga` 엔티티·리포지토리·서비스 (commerce, 테이블명 명시)
2. `SagaType.ORDER_PAYMENT` · `SagaStep` 추가
3. `OrderPaymentSagaOrchestrator` — 시작/성공/실패/보상 진입점
4. `OrderApplication` 이 오케스트레이터를 통해 결제 커맨드 발행
5. `PaymentResultConsumer` → 오케스트레이터 호출로 변경
6. 회수 스케줄러를 사가 기준으로 전환
7. 단위 테스트 (오케스트레이터 상태 전이 · 보상 판정)
8. **A/B 재측정** — 사가 추가 전후 접수 p95

---

## 10. 범위 밖

| 항목 | 이유 |
|---|---|
| 주문완료·장바구니 비우기 | 실패해도 무해. 보상 대상이 아닌 걸 사가로 관리하면 상태만 늘어남 |
| 예치금 차감을 별도 단계로 분리 | §2 — 없던 장애 모드를 만듦 |
| 토스 결제 경로(`pay()`) | 이번 주문 경로를 타지 않음 |
| `payments`·`product` 의 `Saga` 테이블명 충돌 | 기존 문제. 별도 과제 |
