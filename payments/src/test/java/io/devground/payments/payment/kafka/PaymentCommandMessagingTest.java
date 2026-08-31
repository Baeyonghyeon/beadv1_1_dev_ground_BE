package io.devground.payments.payment.kafka;

import io.devground.core.commands.payment.PaymentRequestedCommand;
import io.devground.core.event.payment.OrderPaymentCompleted;
import io.devground.core.event.payment.OrderPaymentFailed;
import io.devground.core.event.payment.PaymentFailReason;
import io.devground.payments.deposit.application.port.out.DepositCommandPort;
import io.devground.payments.deposit.application.port.out.DepositPersistencePort;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.payment.repository.PaymentRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 결제 커맨드 소비 통합 테스트 (payments 쪽, 실제 Kafka 브로커 경유).
 *
 * <p><b>단위 테스트가 못 보는 것을 본다.</b> {@code PaymentKafkaHandlerAsyncPaymentTest} 는
 * 핸들러 메서드를 직접 호출하므로 <b>{@code @KafkaHandler} 가 4개 타입 중 올바른 것으로 분배하는지</b>는
 * 검증되지 않는다. {@code PaymentKafkaHandler} 는 {@code DepositRefundCommand},
 * {@code DepositChargedSuccess}, {@code DepositChargeFailed}, {@code DepositRefundFailed} 를 이미 받고 있고
 * 여기에 {@code PaymentRequestedCommand} 를 추가했다 — 라우팅이 어긋나면 결제가 통째로 DLT 로 간다.
 *
 * <p>Docker 없이 돈다 — H2 + {@code @EmbeddedKafka}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-cmd-msg;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.batch.job.enabled=false",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.group-id=payments-embedded-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "custom.kafka.config.topic-partitions=1",
        "custom.kafka.config.topic-replications=1",
        "custom.toss.secretKey=test-key",
        "custom.toss.clientKey=test-key",
        "custom.toss.confirm-url=http://localhost:19999/never-called",
        "custom.settlement.cron=-",
        "external.openfeign-url=http://localhost:18085"
})
@EmbeddedKafka(partitions = 1, topics = {
        "payments-purchase-commands", "payments-purchase-events", "payments-events",
        "deposits-commands", "deposits-payment-events", "deposits-events"
})
@DirtiesContext
@DisplayName("결제 커맨드 소비 (payments)")
class PaymentCommandMessagingTest {

    private static final String COMMAND_TOPIC = "payments-purchase-commands";
    private static final String EVENT_TOPIC = "payments-purchase-events";
    private static final long PRICE = 5_000L;

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private DepositPersistencePort depositPersistencePort;
    @Autowired private DepositCommandPort depositCommandPort;
    @Autowired private PaymentRepository paymentRepository;

    private Consumer<String, String> eventConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("probe-" + System.nanoTime(), "true", broker);
        eventConsumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(eventConsumer, EVENT_TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (eventConsumer != null) {
            eventConsumer.close();
        }
    }

    private String seedUser(long balance) {
        String userCode = "USER-" + UUID.randomUUID().toString().substring(0, 8);
        depositCommandPort.saveDeposit(new Deposit(UUID.randomUUID().toString(), userCode, balance));
        return userCode;
    }

    private PaymentRequestedCommand commandFor(String userCode, String orderCode) {
        return new PaymentRequestedCommand(orderCode, userCode, PRICE, List.of("PROD-1"), Instant.now());
    }

    /**
     * 이 주문에 해당하는 결과 이벤트를 골라낸다.
     * 테스트들이 같은 임베디드 브로커를 공유하므로 다른 테스트가 남긴 레코드가 함께 읽힌다.
     */
    private Optional<String> findEventFor(String orderCode) {
        return StreamSupport.stream(
                        KafkaTestUtils.getRecords(eventConsumer, Duration.ofSeconds(10)).spliterator(), false)
                .map(org.apache.kafka.clients.consumer.ConsumerRecord::value)
                .filter(v -> v != null && v.contains(orderCode))
                .findFirst();
    }

    // ════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("결제 커맨드를 받으면 예치금을 차감하고 성공 이벤트를 발행한다")
    void 커맨드를_받으면_차감하고_성공이벤트를_발행한다() {
        String userCode = seedUser(1_000_000L);
        String orderCode = "ORDER-" + UUID.randomUUID();

        kafkaTemplate.send(COMMAND_TOPIC, userCode, commandFor(userCode, orderCode));

        // ① 결제가 실제로 처리됐는가
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderCode(orderCode)).isPresent());

        assertThat(depositPersistencePort.getDepositByUserCode(userCode).orElseThrow().getBalance())
                .isEqualTo(1_000_000L - PRICE);

        // ② 결과 이벤트가 commerce 가 구독하는 토픽으로 나갔는가
        String event = findEventFor(orderCode)
                .orElseThrow(() -> new AssertionError("결과 이벤트가 발행되지 않았다: " + orderCode));
        assertThat(event).contains("paymentCode");
    }

    @Test
    @DisplayName("잔액이 부족하면 차감 없이 INSUFFICIENT_BALANCE 로 발행한다")
    void 잔액이_부족하면_보상불필요_사유로_발행한다() {
        String userCode = seedUser(100L);          // 필요 5,000
        String orderCode = "ORDER-" + UUID.randomUUID();

        kafkaTemplate.send(COMMAND_TOPIC, userCode, commandFor(userCode, orderCode));

        String event = await().atMost(Duration.ofSeconds(20))
                .until(() -> findEventFor(orderCode), Optional::isPresent)
                .orElseThrow();

        assertThat(event).contains(PaymentFailReason.INSUFFICIENT_BALANCE.name());

        // 차감이 일어나지 않아야 보상이 불필요하다는 분류가 성립한다
        assertThat(depositPersistencePort.getDepositByUserCode(userCode).orElseThrow().getBalance())
                .isEqualTo(100L);
        assertThat(paymentRepository.findByOrderCode(orderCode)).isEmpty();
    }

    @Test
    @DisplayName("같은 커맨드를 두 번 받아도 예치금은 한 번만 차감된다 — orderCode 멱등키")
    void 중복_커맨드에도_한_번만_차감된다() {
        String userCode = seedUser(1_000_000L);
        String orderCode = "ORDER-" + UUID.randomUUID();

        PaymentRequestedCommand command = commandFor(userCode, orderCode);
        kafkaTemplate.send(COMMAND_TOPIC, userCode, command);
        kafkaTemplate.send(COMMAND_TOPIC, userCode, command);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderCode(orderCode)).isPresent());

        // 두 번 차감되면 5,000 이 아니라 10,000 이 빠진다 — 실측으로 잡았던 이중 차감과 같은 증상
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(depositPersistencePort.getDepositByUserCode(userCode).orElseThrow().getBalance())
                        .isEqualTo(1_000_000L - PRICE));
    }
}
