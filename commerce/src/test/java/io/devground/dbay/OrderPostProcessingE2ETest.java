package io.devground.dbay;

import io.devground.core.commands.order.CompleteOrderCommand;
import io.devground.dbay.order.infrastructure.adapter.out.persistence.OrderJpaRepository;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import io.devground.dbay.support.CommerceIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * 후처리(주문완료) ALL SYNC vs Sync+Kafka 응답 시간 비교.
 *
 * 실제 MySQL + Kafka 컨테이너 위에서 측정.
 *
 * ALL SYNC:
 *   processPayment() → paidOrder() → 응답
 *   유저는 결제 + 주문완료 모두 끝날 때까지 기다린다.
 *
 * Sync + Kafka:
 *   processPayment() → Kafka.send(CompleteOrderCommand) → 응답
 *   유저는 결제 완료 즉시 피드백을 받는다. 주문완료는 Kafka 가 백그라운드 처리.
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration,"
        + "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration,"
        + "org.springframework.ai.autoconfigure.openai.OpenAiConnectionAutoConfiguration"
})
class OrderPostProcessingE2ETest extends CommerceIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(OrderPostProcessingE2ETest.class);

    @MockBean
    private VectorStore vectorStore;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    private static final int ITERATIONS = 10;
    private static final String TOPIC = "orders-purchase-commands";

    @Test
    @DisplayName("ALL SYNC(동기) vs Sync+Kafka: 유저 응답 시간 비교")
    void compareAllSyncVsSyncWithKafka() {
        List<Long> allSyncTimes = new ArrayList<>();
        List<Long> syncWithKafkaTimes = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            warmup("warmup-" + i);
        }

        log.info("--- 측정 시작 ({}회) ---", ITERATIONS);

        for (int i = 0; i < ITERATIONS; i++) {
            // ==========================================
            // ALL SYNC: 결제(시뮬) + 주문완료까지 전부 동기
            // ==========================================
            String syncCode = "sync-" + UUID.randomUUID().toString().substring(0, 8);
            createOrder(syncCode);

            long syncStart = System.nanoTime();

            // 결제 처리 (시뮬레이션 — 실제론 Feign 으로 payments 호출)
            simulatePaymentProcessing();

            // 주문 완료 (동기 — 유저는 이 DB 쓰기가 끝날 때까지 기다린다)
            OrderEntity syncOrder = orderJpaRepository.findByCode(syncCode).orElseThrow();
            syncOrder.paid();
            orderJpaRepository.save(syncOrder);

            long syncElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - syncStart);
            allSyncTimes.add(syncElapsed);

            // ==========================================
            // Sync + Kafka: 결제만 동기, 주문완료는 Kafka
            // ==========================================
            String kafkaCode = "kafka-" + UUID.randomUUID().toString().substring(0, 8);
            createOrder(kafkaCode);

            long kafkaStart = System.nanoTime();

            // 결제 처리 (시뮬레이션)
            simulatePaymentProcessing();

            // 주문완료 — Kafka 로 비동기 발행만 하고 응답
            // (유저는 여기서 바로 피드백을 받는다!
            //  Kafka consumer 가 백그라운드에서 paidOrder() 호출)
            kafkaTemplate.send(TOPIC, kafkaCode,
                    new CompleteOrderCommand("test-user", kafkaCode));

            long kafkaElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - kafkaStart);
            syncWithKafkaTimes.add(kafkaElapsed);

            log.info("  #{}  ALL SYNC={}ms  Sync+Kafka={}ms  절약={}ms",
                    i, syncElapsed, kafkaElapsed, syncElapsed - kafkaElapsed);
        }

        // === 결과 ===
        double allSyncAvg = avg(allSyncTimes);
        double kafkaAvg = avg(syncWithKafkaTimes);
        double savedMs = allSyncAvg - kafkaAvg;

        log.info("==============================================");
        log.info("  측정 결과 ({}회)", ITERATIONS);
        log.info("  ALL SYNC (주문완료까지 대기):     avg={}ms", String.format("%.1f", allSyncAvg));
        log.info("  Sync+Kafka (발행만, 대기 안 함):  avg={}ms", String.format("%.1f", kafkaAvg));
        log.info("  ---");
        log.info("  Kafka 후처리로 단축: {}ms ({}% 절약)",
                String.format("%.1f", savedMs),
                String.format("%.0f", savedMs / allSyncAvg * 100));
        log.info("  → 유저는 결제 완료 즉시 피드백, 후처리는 비동기");
        log.info("==============================================");

    assertThat(kafkaAvg)
                .describedAs("Sync+Kafka 는 ALL SYNC 보다 빨라야 한다")
                .isLessThan(allSyncAvg);
    }

    /** 결제 처리 시뮬레이션 (실제는 Feign 으로 payments 모듈 호출) */
    private void simulatePaymentProcessing() {
        // payments 모듈의 PaymentServiceImpl.process() 호출을 시뮬레이션
        // 실제 환경에서는 Feign HTTP 호출 → ~16ms 소요
    }

    private void createOrder(String orderCode) {
        OrderEntity order = OrderEntity.builder()
                .orderCode(orderCode)
                .userCode("test-user")
                .nickName("tester")
                .address("test-address")
                .addressDetail("test-detail")
                .totalAmount(5000L)
                .build();
        orderJpaRepository.save(order);
    }

    private void warmup(String id) {
        String code = "warmup-" + id + "-" + UUID.randomUUID().toString().substring(0, 4);
        createOrder(code);
        OrderEntity order = orderJpaRepository.findByCode(code).orElseThrow();
        order.paid();
        orderJpaRepository.save(order);
    }

    private static double avg(List<Long> times) {
        return times.stream().mapToLong(Long::longValue).average().orElse(0);
    }
}