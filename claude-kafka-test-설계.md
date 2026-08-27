# Kafka 도입 근거 증명 — 결제 시나리오 부하 테스트 설계

> 작성일: 2026-08-26
> 대상: 로컬 환경(macOS + Docker), **결제(주문 생성 → 결제 → 후처리)** 시나리오 한정
> 제외: 인증/인가, 검색(Elasticsearch/AI), 정산 배치, Toss 외부 결제
> 관련 문서: [`deepseek-kafka-ab-test.md`](./deepseek-kafka-ab-test.md) (예치금 단위 A/B — 이 문서는 그것을 **주문 전체 플로우**로 확장한 버전)

---

## 0. 이 문서의 목적

이력서에 쓸 한 문장을 **측정된 수치로** 뒷받침하는 것이 목표다.

> "순간적으로 트래픽이 몰리는 결제 구간에서, 동기 처리 대비 사용자 응답 시간 p99를 XXms → XXms로 낮추고
> 요청 거절률 XX% → 0%를 달성. 대신 최종 일관성 지연 p99 XXs를 감수하는 트레이드오프를 수치로 검증."

핵심은 "Kafka가 빠르다"가 아니라 **"무엇을 얻고 무엇을 잃는지 둘 다 측정했다"** 이다.
후자를 같이 제시해야 면접에서 설득력이 생긴다.

### 증명할 명제 4가지

| # | 명제 | 측정으로 보이는 모습 |
|---|------|---------------------|
| **P1** | **응답 분리** — 사용자는 무거운 후처리를 기다리지 않는다 | 동일 부하에서 `http_req_duration` p95/p99가 Arm별로 유의미하게 갈림 |
| **P2** | **버퍼링(유실 방지)** — 스레드풀/커넥션풀이 마르는 구간에서도 요청이 죽지 않는다 | 동기 Arm: 5xx·타임아웃·k6 `dropped_iterations` 발생 / Kafka Arm: 거절 0, lag만 증가 |
| **P3** | **평탄한 지연** — 뒷단이 밀려도 앞단 지연이 튀지 않는다 | 버스트 구간 p99 그래프가 동기는 급등, Kafka는 평탄 |
| **P4** | **트레이드오프의 정량화** — 대가는 최종 일관성 지연이다 | 주문 `createdAt → PAID updatedAt` 완결 시간 p50/p95/p99를 Arm별로 제시 |

### 기존 측정치와의 관계

이미 확보된 수치가 있다.

> **API 응답 시간 380ms → 302ms (약 20% 개선)** — 모든 작업을 동기 처리한 경우 대비

이 값은 이번 설계의 **Arm A vs Arm B** 를, 그것도 **부하가 거의 없는 상태의 평균**으로 잰 것에 해당한다.
버리지 말고 **두 가지로 활용한다.**

**활용 1 — 셋업 신뢰성 검증 (sanity check)**
새 설계의 `baseline 20 TPS` 구간에서 Arm A와 Arm B의 차이가 **대략 78ms 근처로 재현되는지** 본다.
재현되면 새 측정 환경이 기존 환경과 동등하다는 뜻이고, 크게 벗어나면 셋업(스텁·프로파일·DB)에 문제가 있다는 신호다.
**즉 380/302 는 이번 실험의 기준점(reference point) 역할을 한다.**

**활용 2 — 더 강한 문장으로 교체**
`380 → 302 (20%)` 는 면접에서 다음 반박을 받는다.

- "평균 20%면 사용자가 체감할까?" — 평균은 **꼬리(p95/p99)를 감춘다.** 100명 중 1명이 3초를 기다려도 평균은 조용하다
- "부하 없는 상태의 20%가 무슨 의미인가?" — 아키텍처 선택의 가치는 **한가할 때가 아니라 몰릴 때** 드러난다
- "그 정도면 Kafka 안 써도 되지 않나?" — ← 이 반박을 못 막으면 Kafka 도입 근거 자체가 무너진다

이번 실험은 같은 비교를 **① 평균 → p95/p99, ② 저부하 → 버스트, ③ 응답 시간 단일 지표 → 응답+거절률+완결 시간** 으로 확장한다.
버스트 구간에서는 두 Arm의 차이가 **선형이 아니라 비선형으로 벌어질 것**으로 예상되며 (§5),
그렇게 나오면 "20% 개선"보다 훨씬 방어하기 쉬운 문장이 된다.

| | 기존 | 이번 설계 |
|---|---|---|
| 지표 | 평균 응답 시간 | p50/p95/p99 + 거절률 + 완결 시간 |
| 부하 | 저부하(사실상 단일 요청) | baseline 20 TPS → burst [T] TPS |
| 비교군 | 동기 vs 현재 구현 (2개) | A / A′ / B / C (4개) |
| 대가 측정 | 없음 | 최종 일관성 지연 p99 |
| 결론 강도 | "20% 빨라짐" | "포화 구간에서 거절 0% vs [X]%" |

---

## 1. 현재 코드 지도 (결제 시나리오)

### 1.1 진입점

```
POST /api/commerce/order/{productCode}     단건
POST /api/commerce/order                   다건 (CartProductsRequest)
헤더: X-CODE = userCode          ← 인증 없음, 헤더만으로 식별 (테스트에 유리)
```

- `OrderApiController` — `commerce/.../order/infrastructure/adapter/in/web/OrderApiController.java:27`
- `OrderApplication.createOrderByOne` — `commerce/.../order/application/service/OrderApplication.java:68`

### 1.2 이미 존재하는 전략 스위치

`OrderApplication:63` 에서 `order.payment.strategy` 값으로 결제 어댑터를 고른다.

```java
private OrderPaymentPort getPaymentAdapter() {
    return "kafka".equals(paymentStrategy) ? kafkaPaymentAdapter : feignPaymentAdapter;
}
```

| 값 | 구현체 | 파일 |
|----|--------|------|
| `feign` (기본) | `FeignPaymentAdapter` | `commerce/.../adapter/out/payment/FeignPaymentAdapter.java` |
| `kafka` | `KafkaPaymentAdapter` | `commerce/.../adapter/out/payment/KafkaPaymentAdapter.java` |

**→ A/B 테스트의 절반은 이미 코드에 들어가 있다.** 남은 건 "완전 동기" Arm 하나 추가 + 계측이다.

### 1.3 경로별 실제 흐름

#### (a) `strategy=feign` — 결제는 동기, 후처리는 Kafka

```
k6 ─POST─▶ commerce OrderApplication (@Transactional 시작)
             ├ user Feign 조회        ← 현재 하드코딩 URL, 스텁 필요
             ├ product Feign 조회     ← 동일
             ├ Orders INSERT (PENDING)
             ├ FeignPaymentAdapter ──HTTP──▶ payments POST /api/payments/process
             │                                  └ PaymentServiceImpl.process (@Transactional)
             │                                       ├ SELECT ... FOR UPDATE (Deposit)   :95
             │                                       ├ 잔액 검증 / withdraw               :104
             │                                       ├ Payment INSERT
             │                                       └ DepositHistory (REQUIRES_NEW)
             ├ Kafka send × 3 (CompleteOrder / DeleteCartItems / ProductSold)
             └ (@Transactional 커밋)
          ◀─ 204 응답
                 ⋮ 백그라운드
                 OrderCommandConsumer ─▶ paidOrder() → Orders = PAID
                 CartCommandConsumer  ─▶ 장바구니 항목 삭제
```

- 후속 이벤트 발행부: `OrderApplication:169` `handlePaymentResult()` (`"feign".equals` 가드 안)

#### (b) `strategy=kafka` — 결제까지 비동기 Saga

```
k6 ─POST─▶ commerce OrderApplication (@Transactional)
             ├ user/product Feign 조회
             ├ Orders INSERT (PENDING)
             ├ KafkaPaymentAdapter.send(payments-purchase-commands, PaymentCreateCommand)
             └ 즉시 PaymentResult.success 반환 (실제 결제는 아직 시작도 안 함)
          ◀─ 204 응답
                 ⋮
                 payments PaymentKafkaHandler:60 ─▶ paymentService.process (예치금 차감 + Payment 저장)
                     └ produce PaymentCreatedEvent → payments-purchase-events
                 commerce OrderSaga:41 ─▶ publishPaymentSuccessToDeposit
                     └ produce WithdrawDeposit → deposits-purchase-commands
                 payments DepositKafkaConsumer.handleWithdrawCommand ─▶ 예치금 차감 (또!)
                     └ produce DepositWithdrawnSuccess → deposits-purchase-events
                 commerce OrderSaga:61 ─▶ CompletePayment / CompleteOrder / DeleteCart / ProductSold 발행
                 각 consumer ─▶ 최종 상태 반영
```

#### (c) 토픽 맵 (결제 시나리오에서 실제로 쓰이는 것만)

| 토픽 | producer | consumer |
|------|----------|----------|
| `payments-purchase-commands` | commerce (`KafkaPaymentAdapter`, `OrderKafkaEventPublisherAdapter`) | payments `PaymentKafkaHandler` |
| `payments-purchase-events` | payments `PaymentKafkaHandler` | commerce `OrderSaga` |
| `deposits-purchase-commands` | commerce `OrderKafkaEventPublisherAdapter` | payments `DepositKafkaConsumer` |
| `deposits-purchase-events` | payments `DepositKafkaConsumer` | commerce `OrderSaga` |
| `orders-purchase-commands` | commerce | commerce `OrderCommandConsumer` |
| `carts-purchase-commands` | commerce | commerce `CartCommandConsumer` |
| `products-purchase-command` | commerce | product 모듈 (**테스트에서는 소비자 없음 → 무시**) |

> `custom.kafka.config.topic-partitions: 1` 이므로 현재 모든 토픽이 **파티션 1개 = consumer 병렬성 1**.
> 이 값이 실험 결과를 크게 좌우한다 (§4.6 참조).

---

## 2. 실험 전에 반드시 정리할 것 (선결 과제)

이걸 안 고치면 측정값이 "설계의 성능"이 아니라 "버그의 성능"이 된다.

### P0-1 · `strategy=kafka` 경로의 예치금 이중 차감 🔴

`PaymentKafkaHandler:65` 의 `paymentService.process()` 가 **이미 예치금을 차감**한다
(`PaymentServiceImpl:104` `deposit.withdraw()`).
그런데 그 뒤 `OrderSaga:41` 이 `WithdrawDeposit` 을 다시 발행해서
`DepositKafkaConsumer.handleWithdrawCommand` 가 **한 번 더 차감**한다.

> Feign 리팩터링으로 `process()` 안에 예치금 차감이 들어오면서 생긴 회귀로 보인다.
> Kafka Arm 을 이 상태로 측정하면 잔액 정합성 검증이 전부 실패하고,
> "Kafka는 정합성이 깨진다"는 잘못된 결론이 나온다.

**실측 확인 (2026-08-26):** `order.payment.strategy=kafka` 로 5,000원짜리 상품 1건을 주문한 결과

```
차감액: 10,000원          ← 상품가의 2배
DepositHistoryEntity: PAYMENT_INTERNAL 2건
```

예측대로 이중 차감이 재현됐다. 이 상태로 Arm C 를 측정하면 잔액 정합성 검증이 전부 실패한다.

**선택지 (둘 중 하나로 통일):**

- **(권장) 차감 지점을 `DepositKafkaConsumer` 한 곳으로** — `PaymentKafkaHandler` 는 `Payment` 레코드만 PENDING 으로 남기고, `DepositWithdrawnSuccess` 수신 시 COMPLETED 로 전이. Saga 단계가 명확해지고 보상 트랜잭션도 자연스럽다.
- (대안) `OrderSaga` 의 `PaymentCreatedEvent` 핸들러에서 `WithdrawDeposit` 발행을 제거하고 바로 완료 이벤트로 점프. 단계는 줄지만 Saga 로서의 설명력이 약해진다.

### P0-2 · `@Transactional` 안에서의 Feign 동기 호출 🟡

`OrderApplication:68` 은 `@Transactional` 이고, 그 안에서 `FeignPaymentAdapter` 가 HTTP 호출을 한다.
즉 **원격 호출 왕복 내내 HikariCP 커넥션을 붙잡고 있다.**

이건 버그이자 동시에 **이번 실험의 핵심 소재**다. 두 가지로 쓸 수 있다:

1. **그대로 두고 측정** → "동기 결합이 커넥션풀을 어떻게 마르게 하는지" 를 그래프로 증명 (`hikaricp_connections_pending` 급등)
2. 개선판(트랜잭션 밖으로 결제 호출 분리)도 측정 → **Arm A′** 로 추가하면 "Kafka 없이도 개선 가능한 부분 / Kafka여야만 되는 부분"을 구분할 수 있어 면접 답변이 훨씬 단단해진다

**권장: 1번을 기본 Arm으로, 2번을 보조 Arm으로 둘 다 측정.**

### P0-3 · user / product Feign 의존 제거 (필수)

```java
// UserFeignClient    url = "user-service:18080"    ← 하드코딩
// ProductFeignClient url = "product-service:8086"  ← 하드코딩
```

결제 시나리오만 볼 거라면 user/product 서비스를 띄우는 건 노이즈다.
**`bench` 프로파일 전용 스텁 어댑터**로 대체한다 (§8-①).
네트워크 I/O가 사라지므로 Arm 간 차이가 오롯이 "결제 경로" 차이로 남는다.

### P0-4 · 멱등성 부재 🟡

`PaymentCreateCommand` 재처리 시 중복 결제를 막는 장치가 없다
(`KafkaErrorHandler` 는 5회 재시도 후 DLT). 부하 중 예외가 나면 재시도로 중복 결제가 발생할 수 있다.

- 최소 조치: `Payment.orderCode` 에 **UNIQUE 제약** 추가 → 중복 시 DB가 거부
- 정석: `processed_message(messageId)` 테이블로 consumer 멱등 처리 (`deepseek-kafka-idempotency.md` 참조)

**최소 조치만이라도 하고 측정할 것.** 안 그러면 "무손실" 검증 쿼리가 오염된다.

### P0-5 · payments 로컬 프로파일이 H2 파일 DB 🟡

`payments/src/main/resources/application-local.yml` → `jdbc:h2:./dbay-db`.
commerce 는 MySQL 인데 payments 만 H2면 DB 성능 특성이 달라 비교가 무의미하다.
**`bench` 프로파일에서 두 모듈 모두 동일 MySQL(`dbay_db`) 사용** (§8-③).

### P0-6 · 부하를 받는 쪽(commerce)이 안 보인다 🔴

`commerce/build.gradle` 에 **actuator·micrometer 의존성이 아예 없다.**
`docker/prometheus.yml` 에는 `host.docker.internal:8081` 타깃이 등록돼 있지만 엔드포인트가 없어 **항상 DOWN** 이다.
Grafana 대시보드(`deposit-performance.json`)도 11개 패널 전부 `service="payments"` 하드코딩이고,
`percentiles-histogram` 설정이 없어 p95/p99 패널이 빈 그래프일 가능성이 높다.

즉 **지금 부하 테스트를 돌리면 정작 임계점이 발생하는 commerce 쪽 Tomcat 스레드·HikariCP 대기를 볼 수 없다.**
이번 실험에서 P2(버퍼링) 증명의 핵심 증거가 전부 거기서 나오므로 **측정 자체가 성립하지 않는다.**
→ 조치는 §6.2.

---

### P0-7 · 락 임계 구간 설계 오류로 동기 결제가 100% 실패 ✅ **해결됨 (2026-08-26)**

#### 증상

`POST /api/payments/process` 가 **매번 정확히 50초 후 500**. 결제·이력·잔액 반영 0건.

```
HTTP=500  time=50.71s   {"msg":"Transaction silently rolled back because it has been marked as rollback-only"}
Caused by: MySQLTransactionRollbackException: Lock wait timeout exceeded
```

#### 원인 — 비관적 락 자체가 아니라 **락을 감싼 범위**

락을 쓴 판단과 수단은 옳았다. `SELECT ... FOR UPDATE` 도, `userCode` unique 인덱스로 락 범위를 좁힌 것도 맞다.
문제는 **X 락을 쥔 채로 무엇을 했는가** 다.

```
🔒 X 락 획득 (SELECT ... FOR UPDATE)
   ├ 잔액 검증 + 차감            ← 실제로 보호가 필요한 구간
   ├ Deposit UPDATE
   ├ Payment INSERT
   └ recordPaymentHistory()      ← ❌ REQUIRES_NEW = 별도 커넥션 = 남남인 트랜잭션
        ├ findByCode × 3
        └ DepositHistory INSERT  ← FK 3개가 부모 행 S 락 요구 → 자기 X 락에 막힘
🔒 (락 계속 유지 — 이 메서드가 리턴해야 커밋 가능)
```

**InnoDB 락 덤프 (실측):**

```
trx 2327 (결제, 바깥)   DepositEntity PRIMARY   X,REC_NOT_GAP   GRANTED   id=1
trx 2328 (이력, 안쪽)   DepositEntity PRIMARY   S,REC_NOT_GAP   WAITING   id=1
--- data_lock_waits: waiting 2328 ←blocked by← blocking 2327 ---
```

데드락 탐지기가 못 잡는 이유: 바깥 트랜잭션은 *락* 이 아니라 *애플리케이션 스레드* 를 기다리는 중이라
wait-for 그래프에 사이클이 안 보인다. 그래서 `innodb_lock_wait_timeout`(기본 50초)을 꽉 채운다.

**`REQUIRES_NEW` 의 의도도 무력화됐다.** 내부 `try/catch` 는 "이력이 실패해도 결제는 살린다" 를 노렸지만,
예외는 메서드 본문이 아니라 **리턴 후 커밋 시점**에 `UnexpectedRollbackException` 으로 터져 catch 를 그냥 지나친다.

**언제 들어왔나:** `6386ab0` (HEAD, "예치금 동시성 제어를 위한 비관적 락 적용").
이전에는 `process()` 가 락 없는 조회를 썼으므로 부모 행에 X 락이 없었고 FK 검사가 통과했다.
→ **기존 380ms → 302ms 측정은 이 커밋 이전 코드로 잰 값**이다.

#### 수정 — 이력 저장을 락 구간 밖으로

두 방법을 모두 구현하고 `payments.history.strategy` 로 전환 가능하게 했다.

| | **`join` (기본)** | `after-commit` |
|---|---|---|
| 방식 | 결제 트랜잭션에 합류 (`REQUIRED`) | 커밋 후 `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` |
| FK 충돌 회피 | 같은 트랜잭션은 자기 락에 막히지 않음 | 커밋 시점에 X 락이 이미 해제됨 |
| **요청당 커넥션** | **1개** | **2개** |
| 이력 실패 시 | 결제도 롤백 (감사 기록 보장) | 결제 유지, 이력만 누락 |

#### 실측 결과

단건 (수정 전후):

| | 수정 전 | 수정 후 |
|---|---|---|
| 응답 | **50.71s / HTTP 500** | **87~100ms / HTTP 200** |
| DB 반영 | Payment 0, History 0 | Payment 3, History 3, 차감 15,000원 정확 |

동시 20 × 100건, **같은 유저**(최악의 행 락 경합), HikariCP pool=10:

| 전략 | 성공 | 실패 | 소요 | 차감액 | 결제 | **이력** |
|---|---:|---:|---:|---:|---:|---:|
| **`join`** | **100** | **0** | **9,291ms** | 100,000 | 100 | **100** ✅ |
| `after-commit` | 60 | 40 | 19,496ms | 60,000 | 60 | **41** ❌ |

`after-commit` 실패 원인 (로그):
```
Connection is not available, request timed out after 3000ms (total=10, active=10, idle=0, waiting=19)
CannotCreateTransactionException × 18   ← 리스너가 두 번째 커넥션을 못 받음
```

**요청당 커넥션 2개 요구가 좁은 풀에서 임계점을 절반으로 당긴다.** 게다가 성공한 결제 60건 중
19건은 이력이 누락됐다 — **돈은 빠져나갔는데 감사 기록이 없는 상태**라 결제 시스템에서는 받아들이기 어렵다.
그래서 기본값은 `join`.

> **비관적 락은 그대로 유지했다.** 두 전략 모두 차감액 = 결제건수 × 금액이 정확히 일치해
> lost update 가 없음을 확인했다 (`join`: 100,000 = 100 × 1,000).

#### 이 발견의 활용

> "비관적 락 도입 후 `REQUIRES_NEW` 이력 저장이 FK 검사로 자기 X 락에 막혀 결제가 50초 타임아웃 나던 것을
> 부하 테스트 환경 구성 중 발견. InnoDB 락 덤프로 원인을 특정하고 이력 저장을 락 구간 밖으로 옮겨
> 응답 50.7s → 0.09s, 동시 100건 성공률 60% → 100% 로 개선."

면접에서 "그래서 비관적 락이 문제였나요?" 에는 이렇게 답한다:
**"락은 옳게 걸었고 유지했습니다. 락을 감싼 임계 구간 설계가 틀렸던 것을 고쳤습니다."**

---

## 3. 실험 설계 — 4개의 Arm

같은 요청(`POST /api/commerce/order/{code}`), 같은 DB, 같은 부하 프로파일.
**"결제와 후처리를 어디까지 동기로 하는가"** 만 바꾼다.

| Arm | 이름 | 결제 | 후처리(주문완료·장바구니·상품) | Kafka 사용 | 설정 |
|-----|------|------|-------------------------------|-----------|------|
| **A** | ALL SYNC | 동기 Feign (**트랜잭션 안**) | **동기 DB 쓰기** | ❌ 없음 | `payment.strategy=feign`<br>`postprocess.strategy=sync` |
| **A′** | SYNC (개선) | 동기 Feign (**트랜잭션 밖**) | 동기 DB 쓰기 | ❌ 없음 | 위 + `tx-boundary=outside` |
| **B** | SYNC + KAFKA 후처리 | 동기 Feign | **Kafka 비동기** | ✅ 후처리만 | `payment.strategy=feign`<br>`postprocess.strategy=kafka` (현재 기본) |
| **C** | FULL KAFKA (Saga) | **Kafka 비동기** | Kafka 비동기 | ✅ 전 구간 | `payment.strategy=kafka` |

### 3.1 각 Arm이 증명하는 것

| 비교 | 증명 내용 | 이력서 문장으로 |
|------|-----------|----------------|
| A vs B | 후처리를 큐로 뺐을 때의 응답 시간 이득 (P1) | "후처리 이벤트화로 결제 응답 p95 XX% 단축" |
| A vs A′ | Kafka 없이 트랜잭션 경계만 고쳐도 얻는 이득 | "성능 개선이 곧 Kafka는 아니라는 걸 구분해서 판단" ← **면접 가산점** |
| B vs C | 결제까지 비동기화했을 때 얻는 것(응답·거절률)과 잃는 것(완결 지연) (P2·P4) | "즉시 응답 대신 최종 일관성 지연 p99 XXs를 감수" |
| A vs C (버스트) | 자원 고갈 구간에서의 생존 여부 (P2·P3) | "동기는 거절률 XX%, Kafka는 0%" |

### 3.2 변수 통제

| 항목 | 값 (전 Arm 공통) |
|------|-----------------|
| DB | MySQL 8.0 컨테이너, 동일 `dbay_db` 스키마, 매 회차 전 리셋 |
| **자원 상한** | **t3.large(2 vCPU / 8 GiB) 재현** — SUT 컨테이너 합계 2.00 CPU / 5.3 GiB (사전작업 문서 §4.1) |
| JVM | commerce/payments 각각 `-Xms512m -Xmx512m -XX:+UseG1GC` + non-heap 상한 고정 |
| 예치금 이력 저장 | `payments.history.strategy=join` 고정 (§2 P0-7) — Arm 간 커넥션 요구량을 동일하게 유지 |
| user/product | `bench` 스텁 (네트워크 I/O 0) |
| 상품 가격 | 5,000원 고정 (단건 주문) |
| 초기 잔액 | 유저당 1,000,000,000원 (버스트 중 고갈 방지) |
| Kafka | 파티션 3 / replica 1, `acks=all`, `enable.idempotence=true` (현행 유지) |
| 웜업 | 각 회차 전 20 TPS × 60초 (JIT + buffer pool) |
| 반복 | Arm 당 3회, **중앙값** 채택 |

### 3.3 부하 프로파일 (버스트)

로컬에서 반복 가능해야 하므로 한 회차 9분으로 압축한다.

```
stage 1  baseline : 20 TPS × 3분      ← 정상 구간 응답 시간 확보
stage 2  spike    : 20 → T TPS × 30초 ramp
stage 3  burst    : T TPS × 3분        ← 임계점 돌파 구간
stage 4  recover  : 20 TPS × 2분30초   ← 회복 관찰
k6 종료 후: lag → 0 까지 drain 시간 측정 (Kafka Arm)
```

> **⚠️ 초안의 `T = 400` 은 실측 결과 비현실적이다 (2026-08-26).**
> 자원 제약(§3.4)을 건 환경에서 **읽기 전용** 엔드포인트에 동시 30 / 약 100 TPS 를 넣었을 때
> 이미 `hikaricp_connections_active` 9/10, `pending` 1, p95 289ms 였다.
> 결제는 이보다 훨씬 무겁다(`SELECT FOR UPDATE` + INSERT 3건).
> **`T` 후보 구간을 50~200 으로 잡고 단계 2 예비 측정으로 확정한다.**

k6 executor: `ramping-arrival-rate` (도착률 고정 → **부하가 시스템 응답에 끌려가지 않음**. `constant-vus` 를 쓰면 느려진 시스템에 부하가 자동으로 줄어들어 임계점이 안 보인다.)

> **burst TPS = 400 은 시작값.** §7 단계 2의 예비 측정으로 확정한다.
> 기준: **Arm A가 에러율 5%를 넘기 시작하는 지점의 약 1.5~2배.**

### 3.4 자원 제약 (임계점을 "만든다")

로컬 18코어 머신에서 기본 설정(Tomcat 200 threads / Hikari 10)이면
부하를 아무렇게나 주면 아무 일도 안 일어나거나, 반대로 Hikari 10 때문에 즉시 무너진다.
**의도적으로 운영 서버의 축소판을 만든다:**

```yaml
# 전 Arm 공통 (application-bench.yml)
server:
  tomcat:
    threads:
      max: 50            # 워커 스레드
    accept-count: 100    # 큐 길이 (초과 시 커넥션 거절)
    connection-timeout: 3s
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      connection-timeout: 3000   # 3초 안에 커넥션 못 받으면 예외
```

- Tomcat 50 스레드 × 3초 타임아웃 → **"스레드풀이 말라서 요청이 거절되는" 상황을 재현**
- Hikari 10 + 3초 타임아웃 → Arm A의 `@Transactional` 안 Feign 호출이 커넥션을 오래 쥐면 곧바로 대기열 폭발

### 3.5 유저 풀 — 2가지 변형

| 변형 | 유저 수 | 노리는 병목 |
|------|---------|------------|
| **WIDE** (기본) | 10,000명 | 스레드/커넥션 풀 고갈 (락 경합 거의 없음) |
| **NARROW** (보조) | 50명 | 예치금 행 락 경합 (`SELECT ... FOR UPDATE`) |

WIDE 가 P1~P3 증명용, NARROW 는 "동시성이 몰릴 때 비관적 락이 어떻게 동작하는지" 보조 자료.
**시간이 부족하면 WIDE 만 해도 이력서 문장은 나온다.**

### 3.6 파티션 수 보조 실험 (선택, 임팩트 큼)

Arm C 를 파티션 **1 → 3 → 6** 으로 바꿔가며 동일 부하를 준다.

- 응답 시간은 거의 안 변함 (앞단은 produce 만 하므로)
- **drain 시간과 최대 lag 이 파티션 수에 반비례** → "Kafka는 파티션으로 소비 측을 수평 확장할 수 있다"를 수치로 증명
- 동기 Arm 에는 이런 손잡이가 없다는 대비가 선명해진다

> ⚠️ 파티션을 늘리면 **키가 다른 메시지의 순서 보장이 깨진다.** 현재 코드는 전부 `orderCode` 를 키로 발행하므로
> 같은 주문의 이벤트는 같은 파티션 = 순서 보장 유지된다. 이 점을 문서에 명시해두면 면접 방어가 된다.

---

## 4. 측정 지표

### 4.1 핵심은 "두 개의 시간"을 분리하는 것

```
     ┌──────────── 체감 응답 시간 (사용자가 기다리는 시간) ────────────┐
요청 ─────────────────────────▶ 204 응답
     └──────────────────── 완결 시간 (주문이 PAID 가 되기까지) ─────────────────────┘
```

- Arm A/A′: 두 시간이 **같다**
- Arm B/C: 체감은 짧고 완결은 길다 → **이 격차가 곧 Kafka의 트레이드오프**

### 4.2 지표 목록

| 구분 | 지표 | 수집 방법 |
|------|------|----------|
| **체감 응답** | p50 / p95 / p99 / max | k6 `http_req_duration` |
| **거절/실패** | 에러율, 5xx, 타임아웃, `dropped_iterations` | k6 요약 |
| **처리량** | 실제 완료 TPS vs 목표 TPS | k6 `http_reqs` / `iterations` |
| **완결 시간** | `Orders.updatedAt - createdAt` (status=PAID) p50/p95/p99 | SQL (§4.4) |
| **큐잉** | consumer group lag 시계열, 최대 lag, drain 시간 | `k6-scripts/lag-monitor.sh` (기존 재사용, group id만 교체) |
| **커넥션풀** | `hikaricp_connections_pending`, `_active` | Prometheus (`/actuator/prometheus`) |
| **스레드풀** | `tomcat_threads_busy_threads`, `_current_threads` | Prometheus |
| **DB 락** | `innodb_row_lock_waits`, `innodb_row_lock_time_avg` | `SHOW GLOBAL STATUS` 사전/사후 diff |
| **정합성** | 요청 성공 수 = Payment 수 = Orders PAID 수, 잔액 수지 | SQL (§4.4) |
| **JVM** | GC pause, heap | Prometheus |

> commerce 모듈에 `management.endpoints.web.exposure.include=health,prometheus,metrics` 가
> 없다 → `application-bench.yml` 에 추가할 것 (payments 는 local 에 이미 있음).
> `docker/prometheus.yml` 스크레이프 타깃에 `host.docker.internal:8081`, `:8085` 추가 필요.

### 4.3 "유실" 을 엄밀하게 정의

용어를 흐리면 면접에서 바로 무너진다. 3가지를 구분한다.

| 구분 | 정의 | 어느 Arm에서 나오나 |
|------|------|-------------------|
| **거절 (fast fail)** | 요청이 5xx/타임아웃으로 **실패 응답을 받음**. 사용자는 실패를 안다 | 동기 Arm (스레드/커넥션 고갈) |
| **유실 (silent loss)** | **성공 응답을 받았는데** 최종 상태가 반영되지 않음 | 양쪽 다 가능 — Kafka Arm은 DLT 적재로, 동기 Arm은 부분 커밋으로 |
| **지연 (eventual)** | 성공 응답 + 최종 반영이 **나중에** 완료됨 | Kafka Arm 정상 동작 |

**측정 규칙:** k6가 2xx를 받은 요청 수를 `S`라 할 때, drain 완료 후
`S == COUNT(Orders WHERE status='PAID') == COUNT(Payment WHERE status='PAYMENT_COMPLETED')`
가 성립해야 유실 0. 차이가 있으면 DLT 토픽 건수와 대조한다.

### 4.4 검증 SQL

```sql
-- ① 완결 시간 분포 (Kafka Arm의 최종 일관성 지연)
SELECT
  COUNT(*) AS paid_cnt,
  ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt))/1000, 1) AS avg_ms,
  ROUND(MAX(TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt))/1000, 1) AS max_ms
FROM Orders
WHERE orderStatus = 'PAID' AND createdAt >= '<테스트 시작시각>';

-- p95/p99 (MySQL 8 윈도우 함수)
SELECT MAX(ms) AS p95_ms FROM (
  SELECT TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt)/1000 AS ms,
         PERCENT_RANK() OVER (ORDER BY TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt)) AS pr
  FROM Orders WHERE orderStatus='PAID' AND createdAt >= '<시작시각>'
) t WHERE pr <= 0.95;

-- ② 상태 분포 (PENDING 잔류 = 미완결)
SELECT orderStatus, COUNT(*) FROM Orders
WHERE createdAt >= '<시작시각>' GROUP BY orderStatus;

-- ③ 결제 정합성
SELECT paymentStatus, COUNT(*), SUM(amount) FROM Payment
WHERE createdAt >= '<시작시각>' GROUP BY paymentStatus;

-- ④ 잔액 수지 (차감액 == 결제 성공 총액 이어야 함)
SELECT SUM(balance) FROM DepositEntity WHERE userCode LIKE 'BENCH-%';
-- 기대: (유저수 × 초기잔액) - (PAYMENT_COMPLETED 건수 × 5000)

-- ⑤ 예치금 이력 건수 (이중 차감 탐지)
SELECT type, COUNT(*) FROM DepositHistoryEntity
WHERE createdAt >= '<시작시각>' GROUP BY type;
```

```bash
# ⑥ DLT 잔류 확인
for t in payments-purchase-commands payments-purchase-events \
         deposits-purchase-commands deposits-purchase-events orders-purchase-commands; do
  echo -n "$t.DLT: "
  docker exec bench-kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
    --bootstrap-server kafka:9090 --topic "$t.DLT" 2>/dev/null | awk -F: '{s+=$3} END{print s+0}'
done

# ⑦ consumer lag
docker exec bench-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9090 --describe --all-groups
```

---

### 4.5 백분위수를 어디까지, 어떻게 보는가

**결론: 두 시간 각각에 대해 p50 / p95 / p99 를 전부 낸다. 주 지표는 p95, 보조는 p99.**

| | 체감 응답 시간 | 완결 시간 |
|---|---|---|
| 산출 주체 | k6 (자동) | 직접 SQL (§4.4 ①) |
| 산출 값 | p50 / p90 / p95 / p99 / max | p50 / p95 / p99 / max |
| 구간 분리 | `http_req_duration{stage:burst}` 태그로 baseline·burst 분리 | `WHERE createdAt BETWEEN` 로 구간 분리 |

#### 왜 평균이 아니라 백분위수인가

평균은 **꼬리를 감춘다.** 100건 중 99건이 50ms, 1건이 3,000ms면 평균은 79.5ms 로 "괜찮아 보인다".
하지만 실제로는 100명 중 1명이 3초를 기다린 것이고, **버스트 구간에서 무너지는 건 언제나 이 꼬리부터**다.
기존 측정치 `380 → 302ms` 가 평균이라면, 이번 실험이 그것을 대체해야 하는 이유가 정확히 이것이다.

#### 왜 p95를 주 지표로 두는가 (p99가 아니라)

p99는 **샘플 수가 적으면 통계적으로 불안정**하다. p99는 사실상 "상위 1%의 경계값 한 개"라서
회차마다 크게 흔들리고, 그 흔들림을 "개선"으로 착각하기 쉽다.

| 구간 | 샘플 수 | p95 신뢰도 | p99 신뢰도 |
|---|---|---|---|
| baseline 20 TPS × 3분 | 약 3,600건 | ✅ 안정 | ⚠️ 상위 36건 기준 — **참고용** |
| burst 150 TPS × 3분 | 약 27,000건 | ✅ 안정 | ✅ 상위 720건 기준 — 사용 가능 |

**규칙:**
- **p95** — 모든 구간에서 주 지표로 사용. Arm 간 비교·이력서 수치는 여기서 뽑는다
- **p99** — burst 구간에서만 결론에 사용. baseline p99는 참고 표기만
- **max** — 단일 이상치라 결론 근거로 쓰지 않되, 이상 징후 탐지용으로 기록
- **3회 반복의 중앙값** 채택 (평균 아님 — 한 회차 튀는 값에 끌려가지 않기 위해)

#### 완결 시간 p95/p99 산출 시 주의

`Orders.updatedAt` 은 `PAID` 전이 시각이지만, 이후 다른 갱신이 있으면 덮어써진다.
테스트 중에는 `autoUpdateOrderStatus()` 스케줄러(`OrderScheduler`)가 상태를 바꿀 수 있으므로
**bench 프로파일에서 스케줄러를 반드시 끈다** (`spring.task.scheduling.enabled=false` 또는 `@Profile` 배제).
안 그러면 완결 시간 분포가 오염된다.

또한 `updatedAt` 은 **commerce JVM 시계**, `createdAt` 도 같은 JVM이므로 시계 오차는 없다.
다만 `Orders` 컬럼이 `datetime(6)` 인지 확인할 것 — `datetime(0)` 이면 1초 미만이 전부 0으로 뭉개진다.

```sql
-- 정밀도 확인 (DATETIME(6) 이어야 함)
SHOW COLUMNS FROM Orders LIKE '%dAt';
```

---

## 5. 기대 결과 (가설)

측정 전에 가설을 적어두고, 빗나가면 그 이유를 분석하는 것까지가 실험이다.

| 지표 | A (ALL SYNC) | A′ (SYNC 개선) | B (SYNC+Kafka 후처리) | C (FULL KAFKA) |
|------|--------------|---------------|----------------------|----------------|
| baseline p99 | 기준 | A보다 소폭↓ | A보다 ↓ | 가장 낮음 |
| **burst p99** | **급등 (초 단위)** | 급등하나 A보다 완만 | 중간 | **평탄** |
| **burst 거절률** | **높음 (스레드/커넥션 고갈)** | A보다 낮음 | 중간 | **0%** |
| 실제 처리 TPS | 목표 미달 | 목표 미달 | 근접 | 목표 달성 |
| Hikari pending | **폭증** | 낮음 | 낮음 | 거의 0 |
| 최대 lag | – | – | 후처리 큐만 증가 | 결제 큐까지 증가 |
| **완결 시간 p99** | = 응답 시간 | = 응답 시간 | 응답 + α | **가장 김 (초~십초)** |
| drain 시간 | – | – | 짧음 | 파티션 수에 반비례 |
| 유실 | 0 (거절은 있음) | 0 | 0 | 0 (DLT 0 확인 필요) |

**핵심 스토리라인:**
> Arm A는 임계점 `T` 에서 무너진다. Arm A′로 트랜잭션 경계만 고쳐도 상당히 버틴다
> (→ *"성능 문제를 무조건 Kafka로 풀지 않았다"* 는 근거).
> 하지만 결제 처리 자체의 용량을 넘어서는 순간 A′도 거절이 시작되고,
> **Arm C만 거절 0을 유지한다. 대가는 완결 시간 p99 XXs.**

---

## 6. 테스트 중 관측 — 무엇을, 어디서, 어떻게 보는가

> 이 장이 없으면 9분짜리 회차를 12번 돌리고 나서 "그래서 뭐가 부족했던 거지?" 로 끝난다.
> **부하를 걸기 전에 이 장의 준비물부터 갖춘다.**

### 6.1 현재 상태 진단 — 지금은 절반만 보인다

| 대상 | 상태 | 근거 |
|------|------|------|
| payments 메트릭 | ✅ 있음 | `payments/build.gradle:35-36` actuator + micrometer-prometheus, `application-local.yml` 에 `management.endpoints.web.exposure.include=health,prometheus,metrics` |
| **commerce 메트릭** | ❌ **없음** | `commerce/build.gradle` 에 **actuator 의존성 자체가 없다.** `management` 설정도 없음 |
| Prometheus 타깃 | ⚠️ 절반 | `docker/prometheus.yml` 에 8081/8085 둘 다 등록되어 있으나, **8081은 엔드포인트가 없어 항상 DOWN** |
| Grafana 대시보드 | ⚠️ payments 전용 | `deposit-performance.json` 11개 패널 전부 `service="payments"` 하드코딩. **Tomcat 스레드 패널 없음, Kafka lag 패널 없음** |
| Kafka lag | ⚠️ CLI만 | exporter 없음 → `lag-monitor.sh` 의 CSV 사후 분석만 가능 |
| MySQL 지표 | ❌ 없음 | exporter 없음 → `SHOW GLOBAL STATUS` 수동 diff |
| k6 지표 | ❌ 실시간 불가 | k6는 **요약을 종료 시에만** 출력. 진행 중에는 p95/p99가 안 보임 |

**가장 큰 문제 두 개:**
1. 부하를 정면으로 받는 쪽이 **commerce(8081)** 인데 그쪽이 안 보인다. Tomcat 스레드 포화·HikariCP 대기 같은 이번 실험의 핵심 증거가 전부 commerce 쪽에서 나온다.
2. k6 결과와 서버 지표가 **다른 시간축**에 있다. "p99가 튄 그 순간에 커넥션 대기가 몇이었나" 를 대조할 수 없다.

### 6.2 최소 준비물 (약 30분)

#### ① commerce 에 actuator 추가

```gradle
// commerce/build.gradle — dependencies 블록
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

```yaml
# commerce/src/main/resources/application-bench.yml (payments-bench 에도 동일하게)
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true     # ← 이게 있어야 Grafana 에서 p95/p99 계산 가능
      slo:
        http.server.requests: 50ms,100ms,300ms,1s,3s
```

> `percentiles-histogram: true` 가 없으면 `http_server_requests_seconds_bucket` 이 안 생겨서
> 기존 대시보드의 `histogram_quantile(...)` 패널이 **빈 그래프**로 나온다. 지금 대시보드가 그 상태일 가능성이 높다.

확인: `curl -s localhost:8081/actuator/prometheus | grep -c http_server_requests_seconds_bucket` → 0이 아니어야 함.
그리고 http://localhost:9090/targets 에서 commerce·payments **둘 다 UP**.

#### ② k6 지표를 Prometheus 로 보내기 (핵심)

이걸 해야 **k6 응답 시간과 서버 지표를 같은 화면·같은 시간축**에서 볼 수 있다.

```yaml
# docker/docker-compose-bench.yml — prometheus 서비스의 command 에 한 줄 추가
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.enable-lifecycle'
      - '--web.enable-remote-write-receiver'   # ← 추가
```

```bash
# k6 실행 시
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99),max' \
k6 run -o experimental-prometheus-rw k6-scripts/order-burst.js
```

Grafana 에서 쓰는 쿼리:

| 보고 싶은 것 | PromQL |
|---|---|
| 실시간 응답 p95 | `k6_http_req_duration_p95` |
| 실시간 응답 p99 | `k6_http_req_duration_p99` |
| 실제 도착률(TPS) | `rate(k6_http_reqs_total[30s])` |
| 실패율 | `rate(k6_http_req_failed_total[30s])` |
| **버려진 요청** | `k6_dropped_iterations_total` |

**대안 (설정이 귀찮으면):** `k6 run --out csv=results/<arm>-raw.csv` 로 원본을 뽑고,
별도 터미널에서 10초 윈도우로 집계해 흘려보낸다. 실시간성은 떨어지지만 사후 분석은 동일하게 된다.

```bash
# 터미널에서 10초마다 최근 구간 p95 를 대충 보기
tail -f results/<arm>-raw.csv | awk -F, '$1=="http_req_duration"{a[n++]=$3}
  n>=200{asort(a); print strftime("%H:%M:%S"), "p95=" a[int(n*0.95)] "ms"; n=0; delete a}'
```

#### ③ Grafana 대시보드 확장

기존 `deposit-performance.json` 을 복사해 `bench-dashboard.json` 을 만들고:

- `service="payments"` 하드코딩 → **대시보드 변수 `$service`** 로 교체 (commerce/payments 토글)
- **추가할 패널 4개** (이번 실험의 핵심 증거)

| 패널 | PromQL |
|---|---|
| Tomcat 워커 포화도 | `tomcat_threads_busy_threads{service=~"$service"}` / `tomcat_threads_config_max_threads{...}` |
| HikariCP 대기 | `hikaricp_connections_pending{service=~"$service"}` (기존 패널에 이미 포함 — commerce 도 나오게 변수화) |
| k6 응답 p95/p99 | `k6_http_req_duration_p95`, `k6_http_req_duration_p99` |
| 거절 | `rate(k6_http_req_failed_total[30s])`, `k6_dropped_iterations_total` |

> 시간이 없으면 **Tomcat 패널과 k6 패널만** 추가해도 된다. 이 둘이 P2(버퍼링) 증명의 핵심 증거다.

#### ④ Kafka lag (선택 — 있으면 그래프, 없으면 CSV)

```yaml
# docker-compose-bench.yml (이미 포함되어 있음)
  kafka-exporter:
    image: danielqsj/kafka-exporter:latest
    container_name: kafka-exporter
    command: ["--kafka.server=kafka:9090"]
    ports: ["9308:9308"]
    depends_on: [kafka]
```
```yaml
# prometheus.yml scrape_configs 에 추가
  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka-exporter:9308']
```
→ `sum(kafka_consumergroup_lag{consumergroup="payments-bench"}) by (topic)` 로 lag 그래프.

안 붙이면 §6.3 터미널 2의 `watch` + `lag-monitor.sh` CSV 로 대체 가능하다.

### 6.3 관제 배치 — 테스트 중 실제로 보는 화면

```
┌─────────────────────────┬─────────────────────────────────────┐
│ 터미널 1 — k6            │  브라우저 — Grafana (bench-dashboard)│
│ 진행률 / VU / 반복 수    │  ① k6 p95·p99   ② 거절률            │
│ dropped_iterations       │  ③ Tomcat busy  ④ Hikari pending    │
│                         │  ⑤ GC pause     ⑥ Kafka lag         │
├─────────────────────────┼─────────────────────────────────────┤
│ 터미널 2 — lag watch     │  터미널 3 — 원샷 판정 명령           │
│ 2초 갱신 consumer lag    │  DLT 잔류 / 상태 분포 / DB 락        │
└─────────────────────────┴─────────────────────────────────────┘
```

**터미널 2 — lag 실시간 감시**
```bash
watch -n 2 'docker exec bench-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9090 --describe --all-groups \
  | awk "NR==1 || \$6 ~ /^[0-9]+$/" | sort -k6 -rn | head -20'
```

**터미널 3 — 30초마다 한 번씩 두드리는 판정 명령**
```bash
# 주문 상태 분포 (PENDING 이 계속 쌓이면 후처리가 못 따라가는 중)
docker exec bench-mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SELECT orderStatus, COUNT(*) FROM Orders GROUP BY orderStatus;"

# DB 락 경합
docker exec bench-mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SHOW GLOBAL STATUS WHERE Variable_name IN
   ('Innodb_row_lock_waits','Innodb_row_lock_time_avg','Threads_connected','Slow_queries');"

# 부하 생성기 자신이 병목인지 (로컬은 k6와 앱이 같은 머신!)
top -l 1 | head -5
```

> ⚠️ **로컬 테스트의 최대 함정**: k6·commerce·payments·MySQL·Kafka 가 전부 한 머신에 있다.
> CPU가 100% 에 붙으면 그건 "서버가 느린 것"이 아니라 **"측정 장비가 부족한 것"** 이다.
> 매 회차 CPU 여유(최소 20~30%)를 확인하고, 없으면 burst TPS 를 낮춰야 한다.

### 6.4 "지금 무슨 일이 일어나고 있는가" 판정표

| 실시간 신호 | 어디서 | 의미 | 조치 |
|---|---|---|---|
| `dropped_iterations` > 0 | k6 | **부하 생성기가 목표 도착률을 못 만듦** | `preAllocatedVUs`/`maxVUs` 상향 후 **재실행. 이 회차는 무효** |
| 시스템 CPU ≈ 100% | 터미널 3 | 측정 장비 포화 | burst TPS 하향. 이 회차 무효 |
| `hikaricp_connections_pending` > 0 지속 | Grafana | 커넥션 대기 시작 = DB 병목 진입 | **Arm A에서 기대되는 현상.** 최대값 기록 |
| `tomcat_threads_busy` ≈ 50 (max) | Grafana | 워커 포화 → 이후 accept 큐로 밀림 | **임계점 도달.** 이 시각 기록 |
| 5xx / connection reset 증가 | k6, Grafana | 거절 시작 | **임계점 T 확정 지점.** TPS 기록 |
| p99만 튀고 p50은 평탄 | Grafana | 꼬리 지연 (락 대기·GC) | 정상적 열화. 계속 진행 |
| p50까지 상승 | Grafana | 전면 포화 | 한계 초과 구간 진입 |
| consumer lag 우상향 | 터미널 2 | **버퍼링 작동 중 (정상)** | Kafka Arm의 기대 동작. 최대 lag 기록 |
| Orders PENDING 계속 증가 | 터미널 3 | 후처리가 유입을 못 따라감 | 정상(비동기). drain 되는지가 관건 |
| **k6 종료 후에도 lag 안 줄어듦** | 터미널 2 | consumer 사망 / 예외 루프 | **중단.** 앱 로그와 DLT 확인 |
| DLT 오프셋 증가 | 터미널 3 | 처리 실패 발생 | **원인 규명 전까지 결과 무효** |
| GC pause 급증 / heap 90%+ | Grafana | 힙 부족 | 힙 상향 후 재실행 |

### 6.5 회차 무효 조건 (이러면 그 데이터는 버린다)

- [ ] `dropped_iterations` > 0 — 부하 생성기 병목
- [ ] 머신 CPU 포화 (여유 20% 미만)
- [ ] DLT 에 메시지 적재됨
- [ ] 웜업 미실시
- [ ] 이전 회차 데이터 미삭제 (Orders/Payment/lag/consumer group)
- [ ] **lag drain 전에 `Orders` 를 삭제함** ← 2026-08-26 실제로 밟은 함정.
      처리 중이던 커맨드의 소비자가 `ORDER_NOT_FOUND` 를 만나 **지수 백오프 5회 재시도**에 들어가고,
      그 지연이 다음 측정 구간까지 밀고 들어온다. **초기화 전에 반드시 lag=0 을 확인할 것**
- [ ] Prometheus 타깃 중 하나라도 DOWN (지표 구멍)

### 6.6 회차마다 남기는 것

```bash
# k6-scripts/run-arm.sh (권장 래퍼) — 시작/종료 시각까지 자동 기록
ARM=$1; RATE=$2
mkdir -p results/$ARM
START=$(date '+%Y-%m-%d %H:%M:%S')
echo "start=$START" > results/$ARM/meta.txt

./k6-scripts/lag-monitor.sh 2 results/$ARM/lag.csv 12 &
LAGPID=$!

K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99),max' \
k6 run -o experimental-prometheus-rw \
  --summary-export=results/$ARM/summary.json \
  --out csv=results/$ARM/raw.csv \
  -e BURST_RATE=$RATE k6-scripts/order-burst.js

echo "k6_end=$(date '+%Y-%m-%d %H:%M:%S')" >> results/$ARM/meta.txt
echo "drain 대기…"; sleep 120           # lag 0 확인용
kill $LAGPID 2>/dev/null
echo "end=$(date '+%Y-%m-%d %H:%M:%S')" >> results/$ARM/meta.txt

docker exec bench-mysql mysql -umysqlId -pmysqlPwd dbay_db \
  < k6-scripts/verify-bench.sql > results/$ARM/verify.txt
```

`meta.txt` 의 시작/종료 시각이 **Grafana 구간 지정과 스크린샷의 근거**가 된다. 이게 없으면 나중에 어느 그래프가 어느 회차인지 못 찾는다.

### 6.7 스모크 런 — 본 측정 전 반드시 1회

본 측정은 4 Arm × 3회 × 9분 = **약 2시간**이다. 셋업이 틀렸으면 전부 날린다.
그 전에 **2분짜리 축소판**으로 파이프라인이 끝까지 도는지 확인한다.

```
20 TPS × 60초 → drain 60초
```

체크리스트:
- [ ] Prometheus `/targets` — commerce, payments 둘 다 UP
- [ ] `curl localhost:8081/actuator/prometheus | grep http_server_requests_seconds_bucket` → 출력 있음
- [ ] Grafana 에 k6 시리즈(`k6_http_req_duration_p95`)가 들어옴
- [ ] `SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID'` → **0이 아님** (스텁·시드·결제가 실제로 동작)
- [ ] drain 후 lag = 0
- [ ] DLT 전 토픽 오프셋 = 0
- [ ] 잔액 감소분 == 결제 성공 건수 × 5,000 (이중 차감 없음 = P0-1 수정 검증)

**7개 전부 통과해야 본 측정 시작.**

---

## 7. 실행 절차

### 단계 0 — 선결 과제 처리
- [ ] P0-1 이중 차감 수정 (§2)
- [ ] P0-3 bench 스텁 어댑터 작성
- [ ] P0-4 `Payment.orderCode` UNIQUE 추가
- [ ] P0-5 payments bench 프로파일 = MySQL
- [ ] **P0-6 commerce actuator + `percentiles-histogram` 추가 (§6.2-①)**
- [ ] bench 프로파일에서 `OrderScheduler` 비활성화 (완결 시간 오염 방지, §4.5)
- [ ] Arm A / A′ 용 `order.postprocess.strategy` 스위치 구현

### 단계 1 — 환경 셋업

```bash
# 1) jar 빌드 (코드를 고칠 때마다)
./gradlew :commerce:bootJar :payments:bootJar

# 2) 전체 기동 (mysql + kafka + commerce + payments + 관측 스택)
docker compose -f docker/docker-compose-bench.yml build
docker compose -f docker/docker-compose-bench.yml up -d

# 3) 시드
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < k6-scripts/seed-bench.sql
```

> **토픽 수동 생성은 필요 없다** (2026-08-26 확인).
> 각 모듈의 `NewTopic` 빈(`OrderKafkaTopicConfig`, `CartKafkaTopicConfig`, `DepositKafkaConfig`,
> `PaymentKafkaConfig`)이 `custom.kafka.config.topic-partitions` 값으로 **DLT 포함 31개를 자동 생성**한다.

> **앱은 컨테이너로 띄운다.** 호스트에서 `bootRun` 하면 t3.large 재현 자원 상한(2 vCPU)이 적용되지 않는다.
> 접속 정보: MySQL `localhost:13306`, Kafka `localhost:19092` (호스트) / `kafka:9090` (컨테이너 내부).
> 자세한 내용은 [`claude-kafka-test-사전작업.md`](./claude-kafka-test-사전작업.md) §7.

### 단계 1.5 — 스모크 런 (필수)

20 TPS × 60초 축소판으로 **§6.7 체크리스트 7개**를 통과시킨다.
여기서 막히면 본 측정 2시간을 통째로 날린다.

### 단계 2 — 예비 측정 (버스트 TPS 확정)
Arm A 로 `25 → 50 → 75 → 100 → 150 → 200` TPS 를 각 60초씩 계단식으로 올린다.
- 에러율이 5%를 넘는 지점 = **동기 임계점 `T`**
- 본 테스트 burst TPS = `1.5T ~ 2T` 로 확정
- 이 계단 결과 자체가 결과 문서의 좋은 표가 된다

### 단계 3~6 — 각 Arm 본 측정
Arm 하나당:
```bash
# 1) 리셋
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < k6-scripts/reset-bench.sql
docker exec bench-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9090 \
  --delete --group commerce-bench --group payments-bench 2>/dev/null || true

# 2) 앱 기동 (Arm 별 환경변수만 교체 — 재빌드 불필요)
BENCH_PAYMENT_STRATEGY=feign BENCH_POSTPROCESS_STRATEGY=sync \
  docker compose -f docker/docker-compose-bench.yml up -d commerce payments

# 3) lag 기록 시작
./k6-scripts/lag-monitor.sh 2 results/<arm>-lag.csv 12 &

# 4) 부하
k6 run --out json=results/<arm>-k6.json \
  -e BASE=http://localhost:8081 -e BURST_RATE=<T확정값> \
  k6-scripts/order-burst.js

# 5) drain 대기 후 검증 SQL 실행 (§4.4)
```

### 단계 7 — 보조 실험
- 파티션 1 / 3 / 6 (Arm C)
- NARROW 유저 풀 (50명)
- (선택) 버스트 중 payments 프로세스 kill → 재시작 → 유실 0 확인 = **내구성 증명**

### 단계 8 — 결과 정리
§10 템플릿 작성 → Grafana 스크린샷(p99 시계열, Hikari pending, lag) 첨부

---

## 7.5 설계 변경 이력 (실측으로 바뀐 것)

초안은 구현 전에 쓴 것이라, 실제로 만들어보니 어긋난 부분이 있다. **뼈대(Arm 구성·측정 지표·절차)는 그대로**이고 바뀐 건 전제와 실행 세부다.

| # | 항목 | 초안 | 실제 | 이유 |
|---|------|------|------|------|
| 1 | **P0-7** | 없음 | 추가 (§2) | 비관적 락 임계 구간 오류로 동기 결제가 100% 실패. 실측으로 발견 |
| 2 | **P0-6** | 없음 | 추가 (§2) | commerce 에 actuator 가 없어 측정 자체가 불가했음 |
| 3 | **버스트 TPS** | 400 고정 | **50~200 후보, 예비 측정으로 확정** | 읽기 전용 100 TPS 에서 이미 Hikari 9/10 |
| 4 | **앱 실행 방식** | 호스트 `gradlew bootRun` | **컨테이너** | t3.large(2 vCPU) 자원 상한을 걸려면 컨테이너여야 함 |
| 5 | **토픽 생성** | 수동 7개 × 2 | **불필요** | `NewTopic` 빈이 DLT 포함 31개 자동 생성 |
| 6 | **컨테이너명·포트** | `mysql` / `kafka`, 3306 / 9092 | **`bench-mysql` / `bench-kafka`, 13306 / 19092** | 기존 로컬 개발 컨테이너와 충돌 방지 |
| 7 | **Kafka CLI 주소** | `localhost:9092` | **`kafka:9090`** (컨테이너 내부) | EXTERNAL 리스너가 `localhost:19092` 를 광고해 컨테이너 안에서는 못 씀 |
| 8 | **Elasticsearch** | 자동 구성 제외로 충분 | **no-op VectorStore 빈 필요** | `ElasticsearchVectorStore` 가 `initialize-schema` 와 무관하게 기동 시 연결 |
| 9 | **새 실험 변수** | 없음 | `payments.history.strategy` | P0-7 수정 과정에서 두 방식의 차이가 커서 측정 대상이 됨 (§2 P0-7) |
| 10 | **관측 스택 예산** | 언급 없음 | **SUT 예산 밖으로 분리** | 관측 오버헤드가 SUT 자원을 잠식하면 측정 왜곡 |

**바뀌지 않은 것:** 4개 Arm 구성, 체감/완결 시간 분리 측정, p95 주 지표 원칙, 유실·거절·지연 구분, 실행 절차의 단계 구성, 결과 템플릿.

---

## 8. 만들어야 할 산출물

| # | 상태 | 파일 | 내용 |
|---|---|------|------|
| ① | ✅ | `commerce/.../order/infrastructure/adapter/out/bench/BenchOrderUserAdapter.java` | `@Profile("bench") @Primary`, `OrderUserPort` 구현. 고정 `UserInfo` 반환 (I/O 없음) |
| ② | ✅ | `commerce/.../out/bench/BenchOrderProductAdapter.java` | `OrderProductPort` 구현. 5,000원 고정 상품 반환 |
| ③ | ✅ | `commerce/src/main/resources/application-bench.yml` | 접속 정보는 환경변수 오버라이드(기본: compose 서비스명 `mysql`/`kafka:9090`) + Eureka off + ES/AI autoconfigure exclude + Tomcat/Hikari 제약 + actuator prometheus + 스케줄러 off |
| ④ | ✅ | `payments/src/main/resources/application-bench.yml` | **MySQL**(H2 아님) + 동일 제약 + prometheus |
| ⑤ | ✅ | `OrderApplication` + `OrderPostProcessPort` 전략 | `order.postprocess.strategy=sync\|kafka`. 기존 `OrderPaymentPort` 와 같은 전략 패턴으로 `OrderPostProcessPort` + Sync/Kafka 어댑터 2개 신설 |
| ⑥ | ❌ | `k6-scripts/order-burst.js` | `ramping-arrival-rate`, `POST /api/commerce/order/{code}`, `X-CODE` 랜덤 유저, 단계별 태그 (`stage:baseline\|burst\|recover`) |
| ⑦ | ❌ | `k6-scripts/seed-bench.sql` | `BENCH-0..9999` 예치금 계정 + 잔액 10억, **그리고 유저별 `cart` + `cartItem`** — Arm A 는 장바구니가 없으면 `CART_NOT_FOUND` 로 주문이 실패한다 |
| ⑧ | ❌ | `k6-scripts/reset-bench.sql` | `Orders`/`OrderItem`/`Payment`/`DepositHistoryEntity` truncate + 잔액 복원 |
| ⑨ | ❌ | `k6-scripts/verify-bench.sql` | §4.4 검증 쿼리 묶음 |
| ⑩ | ❌ | `k6-scripts/lag-monitor.sh` 수정 | group id 인자화 + **컨테이너명 `bench-kafka`, 부트스트랩 `kafka:9090`** 으로 수정 (현재 스크립트는 `kafka` / `localhost:9092` 를 쓴다) |
| ⑪ | ✅ | `commerce/build.gradle` + `application-bench.yml` | **actuator + micrometer-registry-prometheus 추가**, `percentiles-histogram` 활성화 (§6.2-①). `prometheus.yml` 타깃은 이미 등록돼 있음 |
| ⑫ | ✅ | `docker/docker-compose-bench.yml` | prometheus command 에 `--web.enable-remote-write-receiver` 추가 (k6 지표 수집, §6.2-②) |
| ⑬ | ❌ | `docker/grafana/.../bench-dashboard.json` | 기존 대시보드 복사 → `$service` 변수화 + Tomcat/k6/lag 패널 추가 (§6.2-③) |
| ⑭ | ❌ | `k6-scripts/run-arm.sh` | 회차 래퍼 — 시각 기록 + lag 모니터 + k6 + 검증 SQL 자동 실행 (§6.6) |
| ⑮ | ✅ | `claude-kafka-test-결과.md` | 측정 결과 + 이력서 문장 (2026-08-27) |

> ⑥ k6 스크립트에서 **stage 태그**를 붙이는 게 중요하다.
> `http_req_duration{stage:burst}` 로 버스트 구간만 뽑아야 baseline 에 희석되지 않은 p99가 나온다.

---

## 9. JUnit 통합 테스트로도 남길 것 (재현성)

k6 결과는 이력서용, JUnit 은 **"CI에서 회귀를 잡는다"** 용. 이미 기반이 있다.

| 기존 자산 | 재사용 방법 |
|-----------|------------|
| `commerce/src/test/.../FullStackE2ETest.java` | Testcontainers MySQL+Kafka 위에 payments+commerce 동시 기동. **Arm 별 프로퍼티만 바꿔 반복하는 파라미터화 테스트로 확장** |
| `commerce/src/test/.../support/CommerceIntegrationTestBase.java` | `@DynamicPropertySource` 로 컨테이너 연결 — bench 프로퍼티 추가 |
| `commerce/src/test/.../OrderPostProcessingE2ETest.java` | ALL SYNC vs Sync+Kafka 응답 시간 비교의 **원형**. 시뮬레이션 대신 실제 경로를 타도록 개선 |

추가 권장 테스트 2개:
1. **정합성 테스트** — Arm C 로 200건 주문 → Awaitility 로 전건 PAID 대기 → 잔액/Payment/DepositHistory 검증 (이중 차감 회귀 방지)
2. **내구성 테스트** — 발행 도중 consumer 컨테이너 정지 → 재시작 → 유실 0 확인

> 주의: JUnit 안에서는 Testcontainers 오버헤드와 단일 JVM 공유 때문에
> **절대 수치가 왜곡된다.** 이력서에 쓸 숫자는 반드시 k6 + 별도 프로세스 기동 결과로 뽑을 것.

---

## 10. 결과 기록 템플릿

```markdown
## 환경
- 머신: macOS / Docker Desktop (CPU [ ] / RAM [ ])
- commerce, payments: JDK 21, -Xms512m -Xmx512m G1GC, 각각 별도 프로세스
- MySQL 8.0 (컨테이너), Kafka KRaft 단일 브로커, 파티션 [ ]
- Tomcat max-threads 50 / accept-count 100, HikariCP max 10 / timeout 3s
- 유저 풀: BENCH-0..9999, 초기 잔액 1,000,000,000원, 상품가 5,000원
- 부하: baseline 20 TPS×3m → ramp 30s → burst [T] TPS×3m → recover 20 TPS×2.5m

## 예비 측정 (동기 임계점)
| 목표 TPS | 실제 TPS | p99 (ms) | 에러율 | Hikari pending |
|---|---|---|---|---|
| 50 | | | | |
| 100 | | | | |
| ... | | | | |
→ 임계점 T = [ ] TPS, 본 테스트 burst = [ ] TPS

## Arm 별 결과
| 지표 | A ALL SYNC | A′ SYNC개선 | B SYNC+Kafka | C FULL KAFKA |
|---|---|---|---|---|
| baseline p50/p95/p99 (ms) | | | | |
| **burst p50/p95/p99 (ms)** | | | | |
| burst 에러율 (%) | | | | |
| dropped_iterations | | | | |
| 실제 처리 TPS | | | | |
| Hikari pending 최대 | | | | |
| Tomcat busy 최대 | | | | |
| 최대 consumer lag | – | – | | |
| drain 시간 (s) | – | – | | |
| **완결 시간 p50/p95/p99 (ms)** | | | | |
| PENDING 잔류 | | | | |
| 유실 (성공응답 vs PAID) | | | | |
| 잔액 정합성 | | | | |

## 파티션 수 실험 (Arm C)
| 파티션 | 최대 lag | drain (s) | 완결 p99 (ms) | 응답 p99 (ms) |
|---|---|---|---|---|
| 1 | | | | |
| 3 | | | | |
| 6 | | | | |

## 그래프
- [ ] burst 구간 p99 시계열 (4 Arm 오버레이)
- [ ] hikaricp_connections_pending (A vs C)
- [ ] consumer lag 증가·drain 곡선

## 결론
- 얻은 것: [ ]
- 잃은 것: [ ]
- Kafka가 아니어도 됐던 부분: [ ] (A → A′ 개선분)
- Kafka여야 했던 부분: [ ]
```

---

## 11. 이력서 / 면접용 정리

### 이력서 문장 초안 (측정 후 숫자 채우기)

> **주문·결제 파이프라인 이벤트 기반 재설계 (Kafka)**
> - 결제 후처리(주문 완료·장바구니 정리·상품 상태)를 Kafka 이벤트로 분리, 결제 응답 p95 **[A]ms → [B]ms**
> - 정상 대비 20배 스파이크(20 → [T] TPS) 부하에서 동기 방식은 요청 거절률 **[X]%**, 이벤트 기반은 **0%** — 큐 버퍼링으로 자원 고갈 구간 흡수
> - 트레이드오프인 최종 일관성 지연을 **완결 시간 p99 [Y]ms**로 측정하고, Saga 보상 트랜잭션·DLT로 유실 0 검증
> - 성능 저하 원인을 트랜잭션 경계 문제와 아키텍처 문제로 분리 진단 — 전자는 트랜잭션 밖 호출로 해결(**[Z]% 개선**), 후자만 Kafka로 전환

### 예상 반박과 답변

| 질문 | 답변 근거 |
|------|----------|
| "그냥 스레드풀·커넥션풀 늘리면 되는 거 아닌가?" | 예비 측정 계단표. 풀을 키우면 임계점이 뒤로 밀릴 뿐 DB 커넥션이라는 상한이 있고, 그 지점에서 응답 시간이 비선형으로 붕괴함을 보임 |
| "Kafka 없이 `@Async` 로도 되지 않나?" | Arm A′ 결과 + "인메모리 큐는 프로세스 죽으면 유실"을 **내구성 실험(단계 7)** 으로 증명 |
| "비동기면 사용자가 결제 실패를 어떻게 아나?" | Saga 보상 경로(`PaymentCreatedFailed` → `NotifyOrderCreateFailedAlertCommand` → 주문 CANCELLED)와 완결 시간 p99 수치 제시. 실제 서비스라면 알림/폴링 필요함을 인정 |
| "최종 일관성 지연이 결제에 허용되나?" | **허용 안 되는 케이스를 구분했다**고 답변: 예치금 차감은 `SELECT FOR UPDATE` 로 동기 원자 처리(Arm B), 후처리만 비동기. Arm C는 실험적 비교군 |
| "왜 파티션 1개였나?" | 파티션 실험 표로 답변. 순서 보장(키=orderCode)과 처리량의 트레이드오프를 이해하고 있음을 보임 |
| "중복 처리는?" | `enable.idempotence=true`(producer) + `Payment.orderCode` UNIQUE + DLT. consumer 멱등 테이블은 미구현 — **한계로 솔직히 언급** |

### 하지 말아야 할 주장
- ❌ "Kafka를 도입해서 성능이 N배 좋아졌다" → 처리량이 아니라 **응답 시간과 생존성**이 좋아진 것. 전체 처리 용량은 오히려 오버헤드로 약간 줄 수 있다
- ❌ "유실이 없어졌다" → 동기 방식도 유실은 없다(거절이 있을 뿐). **"거절 없이 흡수했다"** 가 정확한 표현
- ❌ 로컬 단일 브로커 측정치를 운영 수치처럼 제시 → 환경 정보를 반드시 병기

---

## 12. 우선순위 (시간이 없다면)

| 순위 | 작업 | 이유 |
|------|------|------|
| ~~0~~ | ~~**P0-7 락 임계 구간 수정**~~ | ✅ **완료 (2026-08-26)** — 50.7s/500 → 0.09s/200, 동시 100건 성공률 60% → 100% |
| 1 | **P0-6 관측 복구** — commerce actuator + `percentiles-histogram` (⑪) | 이게 없으면 임계점이 발생하는 쪽이 안 보여 **측정 자체가 성립 안 함** |
| 2 | P0-1 이중 차감 수정 | 안 고치면 Arm C 데이터가 전부 무효 |
| 3 | bench 스텁 + bench 프로파일 (①②③④) + 스케줄러 off | 이게 없으면 측정 자체가 불가 |
| 4 | **스모크 런 7개 체크리스트 통과** (§6.7) | 2시간짜리 본 측정을 날리지 않기 위한 보험 |
| 5 | k6 → Prometheus remote write (⑫) | k6 지표와 서버 지표를 같은 시간축에 올려야 인과를 설명할 수 있음 |
| 6 | Arm A vs B 측정 (WIDE, 버스트) | **이력서 문장 하나는 여기서 나온다.** baseline에서 380/302 재현 여부도 여기서 확인 |
| 7 | 완결 시간 SQL 측정 | 트레이드오프 정량화 = 차별화 포인트 |
| 8 | Arm C 추가 | Saga 전체 이야기 완성 |
| 9 | Arm A′ 추가 | "무조건 Kafka가 아니다" 서사 → 면접 가산점 |
| 10 | Grafana bench 대시보드 (⑬) | 스크린샷은 포트폴리오에서 강력하지만, 없어도 수치는 나옴 |
| 11 | 파티션 실험 / 내구성 실험 / NARROW 풀 | 있으면 좋은 보너스 |

> 1~4번은 **측정 전 준비**다. 이 넷을 건너뛰고 부하부터 걸면 나온 숫자를 신뢰할 수 없다.

---

## 13. 진행 현황 (최종 갱신: 2026-08-26)

> 이 표는 **파일시스템·DB·실행 중인 컨테이너를 직접 확인해서** 채운다. 기억이나 추정으로 채우지 않는다.

### 13.1 한눈에

```
준비 단계  ██████████████████░░  9/11   측정 환경·관측·시나리오 코드
측정 단계  ██░░░░░░░░░░░░░░░░░░  1/6    예비 확인만, 본 측정 미착수
──────────────────────────────────────────
전체       ████████████░░░░░░░░  10/17
```

**지금 막고 있는 것: P0-1 (Kafka 경로 이중 차감).** 이것 때문에 Arm C 를 측정할 수 없다.

### 13.2 선결 과제 (§2)

| 항목 | 상태 | 확인 방법 |
|---|---|---|
| P0-1 Kafka 경로 이중 차감 | ❌ **미수정** | `OrderSaga` 가 여전히 `publishPaymentSuccessToDeposit` 발행. 실측: 5,000원 상품 → 10,000원 차감 |
| P0-2 트랜잭션 밖 결제 (Arm A′) | ❌ 미구현 | `OrderApplication` 에 `tx-boundary` 스위치 없음 |
| P0-3 user/product 스텁 | ✅ | `BenchOrder{User,Product}Adapter` 존재, 주문 204 확인 |
| P0-4 `Payment.orderCode` UNIQUE | ❌ **미적용** | `SHOW INDEX FROM Payment` 에 유니크 인덱스 없음 |
| P0-5 payments MySQL | ✅ | `application-bench.yml` 이 MySQL 드라이버 사용 |
| P0-6 관측 복구 | ✅ | Prometheus 타깃 4개 `up=1`, 버킷 148개 |
| P0-7 락 임계 구간 | ✅ | 50.7s/500 → 0.09s/200, 동시 100건 성공률 100% |

> P0-7 은 **계획에 없던 항목**이다. 환경을 만들다 발견해서 추가했다.

### 13.3 산출물 (§8)

| 상태 | 항목 |
|---|---|
| ✅ 완료 (8) | ① 사용자 스텁 · ② 상품 스텁 · ③④ bench 프로파일 · ⑤ 후처리 전략 · ⑪ actuator · ⑫ compose · ⑮ 결과 문서 |
| ❌ 미착수 (7) | ⑥ `order-burst.js` · ⑦ `seed-bench.sql` · ⑧ `reset-bench.sql` · ⑨ `verify-bench.sql` · ⑩ `lag-monitor.sh` 수정 · ⑬ 대시보드 · ⑭ `run-arm.sh` |

> ⑩ 은 파일은 있으나 `docker exec kafka` / `localhost:9092` 를 써서 **현재 환경에서 동작하지 않는다.**

### 13.4 우선순위 대비 진척 (§12)

| 순위 | 작업 | 상태 |
|---|---|---|
| 1 | P0-6 관측 복구 | ✅ |
| 2 | P0-1 이중 차감 수정 | ❌ ← **다음 차례** |
| 3 | bench 스텁 + 프로파일 + 스케줄러 off | ✅ |
| 4 | 스모크 런 7개 체크리스트 (§6.7) | ⚠️ 개별 항목은 확인했으나 **정식 회차로는 미실시** |
| 5 | k6 → Prometheus remote write | ⚠️ compose 플래그만 적용, k6 스크립트 없음 |
| 6 | Arm A vs B 측정 | ⚠️ **예비만** (순차 10건). 본 측정 미착수 |
| 7 | 완결 시간 SQL 측정 | ✅ 산출 확인 (A 16.1ms / B 225.9ms) |
| 8 | Arm C | ❌ P0-1 에 막힘 |
| 9 | Arm A′ | ❌ |
| 10 | Grafana bench 대시보드 | ❌ |
| 11 | 파티션 / 내구성 / NARROW 실험 | ❌ |

### 13.5 지금까지 확보한 실측값

| 측정 | 값 | 성격 |
|---|---|---|
| P0-7 수정 효과 (단건) | 50.71s/500 → **0.09s/200** | ✅ 확정 — 이력서에 쓸 수 있음 |
| P0-7 수정 효과 (동시 100건) | 성공률 60% → **100%**, 이력 누락 19건 → 0 | ✅ 확정 |
| 읽기 경로 부하 (100 TPS) | p95 289ms, Hikari 9/10 | ✅ 확정 — 임계점 T 추정 근거 |
| Arm A vs B 체감 응답 | 37.3ms vs 52.9ms | ⚠️ **예비** — 표본 10건, 결론 불가 |
| Arm A vs B 완결 시간 | 16.1ms vs **225.9ms** | ⚠️ 예비 — 방향성은 명확 |
| P0-1 이중 차감 | 5,000원 → 10,000원 차감 | ✅ 확정 (버그 재현) |

### 13.7 부하 비교 실측 (2026-08-26) — "왜 Kafka인가"

동시 60 / 총 600건, 유저 200명, 결제는 양쪽 다 동기 Feign. **후처리 방식만 다름.**

| 지표 | A 동기 후처리 | B Kafka 후처리 | 차이 |
|---|---:|---:|---|
| 처리량 | 49.6 TPS | **65.2 TPS** | **+31%** |
| 총 소요 | 12,078ms | **9,199ms** | −24% |
| p50 | 1,086ms | **884ms** | −19% |
| **p95** | 1,662ms | **1,380ms** | **−17%** ← **이력서 대표 수치** |
| p99 | 2,379ms | 1,741ms | −27% (표본 600건 → 상위 6건이 결정, 참고용) |
| 실패 | 0 | 0 | — |
| Hikari pending 최대 | 39 | 45 | — |
| **응답 직후 완료** | **600 / 600** | **48 / 600** | 비동기임이 드러남 |
| **완결 시간 avg** | **167ms** | **6,977ms** | **42배** |

**해석:** 후처리를 큐로 빼면 요청당 커넥션 점유 시간이 줄어 **같은 자원으로 31% 더 처리**한다.
대가는 최종 일관성 지연 42배. 저부하(순차 10건)에서는 오히려 Kafka 가 느렸으므로
(§13.5), **Kafka 의 이점은 부하가 몰릴 때만 발생한다**는 것이 실측으로 확인됐다.

**한계:** 폐쇄 루프(closed-loop) 부하라 요청이 시스템 속도에 끌려간다.
따라서 "거절률 0%" 는 이 회차로 증명되지 않았다 — 양쪽 다 실패 0이었다.
거절을 보려면 도착률 고정(k6 `ramping-arrival-rate`) 부하가 필요하다.

### 13.6 다음 3개

1. **P0-1 이중 차감 수정** — Arm C 를 막고 있는 유일한 블로커
2. **P0-4 `Payment.orderCode` UNIQUE** — 무손실 검증이 오염되지 않게
3. **⑦⑧⑨ SQL 3종 + ⑥ k6 스크립트** — 여기까지 되면 스모크 런(§6.7) 착수 가능

---

