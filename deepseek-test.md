# deepseek-test.md — 변경 파일 목록 및 테스트 가이드

> 마지막 수정: 2026-07-01

---

## 1. 변경 파일 전체 목록

### 1.1 신규 생성 (6개)

| # | 파일 | 모듈 | 설명 |
|---|------|------|------|
| 1 | `commerce/src/main/java/io/devground/dbay/order/application/port/out/payment/OrderPaymentPort.java` | commerce | 결제 전략 인터페이스 |
| 2 | `commerce/src/main/java/io/devground/dbay/order/application/port/out/payment/PaymentResult.java` | commerce | 결제 결과 공통 응답 record |
| 3 | `commerce/src/main/java/io/devground/dbay/order/infrastructure/adapter/out/payment/PaymentFeignClient.java` | commerce | OpenFeign 클라이언트 (commerce → payments) |
| 4 | `commerce/src/main/java/io/devground/dbay/order/infrastructure/adapter/out/payment/FeignPaymentAdapter.java` | commerce | TO-BE 동기 결제 어댑터 (`@Qualifier("feignPaymentAdapter")`) |
| 5 | `commerce/src/main/java/io/devground/dbay/order/infrastructure/adapter/out/payment/KafkaPaymentAdapter.java` | commerce | AS-IS Kafka 어댑터, 벤치마크 비교용 (`@Qualifier("kafkaPaymentAdapter")`) |
| 6 | `payments/src/main/java/io/devground/payments/payment/event/PaymentCompletedEvent.java` | payments | 예치금 이력 저장 트리거용 Spring 이벤트 |

### 1.2 수정 (5개)

| # | 파일 | 모듈 | 변경 내용 |
|---|------|------|----------|
| 7 | `payments/src/main/java/io/devground/payments/payment/service/PaymentServiceImpl.java` | payments | `process()` 를 `@Transactional` 원자 처리로 재작성. `DepositFeignClient` → `DepositPersistencePort` 직접 주입. Kafka 이벤트 발행 제거. 예치금 이력은 `@TransactionalEventListener(AFTER_COMMIT)` 으로 분리 |
| 8 | `payments/src/main/java/io/devground/payments/payment/saga/PaymentKafkaHandler.java` | payments | `CompletePaymentCommand`, `CancelCreatePaymentCommand` 핸들러 제거. `PaymentCreateCommand` 는 Kafka 비교 전략용으로 유지 |
| 9 | `commerce/src/main/java/io/devground/dbay/order/application/service/OrderApplication.java` | commerce | `OrderPaymentPort` 전략 주입. `order.payment.strategy` 설정값으로 `FeignPaymentAdapter` / `KafkaPaymentAdapter` 분기. 결제 성공 시 후속 Kafka 직접 발행 |
| 10 | `commerce/src/main/java/io/devground/dbay/order/application/port/out/kafka/OrderKafkaEventPort.java` | commerce | 인터페이스 정리, 각 메서드의 용도 주석 추가 |
| 11 | `commerce/src/main/resources/application.yml` | commerce | `order.payment.strategy`, `external.payment-url` 설정 추가 |

### 1.3 테스트 파일 (신규 3개)

| # | 파일 | 모듈 | 설명 |
|---|------|------|------|
| 12 | `payments/src/test/java/.../support/PaymentIntegrationTestBase.java` | payments | MySQL+Kafka Testcontainers 베이스 클래스 |
| 13 | `commerce/src/test/java/.../support/CommerceIntegrationTestBase.java` | commerce | MySQL+Kafka Testcontainers 베이스 클래스 |
| 14 | `payments/src/test/java/.../payment/service/PaymentProcessIntegrationTest.java` | payments | `process()` 원자 트랜잭션 + 동시성 통합 테스트 |

### 1.4 의존성 변경

| # | 파일 | 변경 |
|---|------|------|
| 15 | `commerce/build.gradle` | testcontainers 4종 의존성 추가 |
| 16 | `payments/build.gradle` | testcontainers 4종 의존성 추가 |

### 1.5 영향 없음 (변경하지 않은 파일)

- `commerce/.../order/infrastructure/adapter/in/kafka/OrderSaga.java` — Kafka 전략에서 계속 사용
- `commerce/.../order/infrastructure/adapter/in/kafka/OrderCommandConsumer.java` — 주문 완료/취소 커맨드 소비
- `commerce/.../order/infrastructure/adapter/out/event/OrderCreatedEventPublisher.java` — 환불 이벤트 유지
- `commerce/.../order/infrastructure/adapter/out/kafka/OrderKafkaEventPublisherAdapter.java` — Kafka 이벤트 발행 유지
- `payments/.../deposit/*` — 예치금 도메인 로직 변경 없음
- `payments/.../payment/config/PaymentKafkaConfig.java` — 토픽 정의 유지

---

## 2. 아키텍처 변경점 한눈에 보기

### AS-IS (변경 전)

```
OrderApplication
  → Spring ApplicationEvent → Kafka: PaymentCreateCommand
  → PaymentKafkaHandler.process()
    → DepositFeignClient.getBalance() [HTTP 호출]
    → Payment 저장
    → Kafka: PaymentCreatedEvent
  → OrderSaga
    → Kafka: WithdrawDeposit
  → DepositKafkaConsumer
    → 예치금 차감
    → Kafka: DepositWithdrawnSuccess
  → OrderSaga
    → Kafka: CompletePayment, CompleteOrder, DeleteCart, ProductSold
```

**문제**: Payment-Deposit 같은 DB, 같은 모듈인데 네트워크 4회 왕복 + 보상 트랜잭션 필요

### TO-BE (변경 후, `strategy: feign`)

```
OrderApplication
  → FeignPaymentAdapter (OpenFeign 동기)
  → PaymentController.process()
    → PaymentServiceImpl.process()  @Transactional
      ├─ deposit.getBalance()       [직접 조회, HTTP 아님]
      ├─ deposit.withdraw(amount)   [원자 처리]
      ├─ paymentRepository.save()
      └─ return PaymentDescription
    → @TransactionalEventListener(AFTER_COMMIT)
      └─ depositHistory.save()      [실패 무시]
  ← 동기 응답: 성공/실패
  → Kafka 직접 발행: CompleteOrder, DeleteCart, ProductSold
```

---

## 3. 컴파일 확인

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :commerce:compileJava :payments:compileJava
# → BUILD SUCCESSFUL
```

---

## 4. Testcontainers 통합 테스트 (MySQL + Kafka 자동 실행)

### 4.0 사전 준비

```bash
# Docker Desktop 실행 (필수)
# → Testcontainers 가 Docker 소켓을 통해 MySQL/Kafka 컨테이너를 자동으로 띄운다
docker ps  # Docker 가 실행 중인지 확인
```

### 4.1 Testcontainers 의존성

이미 `build.gradle` 에 추가되어 있다:

```groovy
// commerce/build.gradle + payments/build.gradle
testImplementation 'org.testcontainers:testcontainers:1.20.6'
testImplementation 'org.testcontainers:mysql:1.20.6'
testImplementation 'org.testcontainers:kafka:1.20.6'
testImplementation 'org.testcontainers:junit-jupiter:1.20.6'
```

### 4.2 테스트 베이스 클래스

두 모듈에 추상 베이스 클래스를 만들어뒀다:

```
payments/src/test/java/io/devground/payments/support/PaymentIntegrationTestBase.java
commerce/src/test/java/io/devground/dbay/support/CommerceIntegrationTestBase.java
```

이 클래스들이 하는 일:
- `@Testcontainers` + `@Container static` 으로 **MySQL 8.0** + **Kafka** 컨테이너를 테스트 클래스당 한 번씩 실행
- `@DynamicPropertySource` 로 Spring Boot 설정(`spring.datasource.*`, `spring.kafka.*`)을 컨테이너 주소로 자동 주입
- Eureka 비활성화, Kafka 토픽 자동 생성 활성화 등 테스트에 필요한 설정 적용

### 4.3 통합 테스트 실행

```bash
# Docker Desktop 실행 후

# payments 모듈 통합 테스트 (PaymentServiceImpl.process() 검증)
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew :payments:test --tests "*IntegrationTest"

# commerce 모듈 통합 테스트 (전략 패턴 검증) - TODO 추후 추가
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew :commerce:test --tests "*IntegrationTest"
```

### 4.4 통합 테스트 파일

```
payments/src/test/java/io/devground/payments/payment/service/
└── PaymentProcessIntegrationTest.java
    ├── process_sufficientBalance_deductsAndSavesAtomically()
    │       잔액 충분 → 예치금 차감 + 결제 저장 원자 처리 검증
    ├── process_insufficientBalance_rollsBackEverything()
    │       잔액 부족 → 전체 롤백 검증
    ├── process_historySaveAsync_doesNotAffectPayment()
    │       이력 저장 AFTER_COMMIT 분리 검증
    └── process_concurrentRequests_maintainsBalanceConsistency()
            동시 5건 결제 → 잔액 정합성 검증
```

### 4.5 H2 단위 테스트 vs Testcontainers 통합 테스트 구분

| 구분 | H2 단위 테스트 | Testcontainers 통합 테스트 |
|------|--------------|--------------------------|
| 클래스명 | `*Test.java` | `*IntegrationTest.java` |
| DB | H2 in-memory | MySQL 8.0 컨테이너 |
| Kafka | Mock/Embedded | 실제 Kafka 컨테이너 |
| 실행 시간 | < 1초 | 30~60초 (컨테이너 시작) |
| 용도 | 빠른 피드백 | 실제 환경 검증 |
| Docker | 불필요 | **필수** |

---

## 5. 단위 테스트 작성 가이드 (H2, Docker 불필요)

### 4.1 PaymentServiceImpl.process() — 원자 트랜잭션 테스트

```java
// payments/src/test/java/io/devground/payments/payment/service/PaymentServiceImplTest.java

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock private DepositPersistencePort depositPersistencePort;
    @Mock private DepositCommandPort depositCommandPort;
    @Mock private DepositHistoryCommandPort depositHistoryCommandPort;
    @Mock private PaymentRepository paymentRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Test
    @DisplayName("잔액 충분 → 예치금 차감 + 결제 저장 원자 처리")
    void process_success() {
        // given
        Deposit deposit = new Deposit("user1");
        deposit.charge(10000L); // 잔액 10,000원

        when(depositPersistencePort.getDepositByUserCode("user1"))
            .thenReturn(Optional.of(deposit));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentConfirmRequest request = new PaymentConfirmRequest(
            "order-123", true, 5000L, null, List.of("prod-1")
        );

        // when
        Payment result = paymentService.process("user1", request);

        // then
        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PAYMENT_COMPLETED);
        assertThat(deposit.getBalance()).isEqualTo(5000L); // 10000 - 5000
        verify(depositCommandPort).saveDeposit(deposit);
        verify(paymentRepository).save(any(Payment.class));
        verify(eventPublisher).publishEvent(any(PaymentCompletedEvent.class));
    }

    @Test
    @DisplayName("잔액 부족 → 예외 발생, 예치금 차감되지 않음")
    void process_insufficientBalance_throwsAndRollback() {
        // given
        Deposit deposit = new Deposit("user1"); // 잔액 0원
        when(depositPersistencePort.getDepositByUserCode("user1"))
            .thenReturn(Optional.of(deposit));

        PaymentConfirmRequest request = new PaymentConfirmRequest(
            "order-123", true, 5000L, null, List.of("prod-1")
        );

        // when & then
        assertThatThrownBy(() -> paymentService.process("user1", request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("예치금이 부족");

        // 예치금은 차감되지 않았어야 함
        assertThat(deposit.getBalance()).isEqualTo(0L);
        verify(depositCommandPort, never()).saveDeposit(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("예치금 이력 저장 실패 → 결제는 정상 처리, 예외 전파 안 됨")
    void recordDepositHistory_failure_doesNotAffectPayment() {
        // given
        Deposit deposit = new Deposit("user1");
        deposit.charge(10000L);
        when(depositPersistencePort.getDepositByUserCode("user1"))
            .thenReturn(Optional.of(deposit));
        when(depositHistoryCommandPort.saveDepositHistory(any()))
            .thenThrow(new RuntimeException("DB 장애"));

        PaymentCompletedEvent event = new PaymentCompletedEvent(
            "user1", deposit.getCode(), 5000L, 5000L, "order-123"
        );

        // when: AFTER_COMMIT 이벤트 핸들러 직접 호출
        assertThatCode(() -> paymentService.recordDepositHistory(event))
            .doesNotThrowAnyException(); // 예외가 밖으로 나가지 않음
    }
}
```

### 4.2 FeignPaymentAdapter — 통합 테스트

```java
// commerce/src/test/java/io/devground/dbay/order/infrastructure/adapter/out/payment/FeignPaymentAdapterTest.java

@ExtendWith(MockitoExtension.class)
class FeignPaymentAdapterTest {

    @Mock
    private PaymentFeignClient paymentFeignClient;

    @InjectMocks
    private FeignPaymentAdapter adapter;

    @Test
    @DisplayName("결제 성공 응답 → PaymentResult.success")
    void processPayment_success() {
        // given
        var response = new PaymentFeignClient.PaymentFeignResponse("order-1", "pay-abc");
        var baseResponse = BaseResponse.success(200, response, "결제 성공");

        when(paymentFeignClient.processPayment(eq("user1"), any()))
            .thenReturn(baseResponse);

        // when
        PaymentResult result = adapter.processPayment(
            "user1", "order-1", 5000L, List.of("prod-1")
        );

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.orderCode()).isEqualTo("order-1");
    }

    @Test
    @DisplayName("Feign 호출 예외 → PaymentResult.fail")
    void processPayment_feignException_returnsFail() {
        // given
        when(paymentFeignClient.processPayment(eq("user1"), any()))
            .thenThrow(new RuntimeException("Connection refused"));

        // when
        PaymentResult result = adapter.processPayment(
            "user1", "order-1", 5000L, List.of("prod-1")
        );

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Connection refused");
    }
}
```

### 4.3 KafkaPaymentAdapter — Kafka 발행 확인

```java
// commerce/src/test/java/io/devground/dbay/order/infrastructure/adapter/out/payment/KafkaPaymentAdapterTest.java

@ExtendWith(MockitoExtension.class)
class KafkaPaymentAdapterTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private KafkaPaymentAdapter adapter;

    @Test
    @DisplayName("Kafka 메시지 발행 → 즉시 PaymentResult.success 반환")
    void processPayment_sendsKafkaAndReturnsSuccess() {
        // given
        ReflectionTestUtils.setField(adapter, "paymentsCommandTopicName", "payments-purchase-commands");

        // when
        PaymentResult result = adapter.processPayment(
            "user1", "order-1", 5000L, List.of("prod-1")
        );

        // then
        assertThat(result.success()).isTrue();
        verify(kafkaTemplate).send(
            eq("payments-purchase-commands"),
            eq("order-1"),
            any(PaymentCreateCommand.class)
        );
    }
}
```

### 4.4 OrderApplication — 전략 패턴 테스트

```java
// commerce/src/test/java/io/devground/dbay/order/application/service/OrderApplicationTest.java

@ExtendWith(MockitoExtension.class)
class OrderApplicationTest {

    @Mock private OrderUserPort orderUserPort;
    @Mock private OrderProductPort orderProductPort;
    @Mock private OrderPersistencePort orderPersistencePort;
    @Mock private OrderPublishEventPort orderPublishEventPort;
    @Mock private OrderKafkaEventPort orderKafkaEventPort;
    @Mock @Qualifier("feignPaymentAdapter") private OrderPaymentPort feignPaymentAdapter;
    @Mock @Qualifier("kafkaPaymentAdapter") private OrderPaymentPort kafkaPaymentAdapter;

    private OrderApplication orderApplication;

    @BeforeEach
    void setUp() {
        // feign 전략으로 생성
        orderApplication = new OrderApplication(
            orderUserPort, orderProductPort, orderPersistencePort,
            orderPublishEventPort, orderKafkaEventPort,
            feignPaymentAdapter, kafkaPaymentAdapter
        );
        ReflectionTestUtils.setField(orderApplication, "paymentStrategy", "feign");
    }

    @Test
    @DisplayName("Feign 전략: 결제 성공 → 후속 Kafka 이벤트 발행")
    void createOrderByOne_feignSuccess_publishesPostPaymentEvents() {
        // given
        UserCode userCode = new UserCode("user1");
        ProductCode productCode = new ProductCode("prod-1");

        when(orderUserPort.getUserInfo(userCode))
            .thenReturn(new UserInfo("user1", "buyer", "user1@test.com"));
        when(orderProductPort.getProduct(userCode, productCode))
            .thenReturn(new ProductSnapShot("prod-1", "seller1", "상품", 5000L, ProductStatus.AVAILABLE));
        when(feignPaymentAdapter.processPayment(eq("user1"), anyString(), eq(5000L), any()))
            .thenReturn(PaymentResult.success("order-1", 5000L));

        // when
        orderApplication.createOrderByOne(userCode, productCode);

        // then: Feign 어댑터가 호출되었는지
        verify(feignPaymentAdapter).processPayment(eq("user1"), anyString(), eq(5000L), any());
        // 후속 Kafka 이벤트 발행
        verify(orderKafkaEventPort).publishDepositSuccessCompleteOrder(eq("user1"), anyString());
        verify(orderKafkaEventPort).publishDepositSuccessCompleteDeleteCart(eq("user1"), anyString(), any());
        verify(orderKafkaEventPort).publishDepositSuccessCompleteProduct(anyString(), any());
    }

    @Test
    @DisplayName("Feign 전략: 결제 실패 → 주문 취소, Kafka 이벤트 미발행")
    void createOrderByOne_feignFailure_cancelsOrder() {
        // given
        UserCode userCode = new UserCode("user1");
        ProductCode productCode = new ProductCode("prod-1");

        when(orderUserPort.getUserInfo(userCode))
            .thenReturn(new UserInfo("user1", "buyer", "user1@test.com"));
        when(orderProductPort.getProduct(userCode, productCode))
            .thenReturn(new ProductSnapShot("prod-1", "seller1", "상품", 5000L, ProductStatus.AVAILABLE));
        when(feignPaymentAdapter.processPayment(eq("user1"), anyString(), eq(5000L), any()))
            .thenReturn(PaymentResult.fail("order-1", "예치금 부족"));

        // when
        orderApplication.createOrderByOne(userCode, productCode);

        // then: 주문 취소
        verify(orderPersistencePort).cancel(any(OrderCode.class));
        // 후속 이벤트는 발행되지 않음
        verify(orderKafkaEventPort, never()).publishDepositSuccessCompleteOrder(any(), any());
    }
}
```

---

## 6. 수동 통합 테스트 시나리오 (curl)

### 5.1 사전 조건

```bash
# 1. MySQL 실행
docker run -d --name mysql-test -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=dbay_db mysql:8.0

# 2. Kafka 실행 (Docker Compose 권장)
cd docker && docker-compose up -d kafka

# 3. payments 모듈 실행 (8085 포트)
./gradlew :payments:bootRun

# 4. commerce 모듈 실행 (8081 포트)
./gradlew :commerce:bootRun
```

### 5.2 시나리오 1: Feign 전략 — 정상 결제

```bash
# 전략 확인
grep "strategy" commerce/src/main/resources/application.yml
# → strategy: feign

# 1. 예치금 충전 (잔액 확보)
curl -X POST http://localhost:8085/api/deposits/charge \
  -H "X-CODE: user1" \
  -H "Content-Type: application/json" \
  -d '{"amount": 10000}'

# 2. 잔액 확인
curl -H "X-CODE: user1" http://localhost:8085/api/deposits/balance
# → {"resultCode":200,"data":{"balance":10000}}

# 3. 주문 생성 (→ Feign → payments 동기 호출)
curl -X POST http://localhost:8081/api/orders \
  -H "X-CODE: user1" \
  -H "Content-Type: application/json" \
  -d '{"productCode": "prod-1"}'

# 기대 응답 (200):
# {
#   "resultCode": 200,
#   "msg": "주문 생성 성공",
#   "data": { "orderCode": "...", "orderStatus": "PAID" }
# }

# 4. 잔액 재확인 (예치금 차감 확인)
curl -H "X-CODE: user1" http://localhost:8085/api/deposits/balance
# → balance 가 5000 감소했는지 확인

# 5. 결제 내역 확인
curl -H "X-CODE: user1" http://localhost:8085/api/payments/
# → 결제 내역에 PAYMENT_COMPLETED 상태로 추가되었는지 확인
```

### 5.3 시나리오 2: Feign 전략 — 잔액 부족

```bash
# 잔액이 없는 사용자로 주문 시도
curl -X POST http://localhost:8081/api/orders \
  -H "X-CODE: user-no-balance" \
  -H "Content-Type: application/json" \
  -d '{"productCode": "prod-1"}'

# 기대 응답: 4xx 또는 주문 취소 상태
# 예치금은 차감되지 않았어야 함 (전체 롤백)
```

### 5.4 시나리오 3: Kafka 전략 — 벤치마크 비교

```bash
# application.yml 에서 strategy 를 kafka 로 변경 후 서버 재시작
# 동일한 API 호출 → 내부적으로 Kafka 로 PaymentCreateCommand 발행

# StopWatch 또는 로그로 응답 시간 비교
```

---

## 7. 전략별 응답 시간 측정

### 6.1 로그 기반 측정

`OrderApplication` 의 `handlePaymentResult()` 에서 전략명과 함께 로그가 출력된다:

```
# Feign 전략
[feign] 결제 성공, 후속 이벤트 발행: userCode=user1, orderCode=ord-123

# Kafka 전략
[kafka] 결제 성공, 후속 이벤트 발행: userCode=user1, orderCode=ord-123
```

### 6.2 JMeter/k6 측정 포인트

| 측정 항목 | 엔드포인트 | 비교 관점 |
|----------|-----------|----------|
| 주문 생성 응답 시간 | `POST /api/orders` | Feign: payments 응답 대기, Kafka: 즉시 응답 |
| 결제 완료까지 시간 | Payment.createdAt - Order.createdAt | Feign: 즉시, Kafka: Saga 왕복 |
| 예치금 차감까지 시간 | Deposit.updatedAt - Order.createdAt | Feign: 즉시, Kafka: Saga 왕복 |
| TPS | 동시 요청 100건 | Feign: 블로킹, Kafka: 비동기 버퍼링 |

### 6.3 Micrometer 메트릭 (프로메테우스 연동)

```java
// 추천: OrderApplication.createOrderByOne() 에 추가
@PostMapping("/api/orders")
public BaseResponse<OrderDescription> createOrder(...) {
    Timer.Sample sample = Timer.start(meterRegistry);
    // ... 주문 생성 로직 ...
    sample.stop(Timer.builder("order.create")
        .tag("strategy", paymentStrategy)
        .tag("result", result.success() ? "success" : "fail")
        .register(meterRegistry));
}
```

---

## 8. 롤백 방법

변경사항을 되돌려야 할 경우:

```bash
# 1. OrderApplication.java 를 이전 커밋으로 복원
git checkout HEAD~1 -- commerce/src/main/java/io/devground/dbay/order/application/service/OrderApplication.java

# 2. 신규 생성 파일 삭제
rm commerce/src/main/java/io/devground/dbay/order/application/port/out/payment/OrderPaymentPort.java
rm commerce/src/main/java/io/devground/dbay/order/application/port/out/payment/PaymentResult.java
rm -r commerce/src/main/java/io/devground/dbay/order/infrastructure/adapter/out/payment/
rm payments/src/main/java/io/devground/payments/payment/event/PaymentCompletedEvent.java

# 3. 수정 파일 복원
git checkout HEAD~1 -- payments/src/main/java/io/devground/payments/payment/service/PaymentServiceImpl.java
git checkout HEAD~1 -- payments/src/main/java/io/devground/payments/payment/saga/PaymentKafkaHandler.java
git checkout HEAD~1 -- commerce/src/main/java/io/devground/dbay/order/application/port/out/kafka/OrderKafkaEventPort.java
git checkout HEAD~1 -- commerce/src/main/resources/application.yml

# 4. 컴파일 확인
./gradlew :commerce:compileJava :payments:compileJava
```
