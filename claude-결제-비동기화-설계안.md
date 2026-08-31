# 결제 비동기화 설계안 — 주문 → 결제를 Kafka 로 분리

> **묻는 것:** 주문 접수와 결제 확정을 분리하고, 사용자에게 "결제 대기중 → 완료/실패" 를 밀어주는 구조로 바꿀 수 있는가?
> 관련 문서: [`claude-bench-스파이크테스트.md`](./claude-bench-스파이크테스트.md) · [`claude-kafka-test-결과.md`](./claude-kafka-test-결과.md)
> 기준 커밋: `6a6add3` (Kafka 결제 경로 제거) — **이 문서는 그 판단을 부분적으로 뒤집는다.** §1.3 참조.

---

## 0. 세 줄 요약

1. **처리량은 안 는다.** 예치금 차감 DB 작업의 총량이 그대로고 같은 MySQL 이 처리한다. 두 번 측정으로 확인됨(§1.1).
2. **접수 경로가 가벼워진다.** HTTP 스레드가 동기 Feign 결제를 기다리지 않는다. 스파이크 흡수 가설이 처음으로 검증 가능해진다.
3. **실패 모드가 좋아진다.** "돈만 빠지고 주문이 사라짐" → "주문이 PENDING 에 남음"(복구 가능). 이게 가장 강한 근거다.

---

## 1. 왜 바꾸는가

### 1.1 기대하면 안 되는 것 — 처리량

이미 두 번 기각됐다. 세 번째로 같은 기대를 하면 또 기각된다.

| 실험 | 결과 |
|---|---|
| 후처리 sync vs kafka (150 TPS × 60s, 각 15,299건) | 완결 **9,897 vs 9,903건** — 차이 없음 |
| Hikari 풀 10 → 20 (수렴 구간 각 12,000건) | 처리량 **209.4 → 233.0 TPS (+11.3%)** 뿐 |

풀 증설 측정에서 드러난 현재 병목은 커넥션이 아니다. 수렴 상태 컨테이너 CPU 가
**MySQL 53/50 · payments 46/45 · Kafka 44/40 으로 셋 다 할당량 초과**, commerce 만 49/65 로 여유였다.
결제를 Kafka 로 옮겨도 그 차감 작업은 **같은 payments 인스턴스가 같은 MySQL 에** 하므로 상한은 그대로다.
큐를 앞에 두든 뒤에 두든 병목이 정한다 — 스파이크 문서 §0 의 논리 그대로.

### 1.2 실제로 얻는 것 — 접수 경로 경량화

```
AS-IS  주문 INSERT → 동기 Feign 결제(비관적 락 + 차감 + 결제 저장) → 후처리 publish
TO-BE  주문 INSERT(PENDING) → 결제 커맨드 publish
```

스파이크 문서 §1 "반증 가능성" 에 이렇게 적혀 있다:

> Kafka 가 덜어내는 건 ③(DB 쓰기 2건 → publish 3건)뿐이다. ①②가 무거우면 스파이크에서도 양쪽 다 거절할 수 있다. **그 경우 가설은 기각된다.**

실제로 기각됐다(80~200 TPS 5개 강도 전부 차이 없음). **이 설계는 그 ② 를 제거한다.**
HTTP 스레드의 커넥션 점유 시간이 짧아지므로, 풀 증설 측정에서 본 "20/20 포화" 도 풀릴 여지가 있다.

### 1.3 가장 강한 근거 — 실패 모드가 뒤집힌다

현재 구조의 실측된 최악 시나리오:

| | AS-IS (동기) | TO-BE (비동기) |
|---|---|---|
| 실패 지점 | commerce 트랜잭션 롤백 (payments 는 이미 커밋) | 결제 커맨드 소비 실패 |
| 결과 | **주문 0건 / 예치금 500,000원 차감** | 주문 PENDING 잔류 (차감 없음 또는 환불 대상) |
| 복구 | 불가능 — 흔적이 없다 | 가능 — PENDING 이 곧 복구 대상 |

`createOrderByOne` 이 `@Transactional` 인데 결제는 Feign 으로 **별도 서비스 트랜잭션**이라,
payments 가 커밋된 뒤 commerce 만 롤백되면 돈이 조용히 사라진다.

TO-BE 는 주문을 PENDING 으로 **먼저 남기고** 결제한다. 실패해도 상태가 남으므로 재시도·보상이 가능하다.
→ **"결제는 정합성 때문에 동기여야 한다" 는 원래 전제가 사실과 반대다.** 지금 구조가 정합성이 더 나쁘다.

### 1.4 커밋 6a6add3 의 판단과 충돌하지 않는다

혼동하기 쉬운 지점이라 명시한다. **되돌리는 게 아니다.**

| | 6a6add3 에서 제거한 것 | 이 설계가 도입하는 것 |
|---|---|---|
| 경계 | payments **모듈 내부** (결제 ↔ 예치금 차감) | commerce ↔ payments **서비스 경계** |
| 결합도 이득 | 없음 (같은 모듈) | 있음 (다른 도메인·다른 프로세스) |
| 당시 결과 | 이중 차감(5,000원 상품에 10,000원), 보상 복잡도만 증가 | — |

예치금 차감은 **여전히 `PaymentServiceImpl.process()` 한 곳에서 하나의 트랜잭션으로** 처리한다.
바뀌는 건 그 메서드를 **누가 호출하느냐**(HTTP 스레드 → Kafka 컨슈머)뿐이다.

---

## 2. 상태 모델

`OrderStatus`(`commerce/.../order/domain/vo/OrderStatus.java`) 에 **`PAYMENT_FAILED` 추가**가 필요하다.
현재 값: `ALL, PENDING, PAID, START_DELIVERY, DELIVERED, CONFIRMED, CANCELLED`

```
                    ┌──────────────── PaymentCompleted ──→ PAID ──→ (후처리 커맨드 발행)
                    │
주문 INSERT ──→ PENDING ─── PaymentFailed(잔액부족) ──→ PAYMENT_FAILED   [종착]
                    │
                    └─── 타임아웃 스케줄러(5분) ──────→ CANCELLED         [보상 후 종착]
```

**전이 규칙 — 멱등해야 한다.**
- `PENDING → PAID` 만 허용. 이미 `PAID` 면 no-op (중복 이벤트 소비 대비)
- `PENDING → PAYMENT_FAILED` 만 허용. 이미 종착 상태면 no-op
- `CANCELLED` 로 간 주문에 뒤늦게 `PaymentCompleted` 가 오면 → **환불 보상 발행** (§6.4)

> ⚠️ `PENDING` 이 이미 "후처리 대기" 의미로 쓰이고 있다(`KafkaOrderPostProcessAdapter` 주석: *응답 시점에 주문은 아직 PENDING*).
> 이제 `PENDING` 이 **"결제 미확정"** 과 **"결제 완료·후처리 대기"** 두 가지를 뜻하게 되어 모호하다.
> → **`PAYMENT_PENDING` 을 별도로 두어 분리할 것.** 벤치 스크립트(`bench_dbstat`)와 `verify.sql` 도 같이 고쳐야 한다.

---

## 3. 메시지 계약

기존 설정에 **토픽 이름이 이미 남아 있다**(`commerce/src/main/resources/application.yml:98-105`) — 재사용한다.

### 3.1 결제 요청 (commerce → payments)

```
topic : payments-purchase-commands     (DLT: payments-purchase-commands.DLT)
key   : userCode                       ← §5 참조
```

```java
public record PaymentRequestedCommand(
    String orderCode,          // 멱등키를 겸한다 (§4)
    String userCode,
    long   amount,
    List<String> productCodes,
    Instant requestedAt        // 타임아웃 판정용
) {}
```

### 3.2 결제 결과 (payments → commerce)

```
topic : payments-purchase-events       (DLT: payments-purchase-events.DLT)
key   : userCode
```

```java
public record PaymentCompletedEvent(String orderCode, String userCode,
                                    String paymentCode, long amount) {}

public record PaymentFailedEvent(String orderCode, String userCode,
                                 long amount, PaymentFailReason reason) {}

public enum PaymentFailReason { INSUFFICIENT_BALANCE, DEPOSIT_NOT_FOUND, INTERNAL_ERROR }
```

> `reason` 을 문자열이 아니라 enum 으로 두는 이유: commerce 가 **보상이 필요한 실패**(INTERNAL_ERROR — 차감됐을 수 있음)와
> **보상이 불필요한 실패**(INSUFFICIENT_BALANCE — 차감 안 됨)를 구분해야 한다.

---

## 4. 컴포넌트 변경

기존 `OrderPostProcessPort` 전략 패턴을 **그대로 복제**한다. A/B 측정이 가능해야 하기 때문이다.

### 4.1 commerce

| 파일 | 변경 |
|---|---|
| `OrderPaymentPort` | 반환 타입 문제 — §4.2 참조 |
| **`KafkaPaymentAdapter`** (신규) | `@Qualifier("kafkaPaymentAdapter")`, 커맨드 발행 후 즉시 반환 |
| `OrderApplication` | `@Value("${order.payment.strategy:feign}")` 로 어댑터 선택. `handlePaymentResult` 를 동기 경로에서만 호출 |
| **`PaymentResultConsumer`** (신규) | `payments-purchase-events` 소비 → 주문 상태 전이 → 성공 시 후처리 커맨드 발행 |
| **`PendingOrderTimeoutScheduler`** (신규) | 고아 PENDING 회수 (§6.4) |
| `OrderStatus` | `PAYMENT_PENDING`, `PAYMENT_FAILED` 추가 |

> `order.payment.strategy` 프로퍼티는 `application-bench.yml:138` 에 **이미 있으나 프로덕션 배선이 없다**
> (Kafka 결제 어댑터가 삭제되면서 껍데기만 남음, 현재 `FullStackE2ETest:107` 에서만 참조).
> 이번에 실제 스위치로 되살린다.

### 4.2 `OrderPaymentPort` 의 반환 타입 문제 ← 설계상 가장 지저분한 지점

현재 시그니처는 **동기 결과를 전제**한다:

```java
PaymentResult processPayment(String userCode, String orderCode, long totalAmount, List<String> productCodes);
```

Kafka 어댑터는 돌려줄 결과가 없다. 세 가지 선택지:

| 안 | 방식 | 평가 |
|---|---|---|
| A | `PaymentResult.accepted()` 를 새로 만들어 반환 | 호출부가 `if (accepted) return;` 분기 — 간단하지만 **Port 가 거짓말을 한다** |
| B | Port 를 `OrderPaymentPort` / `OrderPaymentAsyncPort` 로 분리 | 정직하지만 `OrderApplication` 이 두 경로를 알아야 함 |
| C | 유스케이스 자체를 분리 (`createOrderSync` / `createOrderAsync`) | 가장 정직, 코드 중복 최다 |

**권장: B.** A 는 나중에 반드시 사고를 낸다(`accepted` 를 `success` 로 오해). C 는 A/B 측정용 임시 구조에 과하다.
`OrderApplication` 이 전략에 따라 분기하는 건 이미 `getPostProcessAdapter()` 에서 하고 있는 패턴이다.

### 4.3 payments

| 파일 | 변경 |
|---|---|
| **`PaymentCommandConsumer`** (신규) | `payments-purchase-commands` 소비 → `PaymentServiceImpl.process()` **재사용** → 결과 이벤트 발행 |
| `PaymentServiceImpl` | **변경 없음.** 트랜잭션·락·이력 전략을 그대로 둔다 |
| `Payment` 엔티티 | `orderCode` 에 **unique 제약 추가** (§5 멱등성) |

`process()` 를 손대지 않는 게 중요하다. 그 안의 비관적 락과 `DepositHistoryRecorder` 의 AFTER_COMMIT 처리는
`6a6add3` 에서 lock wait timeout 50초를 잡느라 어렵게 맞춘 것이다(응답 50.71초/500 → 0.09초/200).

---

## 5. 멱등성과 파티셔닝

### 5.1 멱등키 = `orderCode`

이미 실측된 문제다 — **유령 주문**: 클라이언트가 타임아웃 처리한 요청이 서버에서는 완주한다(sync 892건 / kafka 864건 / 총 12,299건).
사용자는 실패로 알고 재시도 → 이중 결제.

비동기가 되면 "대기중" 화면에서 새로고침·재시도할 여지가 **더 커진다.** 그래서 선택이 아니라 전제 조건이다.

```
payments : Payment.orderCode 에 UNIQUE 제약
           → 중복 커맨드는 DataIntegrityViolationException
           → 기존 Payment 를 조회해 같은 결과 이벤트를 재발행 (실패로 처리하면 안 됨)

commerce : 상태 전이가 멱등 (§2) — 이미 PAID 면 no-op
```

> `Payment.orderCode` 는 현재 제약이 없다(`@Column(nullable=false)` 뿐). DDL 변경이 필요하다.
> 예치금 `userCode` 에 unique 를 걸었던 `6386ab0` 과 같은 이유·같은 방식이다.

### 5.2 파티션 키 = `userCode` (`orderCode` 아님)

예치금 잔액은 **유저 단위 자원**이다. `orderCode` 로 키를 주면 같은 유저의 동시 결제가 3개 파티션으로 흩어져
비관적 락 경합이 파티션 수만큼 늘어난다. `userCode` 로 주면 유저별 직렬화가 공짜로 따라온다.

- 정확성 문제는 아니다 — `6386ab0` 의 `PESSIMISTIC_WRITE` 가 이미 lost update 를 막는다.
- **성능 문제**다. 락 대기가 줄어들면 커넥션 점유 시간도 줄어든다.
- 위험: 특정 유저에 몰리면 핫 파티션. 벤치는 200 유저 균등이라 무관하고, 실서비스에서도 한 사람이 동시에 여러 결제를 하는 일은 드물다.

---

## 6. 실패 처리

### 6.1 publish 시점 — 커밋 전에 발행하면 안 된다

이미 밟은 함정이다(스파이크 문서 §7.2): 컨슈머가 `ORDER_NOT_FOUND` 로 지수 백오프 5회 재시도에 빠진다.

```java
@Transactional
public void createOrderByOne(...) {
    orderPersistencePort.createSingleOrder(...);   // PAYMENT_PENDING
    // publish 를 여기서 하면 커밋 전에 컨슈머가 주문을 못 찾는다
}
```

| 안 | 방식 | 평가 |
|---|---|---|
| **AFTER_COMMIT** | `@TransactionalEventListener(phase = AFTER_COMMIT)` | 간단. 커밋 후 publish 실패 시 주문이 PENDING 에 갇힘 → §6.4 가 안전망 |
| Outbox | 커밋 트랜잭션에 outbox row 를 같이 쓰고 릴레이가 발행 | 유실 없음. 릴레이·폴링·정리 로직이 추가로 필요 |

**권장: AFTER_COMMIT 으로 시작.** 타임아웃 스케줄러가 있으면 유실이 "영구 손실" 이 아니라 "지연된 취소" 가 된다.
Outbox 는 그 지연조차 허용 못 할 때 올린다.

### 6.2 잔액 부족 (`INSUFFICIENT_BALANCE`)

차감이 일어나지 않았으므로 **보상 불필요**. 주문만 `PAYMENT_FAILED` 로 전이한다.

### 6.3 payments 예외 / 재시도 소진 → DLT

주문은 `PAYMENT_PENDING` 유지. DLT 적재를 알림으로 띄우고, 회수는 §6.4 에 맡긴다.
`KafkaErrorHandler` 가 이미 양쪽 모듈에 있으므로 토픽만 등록하면 된다.

### 6.4 고아 `PAYMENT_PENDING` 회수 스케줄러 ← 이 설계에서 새로 지는 가장 큰 부채

**차감은 됐는데 결과 이벤트가 유실된 경우**가 최악이다 — 돈은 나갔고 주문은 대기 중이다.

```
5분마다:
  PAYMENT_PENDING 이고 createdAt < now-5m 인 주문 조회
    → payments 에 결제 상태 조회 (orderCode 로)
       ├ Payment 있음(성공)  → 주문 PAID 로 전이 + 후처리 커맨드 발행   (이벤트 유실 복구)
       └ Payment 없음        → 주문 CANCELLED 로 전이                    (커맨드 유실)
```

> 환불 보상 경로는 이미 있다 — `DepositRefundFailed` 핸들러를 `acf8baf` 에서 추가했고,
> 정산 Saga 보상 체인(`bea8a72`)에서 같은 패턴을 만들어봤다. 재사용 가능하다.

**주의:** 조회 기반 회수는 payments 를 동기로 부른다. 스케줄러가 대량의 PENDING 을 한 번에 처리하면
결제 경로와 커넥션을 다툰다. → 배치 크기 제한(예: 100건/회) 필수.

---

## 7. 잔액 사전 조회 — 권장

비동기화의 UX 대가는 **"잔액 부족"이라는 흔한 실패를 늦게 안다**는 것이다. 이건 완화할 수 있다.

```
발행 전에 읽기 전용 잔액 조회 1건 (락 없음)
  ├ 명백히 부족 → 즉시 400 거절, 커맨드 발행 안 함
  └ 충분        → 커맨드 발행 (확정은 컨슈머가)
```

- **비용:** `SELECT` 1건, 락 없음. 접수 경로에 거의 부담이 없다.
- **이득:** 사용자 체감 실패의 대부분을 즉시 처리한다.
- **한계:** 이건 **확정이 아니라 힌트**다. 동시 결제 경합으로 통과했다가 비동기로 실패할 수 있고, 그게 정상이다.
  최종 판정은 `PaymentServiceImpl.process()` 의 비관적 락 구간이 한다. 사전 조회를 근거로 차감을 건너뛰면 안 된다.

---

## 8. 상태 전달 채널

| 안 | 장점 | 단점 |
|---|---|---|
| **폴링** `GET /order/{orderCode}/status` | 인프라 추가 없음, 스케일아웃 무관, 구현 반나절 | 요청 수 증가 (결제당 2~3건) |
| SSE | 서버 푸시, 단방향이라 단순 | 커넥션 유지 비용, 다중 인스턴스에서 라우팅 필요 |
| WebSocket | 양방향 | 지금 양방향이 필요 없음 — 과설계 |

**권장: 폴링으로 시작.** 결제 확정이 보통 수백 ms 이므로 1초 간격 2~3회면 끝난다.
그 짧은 구간에 커넥션 유지형 채널을 붙이는 건 비용 대비 이득이 없고,
게이트웨이·다중 인스턴스 환경에서 "어느 인스턴스가 그 사용자의 SSE 를 들고 있는가" 문제가 새로 생긴다.

**SSE 로 올릴 조건:** 확정 지연이 초 단위로 늘거나, 대기 화면 체류가 길어질 때. 그때는 지표가 근거를 준다.

> ⚠️ 폴링 엔드포인트가 **접수 경로와 같은 커넥션 풀을 쓴다.** 결제당 2~3건이면 총 요청 수가 3~4배가 된다.
> §1.1 에서 본 대로 지금 상한을 정하는 게 DB CPU 이므로, 이 조회는 **인덱스 조회 1건**으로 끝나야 한다
> (`Orders.orderCode` 인덱스 확인 필요).

---

## 9. 전환 전략

```yaml
order:
  payment:
    strategy: feign   # feign | kafka
```

기존 `order.postprocess.strategy` 와 **동일한 방식**이다. 두 어댑터를 모두 두고 재기동으로 전환하므로
같은 조건 A/B 측정이 가능하고, 문제가 생기면 되돌리기가 설정 한 줄이다.

**단계:**

1. `PAYMENT_PENDING` / `PAYMENT_FAILED` 상태 추가 + `Payment.orderCode` unique 제약
2. `PaymentCommandConsumer`(payments) + `PaymentResultConsumer`(commerce)
3. `KafkaPaymentAdapter` + Port 분리(§4.2 안 B) + 전략 스위치
4. 타임아웃 스케줄러 (§6.4) — **3번과 같이 나가야 한다.** 없이 켜면 고아 주문이 쌓인다
5. 잔액 사전 조회 (§7)
6. 폴링 엔드포인트 (§8)
7. A/B 측정 (§10)

---

## 10. 측정 계획 → **결과: [`claude-결제-비동기화-측정.md`](./claude-결제-비동기화-측정.md)**

> **실측 요약(2026-08-30):** 접수 p95 592 → 357ms(−39.7%), 접수 처리량 +83.0%.
> 완결 처리량은 147.2 → 136.9 TPS(**−7.0%**) 로 오히려 나빠졌고, 원인은 결제 병렬도가
> Tomcat 워커(실질 10)에서 Kafka 컨슈머 스레드(파티션 3개)로 떨어진 것이다.
> 아래 계획 중 **지속 부하 A/B 는 완료**, 스파이크 A/B 는 미실행.

이 구조에서는 **스파이크 흡수 A/B 를 다시 할 가치가 있다.** 그때 기각된 이유(§1.2)가 제거되기 때문이다.

**가설:** 접수 경로에서 동기 Feign 결제가 빠지므로, 스파이크 구간 거절률이 sync 대비 유의미하게 낮다.

**지표:**
- 스파이크 구간 거절률 (주 지표)
- 접수 p95 (표본이 크면 p99 도 — [`kafka-bench-metrics-rule`] 참조)
- Hikari `active` / `pending` — 20/20 포화가 풀리는가
- 응답 종료 직후 `PAYMENT_PENDING` 건수 — 큐에 쌓였다는 증거

### ⚠️ 회차 설계를 반드시 고칠 것 — 워밍업

풀 증설 측정에서 처리량이 **58 → 87 → 116 → 148 → 174 → 201 → 232 TPS 로 7회차(≈16,800건)까지 단조 증가**한 뒤 수렴했다.
pool=10 / pool=20 두 배포에서 궤적이 동일하게 재현됐다.

- 스크립트의 **워밍업 300건은 전혀 부족하다.** 콜드 1회차만 재면 실제 용량의 **1/4** 을 잰다.
- 기존 ladder/spike 회차의 절대 수치(80 TPS 에서 14.82% 타임아웃)는 이 때문에도 신뢰할 수 없다.
- 실제 수렴 용량은 **200 TPS 대**이지, 스파이크 문서가 가정한 "약 100 TPS" 가 아니다. → **스파이크 강도를 다시 잡아야 한다.**
- **A/B 는 양쪽 다 수렴 회차끼리** 비교할 것. 최소 7회 반복 후 3~4회를 표본으로 쓴다.

---

## 11. 열린 질문

1. **`PENDING` 의미 분리** — 기존 후처리 대기 `PENDING` 과 충돌한다(§2). 벤치 스크립트·`verify.sql`·대시보드를 같이 고쳐야 하는데, 측정 이력과의 연속성을 어떻게 유지할 것인가?
2. **결제 실패 시 재고/상품 상태** — 현재 후처리에서 상품 상태를 바꾼다. `PAYMENT_FAILED` 면 후처리를 아예 발행하지 않으므로 문제 없지만, "결제 대기 중에 다른 사람이 같은 상품을 산다" 는 경합이 새로 생긴다. 선점(예약) 이 필요한가?
3. **주문 목록 화면** — `PAYMENT_PENDING` 주문을 목록에 보여줄 것인가, 확정 전까지 숨길 것인가?
4. **폴링 종료 조건** — 클라이언트가 무한 폴링하지 않도록 최대 시도 횟수와 그 후 안내 문구가 필요하다.
