# 결제 비동기화 — 테스트 요약

> 구현: [`claude-결제-비동기화-구현.md`](./claude-결제-비동기화-구현.md) · 설계: [`claude-결제-비동기화-설계안.md`](./claude-결제-비동기화-설계안.md) · 측정: [`claude-결제-비동기화-측정.md`](./claude-결제-비동기화-측정.md)
> 브랜치 `feat/async-payment-kafka` · **자동화 53건 전부 통과 (실패 0 / 오류 0)** + bench 환경 end-to-end 수동 검증

```bash
./gradlew :commerce:test --tests "io.devground.dbay.order.*"      # 30건 / 22초
./gradlew :payments:test --tests "io.devground.payments.payment.*" # 10건 / 6초
```

**전부 Docker 없이 돈다.** 두 층으로 나뉜다:

| 층 | 방식 | 지키는 것 | 속도 |
|---|---|---|---|
| **단위 45건** | Mockito · H2 `@DataJpaTest` | **판단 로직** — 실패 분류, 멱등 분기, 회수 판정 | 0.5초 |
| **메시징 8건** | `@EmbeddedKafka` + H2 | **배선** — 타입 라우팅, 직렬화, 커밋 시점 | 19초 |

기존 통합 테스트가 쓰는 testcontainers 는 피했다 — 벤치 스택이 떠 있으면 포트가 충돌하고, Docker 없이는 아예 못 돈다.

여기에 더해 전 구간 흐름은 **bench 환경에서 실제로 돌려 확인했다** — §7.

---

## 0. 무엇을 지키려고 쓴 테스트인가

비동기 결제에서 값비싼 버그는 셋뿐이다. 53건은 전부 이 셋을 막는 데 쓰였다.

| # | 사고 | 막는 테스트 |
|---|---|---|
| **A** | **돈이 빠진 주문을 취소한다** | §3 회수 판정 5건 · §4 가드 쿼리 3건 |
| **B** | 중복 이벤트로 후처리가 두 번 나간다 | §2 멱등성 2건 · §4 가드 쿼리 1건 |
| **C** | 접수 경로가 여전히 무겁다 (비동기의 목적 자체가 무산) | §1 전략 분기 3건 · §5 사전 확인 1건 |
| **D** | **메시지가 오가지 않는다** (라우팅·직렬화가 조용히 깨짐) | §6 메시징 8건 |

---

## 1. 파일별 구성 (6개)

| 파일 | 건수 | 종류 | 대상 |
|---|---:|---|---|
| `commerce/…/order/domain/OrderPaymentStateTest` | 5 | 순수 단위 | 도메인 상태 전이 |
| `commerce/…/order/service/OrderApplicationAsyncPaymentTest` | 22 | Mockito | 전략 분기 · 멱등성 · 회수 판정 · 사전 확인 · 폴링 |
| `commerce/…/order/persistence/OrderPaymentStateQueryTest` | 11 | `@DataJpaTest` (H2) | 조건부 UPDATE · 폴링 쿼리 |
| `payments/…/payment/saga/PaymentKafkaHandlerAsyncPaymentTest` | 7 | Mockito | 결제 커맨드 소비 · 실패 분류 |
| `commerce/…/order/kafka/AsyncPaymentMessagingTest` | 5 | `@EmbeddedKafka` | 발행 시점 · 역직렬화 · 타입 라우팅 |
| `payments/…/payment/kafka/PaymentCommandMessagingTest` | 3 | `@EmbeddedKafka` | 커맨드 소비 · 실제 차감 · 멱등키 |

---

## 2. `OrderPaymentStateTest` — 5건

도메인 상태 전이. **핵심은 `PENDING` 과 `PAYMENT_PENDING` 이 섞이지 않는다는 것**이다.

```java
assertThat(OrderStatus.PAYMENT_PENDING.isAwaitingPayment()).isTrue();
assertThat(OrderStatus.PENDING.isAwaitingPayment()).isFalse();   // ← 이게 이 파일의 핵심
```

`PENDING` 이 "결제 대기" 로 잡히면 회수 스케줄러가 **이미 결제된 주문을 전부 취소한다.**
`failPayment()` 가 `CANCELLED` 가 아닌 `PAYMENT_FAILED` 로 가는 것도 함께 고정했다 — 환불 보상 대상이 아니기 때문이다.

---

## 3. `OrderApplicationAsyncPaymentTest` — 22건

### 접수 시 전략 분기 (5건) — 사고 C

| 테스트 | 단언 |
|---|---|
| kafka 전략 → `PAYMENT_PENDING` 저장 + 커맨드 예약 | `ArgumentCaptor` 로 저장된 `Order` 의 상태 확인 |
| **kafka 전략 → 동기 Feign 결제를 호출하지 않는다** | `verifyNoInteractions(orderPaymentPort)` |
| kafka 전략 → 접수 시점에 후처리를 발행하지 않는다 | `verifyNoInteractions(kafkaPostProcess)` |
| feign 전략 → 기존 동기 경로 그대로, 주문은 `PENDING` | 회귀 방지 |

> 두 번째가 이 기능의 존재 이유다. 접수 경로에서 Feign 결제가 빠지지 않으면
> 스파이크 문서 §1 의 "반증 가능성"(①②가 무거우면 양쪽 다 거절)이 그대로 재현된다.

### 결제 결과 반영 (4건) — 사고 B

```java
when(orderPersistencePort.markPaidIfAwaitingPayment(ORDER))
        .thenReturn(true)     // 1회차: 전이 성공
        .thenReturn(false);   // 2회차: 이미 PAID

orderApplication.applyPaymentSuccess(...);   // 두 번 호출
orderApplication.applyPaymentSuccess(...);

verify(kafkaPostProcess, times(1)).completeOrder(...);   // 후처리는 한 번만
```

Kafka 는 at-least-once 라 중복 소비가 정상이다. 이게 깨지면 장바구니 정리·상품 상태 커맨드가 주문당 두 번씩 나간다.

### 회수 판정 (5건) — 사고 A ← **가장 중요**

| 상황 | 기대 동작 | 이유 |
|---|---|---|
| 결제 기록 없음 | 취소 | 커맨드 유실 — 차감 없으므로 안전 |
| **결제 `PAYMENT_COMPLETED`** | **취소하지 않고 `PAID` 복구 + 후처리 재개** | 결과 이벤트 유실 — **돈은 이미 나갔다** |
| 결제 있으나 미완료 | 취소 | 확정된 차감이 아님 |
| **조회 실패(예외)** | **그 건은 건드리지 않고 다음 건 계속** | 조회 실패를 "결제 없음" 으로 오판하면 사고 A |
| 대상 없음 | payments 를 호출하지 않음 | 불필요한 원격 호출 방지 |

```java
// "결제 완료" 케이스에서 이 단언이 깨지면 돈이 빠진 주문을 취소하게 된다
verify(orderPersistencePort, never()).cancelIfAwaitingPayment(any());
```

조회 실패 케이스는 2건을 넣고 첫 건만 예외를 던져, **배치가 멈추지 않는지**와
**실패한 건은 손대지 않는지**를 동시에 확인한다.

### 잔액 사전 확인 (4건)

| 테스트 | 기대 |
|---|---|
| **기본은 꺼져 있어 예치금을 조회하지 않는다** | 접수 경로에 원격 호출을 늘리지 않는다는 것이 기본값이다 |
| 켜면 잔액 부족 시 주문을 만들지 않는다 | 대기 화면까지 갔다가 실패하는 경험을 없앤다 |
| 잔액이 충분하면 평소대로 진행 | 정상 경로 |
| **조회 실패는 주문을 막지 않는다** | 부가 확인이 payments 장애를 주문 전체로 전파하면 안 된다 |

### 결제 상태 폴링 (4건)

| 테스트 | 기대 |
|---|---|
| 대기 중이면 `awaitingPayment=true` | 클라이언트가 폴링을 계속할 신호 |
| 확정되면 `false` | 폴링 종료 |
| 결제 실패도 확정이다 | 실패 화면을 띄우고 멈춰야 한다 |
| **남의 주문은 조회되지 않는다** | 주문 코드가 UUID 라도 접근 제어를 대신할 수 없다 |

접수 반환값도 함께 고정했다 — 비동기는 주문 코드와 `awaitingPayment=true`, **동기는 `false`**(컨트롤러가 기존과 같은 204 를 내야 하므로).

---

---

## 4. `OrderPaymentStateQueryTest` — 11건 (H2)

**멱등성이 실제로 사는 곳은 응용 계층 분기가 아니라 이 조건부 UPDATE 다.**

```sql
UPDATE Orders SET orderStatus='PAID'
WHERE code = :orderCode AND orderStatus = 'PAYMENT_PENDING'
```

| 테스트 | 단언 |
|---|---|
| `PAYMENT_PENDING` → `PAID`, 1행 갱신 | 정상 경로 |
| **두 번째 호출은 0행** | §3 멱등성의 DB 레벨 근거 |
| `PENDING` 주문은 건드리지 않음 | 두 상태 분리 검증 |
| `CANCELLED` 주문은 되살아나지 않음 | 늦게 도착한 성공 이벤트 방어 |
| 실패 전이도 `PAYMENT_PENDING` 일 때만 | `PAID` 주문이 실패로 뒤집히지 않음 |
| 회수 취소도 `PAYMENT_PENDING` 일 때만 | 사고 A 방어 |
| 회수 대상 조회는 `PAYMENT_PENDING` 만 | 상태 필터 |
| 배치 크기 제한이 걸린다 | 커넥션 경합 방지 |
| 시한 전 주문은 대상 아님 | `createdAt` 컷오프 |
| 폴링 조회가 상태·소유자를 함께 돌려준다 | 프로젝션 쿼리 검증 |
| 없는 주문 코드는 빈 결과 | 404 로 이어지는 경로 |

## 5. `PaymentKafkaHandlerAsyncPaymentTest` — 7건

결제 커맨드 소비. **실패를 어떻게 나누느냐**가 전부다 — commerce 는 이 이벤트만 보고 보상 필요 여부를 판단한다.

| 테스트 | 기대 |
|---|---|
| 성공 → `OrderPaymentCompleted` 발행 | 파티션 키가 `userCode` 인 것도 함께 확인 |
| 동기 경로와 **같은 `process()`** 를 호출 | `PaymentConfirmRequest` 내용까지 캡처해 검증 |
| 잔액 부족 → `INSUFFICIENT_BALANCE` | 차감 없음 → 보상 불필요 |
| 예치금 없음 → `DEPOSIT_NOT_FOUND` | 차감 없음 → 보상 불필요 |
| **중복 커맨드 → `Completed` 재발행** | 실패로 처리하면 정상 주문이 취소된다 |
| unique 위반인데 기존 결제 없음 → `INTERNAL_ERROR` | 회수 대상으로 넘김 |
| **분류 안 된 예외 → 삼키지 않고 전파** | 재시도 → DLT |

마지막 건이 중요하다:

```java
assertThatThrownBy(() -> handler.handleEvent(command)).isInstanceOf(RuntimeException.class);
verify(kafkaTemplate, never()).send(eq(EVENT_TOPIC), anyString(), any());
```

여기서 실패 이벤트를 내보내면 **"차감됐는데 실패로 확정"** 이 된다. 모르면 확정하지 않고 DLT 로 보내는 게 맞다.

---

## 6. 메시징 테스트 8건 (`@EmbeddedKafka`) — 사고 D

**단위 테스트가 구조적으로 못 보는 구간이다.** 단위 테스트는 핸들러를 이렇게 부른다:

```java
handler.handleEvent(command);   // ← 자바 메서드 호출. Kafka 가 개입하지 않는다
```

운영에서는 그 사이에 직렬화 → 브로커 → 역직렬화 → `@KafkaHandler` 타입 분배가 있다.
셋 다 **조용히** 깨지고 증상은 "결제가 안 되고 DLT 만 쌓임" 이라 늦게 발견된다.

`@EmbeddedKafka` 는 JVM 안에 브로커를 띄운다 — Docker 불필요. `spring-kafka-test` 는 이미 두 모듈 의존성에 있었다.

### commerce 쪽 5건 — `AsyncPaymentMessagingTest`

| 테스트 | 지키는 것 |
|---|---|
| 접수하면 결제 커맨드가 실제 토픽에 실린다 | **키가 `userCode`** 이고 `__TypeId__` 헤더가 `PaymentRequestedCommand` 인지까지 확인 — 헤더가 없으면 payments 가 분배하지 못한다 |
| **커밋되지 않으면 커맨드가 나가지 않는다** | `AFTER_COMMIT` 검증. 아래 설명 |
| 성공 이벤트 → `PAID` | 역직렬화 + 라우팅 |
| **실패 이벤트 → `PAYMENT_FAILED`** | 같은 토픽·같은 클래스에서 **타입만 다르다.** 라우팅이 어긋나면 성공으로 처리되거나 DLT 로 간다 |
| 중복 성공 이벤트에도 `PAID` 유지 | at-least-once 대응이 실제 경로에서도 성립하는가 |

`AFTER_COMMIT` 검증 방식이 이 파일에서 제일 깔끔하다:

```java
@Test
@Transactional   // ← 테스트 트랜잭션은 끝에 롤백된다
void 커밋되지_않으면_커맨드가_나가지_않는다() {
    String orderCode = orderApplication.createOrderByOne(USER, PRODUCT).orderCode();
    assertThat(findCommandFor(orderCode)).isEmpty();   // 커밋이 없으니 발행도 없어야 한다
}
```

발행이 메서드 안에 있었다면 메시지가 나가서 이 단언이 깨진다. **커밋 전 발행을 직접 잡아내는 테스트다.**

### payments 쪽 3건 — `PaymentCommandMessagingTest`

| 테스트 | 지키는 것 |
|---|---|
| 커맨드 → 차감 + 성공 이벤트 발행 | `PaymentKafkaHandler` 가 **5번째 타입**을 올바로 받는가 (기존 4개와 섞이지 않는가) |
| 잔액 부족 → 차감 없이 `INSUFFICIENT_BALANCE` | 보상 불필요 분류가 실제 경로에서 성립 |
| **중복 커맨드 → 한 번만 차감** | `orderCode` unique 제약이 실제로 이중 차감을 막는가. H2 는 `create-drop` 이라 제약이 실제로 걸린다 |

마지막 건이 특히 의미 있다 — 단위 테스트는 `DataIntegrityViolationException` 을 **모의**했을 뿐이고, 여기서는 **DB 제약이 진짜로 두 번째를 튕겨내는지** 본다. `6a6add3` 에서 잡았던 이중 차감(5,000원 상품에 10,000원)과 같은 증상을 회귀 방지한다.

### 테스트끼리 브로커를 공유한다 — 밟은 함정

`getSingleRecord` 를 쓰면 다른 테스트가 남긴 레코드까지 읽혀 **"More than one record"** 로 엉뚱하게 실패한다.
그래서 각 테스트가 **자기 `orderCode` 를 포함한 레코드만 골라낸다**(`findCommandFor` / `findEventFor`).

---

## 7. end-to-end 수동 검증 (bench 환경)

§6 이 각 모듈의 배선을 검증한다면, 여기서는 **commerce ↔ Kafka ↔ payments 전 구간**을 실제 MySQL 위에서 확인했다. 상세 수치는 구현 문서 §9.

| 시나리오 | 결과 |
|---|---|
| 성공 경로 | 202(178ms) → 폴링 8회 → `PAID`. 예치금 정확히 5,000 차감, 이력 1건, 장바구니 정리 완료 |
| 실패 경로(잔액 부족) | 202 접수 → 폴링 1회 → `PAYMENT_FAILED`. **잔액 그대로, 결제 레코드 0건** |
| 사전 확인 ON | 400 "잔액이 부족합니다." 즉시 거절, 주문 레코드 생성 안 됨 |
| 동기 경로 회귀 | 204 / 본문 0 bytes → `PAID`. 기존 계약 그대로 |
| DLT 4개 토픽 · 컨슈머 lag | **전부 0** |

### 이 과정에서 실제로 잡은 결함 3건

1. **`Orders.orderStatus` ENUM 에 새 값이 없어 주문 INSERT 가 전부 500** — `ddl-auto: update` 가 기존 ENUM 컬럼을 바꾸지 못한다. → `bench-scripts/migrate-async-payment.sql` 추가
2. **`Payment.orderCode` unique 제약이 생성되지 않음 (조용히)** — 멱등키가 없는 채로 동작할 뻔했다. 같은 마이그레이션에 포함
3. **잔액 부족이 400 이 아니라 500** — order 응용 계층 예외가 `GlobalExceptionHandler` 에 안 걸린다. 내 코드 안에서 core `ErrorCode` 로 우회(구현 문서 §11-⑤)

> 셋 다 **단위 테스트로는 절대 안 잡히는 종류**다. 스키마와 예외 매핑은 실제 스택을 띄워야 드러난다.

---

## 8. 여전히 덮지 못한 것

| 항목 | 왜 안 했나 | 어떻게 확인해야 하나 |
|---|---|---|
| **동시 중복 커맨드** | §6 은 순차 2건이다 | 진짜 경합에서 unique 제약이 버티는지 — `PaymentProcessIntegrationTest` 의 동시성 패턴 재사용 |
| **회수 스케줄러 실동작** | bench 에서 `reconcile-cron: "-"` 으로 꺼둔다 | 결과 이벤트를 인위로 유실시키고 시한 뒤 복구 확인 |
| **DLT 적재 경로** | 정상 경로만 봤다 | 역직렬화 실패·재시도 소진 시 실제로 DLT 로 가는지 |
| **폴링 부하** | 미측정 | 폴링이 총 요청 수를 3~4배로 늘린다. A/B 측정에 반드시 포함할 것 |
| **MySQL ENUM 스키마** | H2 는 varchar 라 문제가 안 드러난다 | 이것 때문에 bench 에서 500 이 났다(§7). 마이그레이션 실행 여부는 사람이 확인해야 한다 |

---

## 9. 다음에 할 것

1. ~~벤치 스크립트 수정~~ — 완료. `bench_dbstat`·`verify.sql` 이 `PAYMENT_PENDING` 을 분리 집계한다
2. ~~지속 부하 A/B~~ — 완료([측정 문서](./claude-결제-비동기화-측정.md))
3. **스파이크 A/B** — 접수 경로가 가벼워져 기각됐던 가설을 다시 볼 조건이 됐다
4. **폴링 부하 포함 측정** — 이번 회차에는 폴링을 넣지 않았다
