package io.devground.dbay.order.kafka;

import io.devground.core.commands.payment.PaymentRequestedCommand;
import io.devground.core.event.payment.OrderPaymentCompleted;
import io.devground.core.event.payment.OrderPaymentFailed;
import io.devground.core.event.payment.PaymentFailReason;
import io.devground.dbay.order.application.port.out.product.OrderProductPort;
import io.devground.dbay.order.application.port.out.user.OrderUserPort;
import io.devground.dbay.order.application.service.OrderApplication;
import io.devground.dbay.order.application.vo.OrderAcceptance;
import io.devground.dbay.order.application.vo.ProductSnapShot;
import io.devground.dbay.order.application.vo.UserInfo;
import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.domain.vo.ProductCode;
import io.devground.dbay.order.domain.vo.ProductStatus;
import io.devground.dbay.order.domain.vo.UserCode;
import io.devground.dbay.order.infrastructure.adapter.out.persistence.OrderJpaRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 비동기 결제 메시징 통합 테스트 (commerce 쪽, 실제 Kafka 브로커 경유).
 *
 * <p><b>왜 이 테스트가 따로 필요한가:</b> 단위 테스트는 핸들러 메서드를 <b>직접 호출</b>한다.
 * 그 사이에 있는 세 가지는 검증되지 않는다 —
 * <ol>
 *   <li><b>{@code @KafkaHandler} 타입 라우팅</b> — 어긋나면 이벤트가 전부 DLT 로 간다</li>
 *   <li><b>JSON 직렬화/역직렬화</b> — {@code spring.json.trusted.packages} 가 틀리면 즉시 DLT</li>
 *   <li><b>{@code AFTER_COMMIT} 발행 시점</b> — 커밋 전에 나가면 payments 가 없는 주문을 결제한다</li>
 * </ol>
 * 셋 다 "조용히" 깨지고, 증상은 "결제가 안 되고 DLT 만 쌓임" 이라 늦게 발견된다.
 *
 * <p>Docker 없이 돈다 — H2 + {@code @EmbeddedKafka}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:async-payment-msg;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.group-id=commerce-embedded-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.ai.openai.api-key=test-key",
        "external.payment-url=http://localhost:18085",
        "custom.kafka.config.topic-partitions=1",
        "custom.kafka.config.topic-replications=1",
        // ▼ 이 테스트의 대상 — 비동기 결제 경로
        "order.payment.strategy=kafka",
        "order.postprocess.strategy=kafka",
        "order.payment.reconcile-cron=-",
        "spring.autoconfigure.exclude="
                + "org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration"
})
@EmbeddedKafka(partitions = 1, topics = {
        "payments-purchase-commands", "payments-purchase-events",
        "orders-purchase-commands", "carts-purchase-commands", "products-purchase-command"
})
@DirtiesContext
@DisplayName("비동기 결제 메시징 (commerce)")
class AsyncPaymentMessagingTest {

    private static final String COMMAND_TOPIC = "payments-purchase-commands";
    private static final String EVENT_TOPIC = "payments-purchase-events";

    private static final UserCode USER = new UserCode("USER-MSG-1");
    private static final ProductCode PRODUCT = new ProductCode("PROD-MSG-1");

    @Autowired private OrderApplication orderApplication;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;

    // 외부 서비스 호출은 이 테스트의 관심사가 아니다
    @MockBean private OrderUserPort orderUserPort;
    @MockBean private OrderProductPort orderProductPort;
    @MockBean private VectorStore vectorStore;

    private Consumer<String, String> commandConsumer;

    @BeforeEach
    void setUp() {
        when(orderUserPort.getUserInfo(any())).thenReturn(new UserInfo("닉", "주소", "상세"));
        when(orderProductPort.getProduct(any(), any())).thenReturn(
                new ProductSnapShot("PROD-MSG-1", "SELLER-1", "테스트상품", 5_000L, ProductStatus.ON_SALE));

        // 결제 커맨드를 원문으로 읽는 테스트 컨슈머 (앱 컨슈머와 다른 그룹)
        Map<String, Object> props = KafkaTestUtils.consumerProps("probe-" + System.nanoTime(), "true", broker);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        commandConsumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(commandConsumer, COMMAND_TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (commandConsumer != null) {
            commandConsumer.close();
        }
    }

    private OrderStatus statusOf(String orderCode) {
        return orderJpaRepository.findByCode(orderCode).orElseThrow().getOrderStatus();
    }

    /**
     * 이 주문에 해당하는 결제 커맨드 레코드를 골라낸다.
     *
     * <p>테스트들이 같은 임베디드 브로커·같은 토픽을 공유하므로 다른 테스트가 남긴 레코드가 함께 읽힌다.
     * {@code getSingleRecord} 를 쓰면 "More than one record" 로 엉뚱하게 실패한다.
     */
    private Optional<ConsumerRecord<String, String>> findCommandFor(String orderCode) {
        return StreamSupport.stream(
                        KafkaTestUtils.getRecords(commandConsumer, Duration.ofSeconds(10)).spliterator(), false)
                .filter(r -> r.value() != null && r.value().contains(orderCode))
                .findFirst();
    }

    // ════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("주문을 접수하면 결제 커맨드가 실제 토픽에 실려 나간다 — 키는 userCode")
    void 접수하면_결제커맨드가_토픽에_실린다() {
        OrderAcceptance acceptance = orderApplication.createOrderByOne(USER, PRODUCT);

        ConsumerRecord<String, String> record = findCommandFor(acceptance.orderCode())
                .orElseThrow(() -> new AssertionError("결제 커맨드가 발행되지 않았다: " + acceptance.orderCode()));

        // 파티션 키가 userCode 여야 같은 유저의 결제가 한 파티션에서 직렬화된다 (설계 §5.2)
        assertThat(record.key()).isEqualTo(USER.value());

        // 역직렬화 대상 타입을 헤더로 알려주는지 — 이게 없으면 payments 의 @KafkaHandler 가 분배하지 못한다
        assertThat(record.headers().lastHeader("__TypeId__")).isNotNull();
        assertThat(new String(record.headers().lastHeader("__TypeId__").value()))
                .isEqualTo(PaymentRequestedCommand.class.getName());
    }

    @Test
    @Transactional
    @DisplayName("트랜잭션이 커밋되지 않으면 결제 커맨드가 나가지 않는다 — AFTER_COMMIT 검증")
    void 커밋되지_않으면_커맨드가_나가지_않는다() {
        // 테스트 메서드의 트랜잭션은 끝에 롤백된다. 발행이 AFTER_COMMIT 에 걸려 있다면
        // 이 안에서는 어떤 메시지도 나가면 안 된다.
        //
        // 이 단언이 깨진다는 건 커밋 전에 발행한다는 뜻이고, 그러면 payments 가
        // 아직 존재하지 않는 주문의 결제를 처리한다 (스파이크 문서 §7.2 의 함정).
        String orderCode = orderApplication.createOrderByOne(USER, PRODUCT).orderCode();

        assertThat(findCommandFor(orderCode)).isEmpty();
    }

    @Test
    @DisplayName("결제 성공 이벤트를 받으면 주문이 PAID 로 전이된다 — 역직렬화 + 타입 라우팅")
    void 성공이벤트를_받으면_PAID로_전이된다() {
        String orderCode = orderApplication.createOrderByOne(USER, PRODUCT).orderCode();
        assertThat(statusOf(orderCode)).isEqualTo(OrderStatus.PAYMENT_PENDING);

        kafkaTemplate.send(EVENT_TOPIC, USER.value(), new OrderPaymentCompleted(
                orderCode, USER.value(), "PAY-1", 5_000L, java.util.List.of(PRODUCT.value())));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(orderCode)).isEqualTo(OrderStatus.PAID));
    }

    @Test
    @DisplayName("결제 실패 이벤트를 받으면 주문이 PAYMENT_FAILED 로 전이된다 — 두 번째 타입도 올바로 분배되는가")
    void 실패이벤트를_받으면_PAYMENT_FAILED로_전이된다() {
        String orderCode = orderApplication.createOrderByOne(USER, PRODUCT).orderCode();

        kafkaTemplate.send(EVENT_TOPIC, USER.value(), new OrderPaymentFailed(
                orderCode, USER.value(), 5_000L, PaymentFailReason.INSUFFICIENT_BALANCE, "잔액 부족"));

        // 같은 토픽·같은 컨슈머 클래스에서 타입만 다르다. 라우팅이 어긋나면
        // 성공 핸들러로 들어가 PAID 가 되거나, 아무 핸들러도 못 찾아 DLT 로 간다.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(orderCode)).isEqualTo(OrderStatus.PAYMENT_FAILED));
    }

    @Test
    @DisplayName("같은 성공 이벤트를 두 번 받아도 PAID 그대로다 — at-least-once 대응")
    void 중복_성공이벤트에도_상태가_유지된다() {
        String orderCode = orderApplication.createOrderByOne(USER, PRODUCT).orderCode();

        OrderPaymentCompleted event = new OrderPaymentCompleted(
                orderCode, USER.value(), "PAY-1", 5_000L, java.util.List.of(PRODUCT.value()));
        kafkaTemplate.send(EVENT_TOPIC, USER.value(), event);
        kafkaTemplate.send(EVENT_TOPIC, USER.value(), event);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(orderCode)).isEqualTo(OrderStatus.PAID));

        // 두 번째 소비가 상태를 되돌리거나 예외로 DLT 에 빠지지 않는지 확인한다
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(statusOf(orderCode)).isEqualTo(OrderStatus.PAID));
    }
}
