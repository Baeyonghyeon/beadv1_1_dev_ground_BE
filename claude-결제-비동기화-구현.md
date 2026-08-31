# 결제 비동기화 — 구현 요약

> 설계: [`claude-결제-비동기화-설계안.md`](./claude-결제-비동기화-설계안.md) · 테스트: [`claude-결제-비동기화-테스트.md`](./claude-결제-비동기화-테스트.md) · **측정: [`claude-결제-비동기화-측정.md`](./claude-결제-비동기화-측정.md)**
> 브랜치 `feat/async-payment-kafka` · 설계 §9 의 **1~7 단계 전부 완료** — 구현 · end-to-end 검증(§9) · A/B 측정(별도 문서).
>
> **측정 요약: 접수 p95 −39.7% / 접수 처리량 +83%, 완결 처리량 −7%(파티션 3개 기준).**

---

## 0. 한 줄 요약

주문 접수와 결제 확정을 분리했다. `order.payment.strategy` 한 줄로 **동기(feign) ↔ 비동기(kafka)** 를 바꿀 수 있고,
비동기일 때 주문은 `PAYMENT_PENDING` 으로 먼저 남은 뒤 결제 결과 이벤트로 확정된다.

```
AS-IS  주문 INSERT → 동기 Feign 결제(락+차감+이력) → 후처리 발행 → 응답
TO-BE  주문 INSERT(PAYMENT_PENDING) → 응답
       ↓ (커밋 후)
       payments-purchase-commands → payments: process() → payments-purchase-events
       ↓
       commerce: PAID | PAYMENT_FAILED 전이 → 후처리 발행
```

---

## 1. 신규 파일 (10개)

### core — 메시지 계약

| 파일 | 역할 |
|---|---|
| `commands/payment/PaymentRequestedCommand` | 결제 요청. `orderCode` 가 **멱등키를 겸한다** |
| `event/payment/OrderPaymentCompleted` | 결제 성공. `paymentCode`·`productCodes` 를 실어 후처리로 잇는다 |
| `event/payment/OrderPaymentFailed` | 결제 실패. `reason` 으로 보상 필요 여부를 전달 |
| `event/payment/PaymentFailReason` | `INSUFFICIENT_BALANCE` / `DEPOSIT_NOT_FOUND` / `INTERNAL_ERROR` |

> **`reason` 을 enum 으로 둔 이유:** commerce 가 "보상이 필요한 실패" 와 "불필요한 실패" 를 구분해야 한다.
> 잔액 부족은 차감이 없어 주문만 접으면 되지만, `INTERNAL_ERROR` 는 차감됐을 수 있어 회수 대상이다.
> 문자열 메시지로는 이 판정을 할 수 없다.

### commerce

| 파일 | 역할 |
|---|---|
| `port/out/payment/OrderPaymentAsyncPort` | 비동기 결제 요청 포트 — **반환값 없음** |
| `port/out/payment/OrderPaymentLookupPort` | 결제 존재 여부 조회 (회수 전용) |
| `adapter/out/payment/KafkaPaymentAdapter` | 스프링 이벤트만 발행 (실제 전송은 커밋 후) |
| `adapter/out/payment/PaymentRequestKafkaRelay` | `AFTER_COMMIT` 에 Kafka 전송 |
| `adapter/out/payment/FeignPaymentLookupAdapter` | 결제 조회 Feign 어댑터 |
| `adapter/in/kafka/PaymentResultConsumer` | 결제 결과 소비 → 상태 전이 |
| `adapter/in/scheduler/PendingPaymentReconciler` | 5분마다 고아 `PAYMENT_PENDING` 회수 |
| `port/out/payment/OrderDepositPort` | 잔액 사전 확인 포트 (기본 OFF) |
| `adapter/out/payment/DepositFeignClient` · `FeignDepositAdapter` | 잔액 조회 |
| `application/vo/OrderAcceptance` | 접수 결과 — 확정인가 대기인가 |
| `domain/vo/OrderPaymentStatus` | 폴링용 경량 조회 결과 |

---

## 2. 설계 판단 4가지 — 왜 이렇게 했는가

### ① Port 를 분리했다 (설계 §4.2 안 B)

`OrderPaymentPort.processPayment()` 는 `PaymentResult` 를 돌려주겠다고 약속한다. 비동기 경로에는 그 시점에 돌려줄 결과가 없다.

같은 인터페이스에 `PaymentResult.accepted()` 같은 값을 끼워 넣는 안(A)을 버린 이유는 하나다 —
**호출부가 그걸 "성공" 으로 오해할 여지가 생긴다.** 결제 코드에서 그 오해는 돈이 걸린 사고다.
그래서 반환값이 없는 `OrderPaymentAsyncPort` 를 따로 뒀다.

### ② publish 를 커밋 뒤로 미뤘다 (설계 §6.1)

`createOrderByOne` 은 `@Transactional` 이다. 그 안에서 Kafka 로 바로 보내면 **커밋 전에** 메시지가 나가고,
payments 가 즉시 소비해 아직 존재하지 않는 주문의 결제를 처리한다.

이건 이미 밟은 함정이다 — 스파이크 문서 §7.2 의 `ORDER_NOT_FOUND` 지수 백오프 5회가 같은 원인이었다.

```
KafkaPaymentAdapter        → 스프링 애플리케이션 이벤트만 발행
PaymentRequestKafkaRelay   → @TransactionalEventListener(AFTER_COMMIT) 에서 Kafka 전송
```

payments 의 `DepositHistoryRecorder` 가 쓰는 것과 같은 관용구다.
**대가:** 커밋 후 전송이 실패하면 주문이 `PAYMENT_PENDING` 에 갇힌다 → §2-④ 가 회수한다.
Outbox 를 쓰면 유실 자체가 없어지지만, 회수 장치가 있으면 "영구 손실" 이 아니라 "지연된 취소" 로 끝나므로 우선 이 방식으로 갔다.

### ③ 멱등성을 조건부 UPDATE 로 만들었다

Kafka 는 at-least-once 다. 중복 소비는 예외가 아니라 **정상 동작**이다.

```sql
UPDATE Orders SET orderStatus = 'PAID'
WHERE code = :orderCode AND orderStatus = 'PAYMENT_PENDING'   -- ← 이 조건이 멱등성의 전부
```

갱신 행 수가 0이면 이미 전이된 것이므로 **후처리도 건너뛴다.**
이 가드가 없으면 중복 이벤트마다 장바구니 정리·상품 상태 커맨드가 중복 발행된다.

기존 `paidByCode()` 는 조건 없이 덮어쓰므로 이 경로에서 쓰면 안 된다. 별도 메서드로 뒀다.

### ④ 파티션 키를 `userCode` 로 잡았다 (설계 §5.2)

예치금 잔액은 **유저 단위 자원**이다. `orderCode` 로 키를 주면 같은 유저의 동시 결제가 3개 파티션으로 흩어져
비관적 락(`6386ab0`) 경합이 파티션 수만큼 늘어난다. 정확성 문제가 아니라 **성능 문제**다.

---

## 3. 상태 모델

`OrderStatus` 에 두 값을 추가했다.

```
주문 INSERT ──→ PAYMENT_PENDING ─┬─ OrderPaymentCompleted ──→ PAID ──→ (후처리 발행)
                                 ├─ OrderPaymentFailed ─────→ PAYMENT_FAILED   [종착]
                                 └─ 회수 스케줄러(5분) ──────→ CANCELLED        [종착]
```

> ⚠️ **`PENDING` 과 `PAYMENT_PENDING` 은 다르다.**
> `PENDING` = 돈이 이미 빠졌고 후처리만 남음 (동기 경로의 생성 상태)
> `PAYMENT_PENDING` = 돈이 빠졌는지 아직 모름 (비동기 경로의 생성 상태)
> 이 둘을 섞으면 **회수 스케줄러가 결제 완료된 주문을 취소한다.** 테스트로 고정해 뒀다.

`PAYMENT_FAILED` 를 `CANCELLED` 와 구분한 이유: 취소는 "결제됐던 주문을 되돌린 것", 실패는 "애초에 결제가 성립하지 않은 것" 이다. 후자는 환불 보상 대상이 아니다.

---

## 4. 회수 스케줄러 — 이번 구현에서 새로 진 가장 큰 부채

commerce 의 DB 만 봐서는 두 경우를 구분할 수 없다. 둘 다 `PAYMENT_PENDING` 이다.

| | 실제 상황 | 올바른 처리 |
|---|---|---|
| 커맨드 유실 | 결제가 시작되지도 않음. **차감 없음** | 주문 취소 |
| 결과 이벤트 유실 | 결제는 끝남. **돈은 이미 나갔다** | `PAID` 로 복구 + 후처리 재개 |

그래서 payments 에 결제 기록이 있는지 물어본 뒤 판정한다.

```
5분마다:
  PAYMENT_PENDING & createdAt < now-5m 인 주문 최대 100건
    → GET /api/payments/order/{orderCode}
       ├ PAYMENT_COMPLETED  → markPaidIfAwaitingPayment + 후처리 발행
       └ 없음 / 미완료       → cancelIfAwaitingPayment
```

**두 가지를 의도적으로 지켰다.**

- **트랜잭션을 걸지 않는다.** 건별로 Feign 을 호출하므로, 트랜잭션을 열면 커넥션을 쥔 채 네트워크를 기다려 결제 경로와 커넥션을 다툰다. 상태 전이는 각 포트 메서드가 자체 트랜잭션으로 처리한다.
- **조회 실패와 "결제 없음" 을 구분한다.** 조회에 실패했는데 "없음" 으로 판정하면 **이미 돈이 빠진 주문을 취소**한다. 그래서 payments 는 결제가 없을 때 404 가 아니라 `200 + data:null` 을 주고, 어댑터는 통신 실패 시 예외를 던져 그 건을 다음 주기로 미룬다.

배치 크기는 100건으로 제한했다(설계 §6.4 의 경고).

---

## 5. payments 변경

| 파일 | 변경 |
|---|---|
| `PaymentKafkaHandler` | `PaymentRequestedCommand` 핸들러 추가 + 결과 이벤트 발행 |
| `Payment` | `orderCode` 에 **unique 제약** (멱등키) |
| `PaymentService(+Impl)` | `findByOrderCode()` 추가 |
| `PaymentController` | `GET /api/payments/order/{orderCode}` 추가 |

**`PaymentServiceImpl.process()` 는 건드리지 않았다.** 그 안의 비관적 락 순서와 `DepositHistoryRecorder` 의
AFTER_COMMIT 처리는 `6a6add3` 에서 lock wait timeout(50초)을 잡느라 어렵게 맞춘 것이다(응답 50.71초/500 → 0.09초/200).
바뀐 건 **호출자가 HTTP 워커 스레드에서 Kafka 컨슈머로 옮겨간 것**뿐이다.

### 실패 분류

| 상황 | 발행 | 이유 |
|---|---|---|
| 잔액 부족 (`IllegalStateException`) | `INSUFFICIENT_BALANCE` | 차감 없음 → 보상 불필요 |
| 예치금 없음 (`ServiceException`) | `DEPOSIT_NOT_FOUND` | 차감 없음 → 보상 불필요 |
| 중복 커맨드 (`DataIntegrityViolationException`) | **`Completed` 재발행** | 이미 성공 — 실패로 처리하면 정상 주문이 취소된다 |
| 그 외 예외 | **발행 안 함 (전파)** | 재시도 → DLT. 삼키면 "차감됐는데 실패로 확정" 이 생긴다 |

> ⚠️ **컨슈머를 새로 만들지 않고 `PaymentKafkaHandler` 에 핸들러를 추가했다.**
> `payments-purchase-commands` 를 이미 이 클래스가 구독하고 있어서다. 같은 토픽에 다른 `@KafkaListener`
> 클래스를 추가하면 같은 컨슈머 그룹 안에서 파티션이 나뉘어 서로 처리 못 하는 타입을 받게 된다.

---

## 6. 설정

```yaml
order:
  payment:
    strategy: ${ORDER_PAYMENT_STRATEGY:feign}   # feign | kafka
    timeout-seconds: 300          # 이 시간을 넘긴 PAYMENT_PENDING 은 회수 대상
    reconcile-batch-size: 100     # 회수 주기당 최대 건수
    reconcile-cron: "0 */5 * * * *"
```

**기본값은 `feign`(기존 동작)이다.** 비동기는 명시적으로 켜야 한다.
bench 프로파일은 `BENCH_PAYMENT_STRATEGY` 로 전환하고, `reconcile-cron: "-"` 으로 회수를 끈다 —
측정 중 회수가 주문 상태를 바꾸면 완결 시간 집계가 오염되기 때문이다.

토픽은 신규 생성이 아니라 **기존에 이름만 남아 있던 것을 되살렸다**(`payments-purchase-commands` / `payments-purchase-events`).
`OrderKafkaTopicConfig` 에 DLT 포함 `NewTopic` 빈이 이미 있었다.

---

## 7. 폴링 · 잔액 사전 확인

### 7.1 응답 계약 — 동기는 그대로, 비동기만 202

**문제:** 기존 주문 생성은 `204 No Content` 로 응답한다. 204 는 정의상 **본문을 실을 수 없어** 주문 코드를 돌려줄 방법이 없다. 주문 코드가 없으면 클라이언트가 폴링할 대상을 모른다.

```
동기(feign)   → 204, 본문 없음          ← 기존과 한 바이트도 다르지 않다
비동기(kafka) → 202 + {orderCode, orderStatus, pollUrl}
```

동기 경로를 건드리지 않은 건 의도적이다. 기존 부하 측정과의 비교 가능성이 거기 걸려 있다.

### 7.2 폴링 엔드포인트

```
GET /api/commerce/order/{orderCode}/payment-status
→ { orderCode, orderStatus, awaitingPayment, message }
```

`awaitingPayment` 가 false 가 되면 클라이언트는 폴링을 멈춘다. **본인 주문만 조회된다** — 남의 주문은 존재 여부도 알려주지 않으려고 권한 오류가 아니라 `ORDER_NOT_FOUND` 로 응답한다.

조회는 **인덱스 1행, 조인 없음**(`findPaymentStatusByCode` 프로젝션)이다. 폴링은 결제 1건당 여러 번 호출되므로 여기가 무거우면 비동기화로 덜어낸 부하를 폴링이 도로 만든다.

### 7.3 잔액 사전 확인 — 기본 OFF

```yaml
order.payment.pre-check-balance: false   # 기본값
```

**기본값을 false 로 둔 이유를 분명히 해둔다.** 이 확인은 payments 로의 **원격 호출을 접수 경로에 하나 되돌려 놓는다.** 비동기화의 요점이 접수 경로 경량화인데 그걸 일부 무르는 셈이라, 측정을 오염시키지 않도록 꺼두고 UX 요구가 분명할 때만 켠다.

두 가지를 지켰다:
- **확정이 아니라 힌트다.** 락 없이 읽으므로 경합에서는 통과했다가 비동기로 실패할 수 있다. 최종 판정은 여전히 `process()` 의 비관적 락 구간이 한다.
- **조회 실패는 주문을 막지 않는다.** 부가 확인이 payments 장애를 주문 전체로 전파하면 안 된다.

---

## 8. 스키마 마이그레이션 — ⚠️ 앱 기동 전에 반드시 실행

```bash
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/migrate-async-payment.sql
```

**이 프로젝트는 Flyway/Liquibase 가 없고 `ddl-auto: update` 로 스키마를 맞춘다. 그런데 update 는 기존 컬럼 정의를 바꾸지 못한다.** end-to-end 검증에서 실제로 두 번 걸렸다:

| 증상 | 원인 | 조치 |
|---|---|---|
| `Data truncated for column 'orderStatus'` → 주문 INSERT 500 | MySQL ENUM 에 `PAYMENT_PENDING`/`PAYMENT_FAILED` 를 추가하지 못함 | `ALTER TABLE Orders MODIFY COLUMN orderStatus ENUM(...)` |
| 멱등키가 동작하지 않음 (조용히) | 기존 컬럼에 unique 제약을 붙이지 못함 | `ALTER TABLE Payment ADD UNIQUE INDEX uk_payment_order_code` |

두 번째가 특히 위험하다 — **에러 없이 그냥 제약이 없는 상태로 돌아간다.** 중복 결제 방어가 사라진 줄 모르고 운영하게 된다.

---

## 9. end-to-end 검증 결과 (bench 환경 실측)

### 성공 경로

```
POST /api/commerce/order/PROD-0   → HTTP 202, 178ms
                                    {orderCode, PAYMENT_PENDING, pollUrl}
폴링 100ms 간격 × 8회              → PAYMENT_PENDING ×7 → PAID
```

| 확인 항목 | 결과 |
|---|---|
| 주문 상태 | `PAID` |
| 결제 레코드 | 생성됨, 금액 5,000 일치 |
| 예치금 | 1,000,000,000 → **999,995,000** (정확히 5,000 차감) |
| 예치금 이력 | 1건 |
| 장바구니 정리(후처리) | 완료 (남은 항목 0) |
| DLT 4개 토픽 | **전부 0** |

### 실패 경로 (잔액 부족)

```
잔액 100원 사용자 → POST → HTTP 202 (접수는 성공)
폴링 1회          → PAYMENT_FAILED "결제에 실패했습니다."
```

**잔액 그대로 100원, 결제 레코드 0건** — 차감 없이 실패했다. 보상이 필요 없는 실패로 올바르게 분류됐다.

### 잔액 사전 확인 ON

```
잔액 100원 사용자 → POST → HTTP 400 "잔액이 부족합니다." (147ms)
주문 레코드 증가 없음 — 대기 화면까지 가지 않고 즉시 거절
```

### 동기 경로 회귀

```
BENCH_PAYMENT_STRATEGY=feign → HTTP 204, 본문 0 bytes → PAID
```

기존 응답 계약이 그대로다. DLT 0, 컨슈머 lag 0.

---

## 10. 미구현 — 이 브랜치에 없는 것

| 설계 단계 | 상태 | 비고 |
|---|---|---|
| §10 스파이크 A/B | **미실행** | 지속 부하 A/B 는 완료([측정 문서](./claude-결제-비동기화-측정.md)). 스파이크는 강도를 다시 잡아야 함 |
| 프런트엔드 | **범위 밖** | 서버는 202 + `pollUrl` 까지 제공한다. 폴링 루프와 대기 화면은 클라이언트 몫 |

---

## 11. 알려진 이슈

1. **중복 `PAID` 쓰기** — `applyPaymentSuccess` 가 `PAID` 로 전이한 뒤, 후처리 커맨드 `CompleteOrderCommand` 를 소비한 `OrderCommandConsumer` 가 다시 `paidByCode()` 로 `PAID` 를 쓴다. 기존 후처리 계약을 건드리지 않으려고 그대로 뒀다(A/B 비교 가능성 유지). 주문당 UPDATE 1회가 낭비되므로 측정 전에 정리할 여지가 있다.

2. **`Payment.orderCode` unique 제약과 기존 데이터** — `ddl-auto: update` 가 인덱스를 추가하는데, 기존에 중복 `orderCode` 가 있으면 실패한다. 배포 전 확인:
   ```sql
   SELECT orderCode FROM Payment GROUP BY orderCode HAVING COUNT(*) > 1;
   ```

3. **`PENDING` 의미 중복** — 설계 §11-① 의 열린 질문 그대로다. 벤치 스크립트(`bench_dbstat`)와 `verify.sql` 이 `PENDING` 을 "후처리 대기" 로 세는데, 비동기 경로에서는 `PAYMENT_PENDING` 을 봐야 한다. **측정 전에 스크립트를 고쳐야 한다.**

4. **결제 대기 중 상품 경합** — 설계 §11-② 미해결. "결제 대기 중에 다른 사람이 같은 상품을 사는" 경합이 새로 생긴다. 선점(예약) 여부는 결정되지 않았다.

5. **order 응용 계층 예외가 500 으로 나간다 (기존 결함)** — commerce 의 `GlobalExceptionHandler` 는 `io.devground.core.model.exception.ServiceException` 만 처리한다. 그런데 `order.application.exception.ServiceException` 은 그것을 상속하지 않아 **핸들러에 걸리지 않고 500 이 된다.** end-to-end 검증에서 잔액 부족이 500 으로 나가는 걸 보고 발견했다.
   - 이번엔 **내 코드 안에서만** 우회했다 — 사전 확인은 core 의 `ErrorCode.INSUFFICIENT_BALANCE` 를 던진다(→ 400 확인).
   - **근본 수정은 하지 않았다.** 핸들러에 분기를 추가하면 order 모듈의 모든 에러 응답이 500 에서 4xx 로 바뀌는데, 그건 이 브랜치의 범위를 넘고 **기존 측정 결과의 의미까지 바뀐다**(장애 주입 실험의 "sync: HTTP 500 × 100"). 별도 판단이 필요하다.

6. **`Payment.paymentStatus` 가 ordinal 로 저장된다** — `@Enumerated(EnumType.STRING)` 이 없어 DB 에 정수로 들어간다(실측: `PAYMENT_COMPLETED` → `1`). 기존 결함이지만 이제 **회수 스케줄러가 이 값으로 "취소 vs 복구" 를 판정**하므로 위험도가 올라갔다. enum 순서를 바꾸면 기존 행이 다른 상태로 읽히고, 그 오독은 결제된 주문을 취소한다.
