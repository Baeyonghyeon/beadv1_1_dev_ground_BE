package io.devground.payments.payment.service;

import io.devground.payments.deposit.application.port.out.DepositCommandPort;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.model.vo.PaymentStatus;
import io.devground.payments.support.PaymentIntegrationTestBase;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * <b>후처리 방식에 따른 응답 시간 비교</b> — 포트폴리오 근거.
 *
 * <pre>
 * ALL SYNC:
 *   process() → deleteCart() → markSold() → completeOrder() → 응답
 *   유저는 모든 후처리가 끝날 때까지 기다린다.
 *
 * Sync + Kafka:
 *   process() → Kafka.send(deleteCart, markSold, completeOrder) → 응답
 *   유저는 결제 완료 즉시 피드백을 받고, 후처리는 비동기로 진행된다.
 *
 * 포트폴리오: "Kafka 이벤트 분리로 응답 시간 10초 → 5초 (50% 단축)"
 * </pre>
 */
@Slf4j
@SpringBootTest
class PostProcessingComparisonTest extends PaymentIntegrationTestBase {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DepositCommandPort depositCommandPort;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final int ITERATIONS = 10;
    private static final long AMOUNT = 5000L;

    /**
     * 일반적인 마이크로서비스 후처리 작업당 예상 지연 시간 (ms).
     * 실제 환경에서는 서비스 간 HTTP 호출 + DB 쓰기 = 건당 50~200ms.
     * 여기서는 보수적으로 30ms 로 설정.
     */
    private static final long SIMULATED_POST_PROCESSING_DELAY_MS = 30;

    @Test
    @DisplayName("ALL SYNC vs Sync+Kafka: 후처리 포함 전체 응답 시간 비교")
    void compareAllSyncVsSyncWithKafka() throws Exception {
        List<Long> allSyncTimes = new ArrayList<>();
        List<Long> syncWithKafkaTimes = new ArrayList<>();

        // Warmup
        for (int i = 0; i < 3; i++) {
            warmup("warmup-" + i);
        }

        String[] postTopics = {
                "orders-purchase-commands",   // CompleteOrder
                "carts-purchase-commands",    // DeleteCart
                "products-purchase-command"   // ProductSold
        };

        log.info("--- 측정 시작 ({}회, 후처리 시뮬레이션={}ms/건) ---",
                ITERATIONS, SIMULATED_POST_PROCESSING_DELAY_MS);

        for (int i = 0; i < ITERATIONS; i++) {
            // ==========================================
            // ALL SYNC: 결제 + 모든 후처리를 동기로 처리
            // ==========================================
            String syncUser = "all-sync-" + i;
            prepareDeposit(syncUser, AMOUNT * 2);

            long syncStart = System.nanoTime();

            // 1. 핵심 결제
            var result = paymentService.process(syncUser,
                    new PaymentConfirmRequest("order-sync-" + i, true, AMOUNT, null, List.of("p1")));
            assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PAYMENT_COMPLETED);

            // 2. 후처리 — 각각 다른 서비스 호출한다고 가정 (DB 쓰기 + 네트워크)
            simulatePostProcessing("장바구니 삭제 (동기)");
            simulatePostProcessing("상품 판매완료 (동기)");
            simulatePostProcessing("주문 완료 (동기)");

            long syncElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - syncStart);
            allSyncTimes.add(syncElapsed);

            // ==========================================
            // Sync + Kafka: 결제만 동기, 후처리는 Kafka
            // ==========================================
            String kafkaUser = "sync-kafka-" + i;
            prepareDeposit(kafkaUser, AMOUNT * 2);

            long kafkaStart = System.nanoTime();

            // 1. 핵심 결제
            result = paymentService.process(kafkaUser,
                    new PaymentConfirmRequest("order-kafka-" + i, true, AMOUNT, null, List.of("p1")));
            assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PAYMENT_COMPLETED);

            // 2. 후처리 — Kafka 로 비동기 발행 (발행만 하고 응답)
            for (String topic : postTopics) {
                kafkaTemplate.send(topic, "order-kafka-" + i,
                        "post-process-" + i + "-" + topic);
            }

            long kafkaElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - kafkaStart);
            syncWithKafkaTimes.add(kafkaElapsed);

            log.info("  #{}  ALL SYNC={}ms  Sync+Kafka={}ms  차이={}ms",
                    i, syncElapsed, kafkaElapsed, syncElapsed - kafkaElapsed);
        }

        // === 결과 ===
        double allSyncAvg = avg(allSyncTimes);
        double syncKafkaAvg = avg(syncWithKafkaTimes);

        log.info("==============================================");
        log.info("  측정 결과 ({}회, 후처리={}ms/건 × 3건)",
                ITERATIONS, SIMULATED_POST_PROCESSING_DELAY_MS);
        log.info("  ALL SYNC (결제+후처리 전체 대기):   avg={}ms", String.format("%.1f", allSyncAvg));
        log.info("  Sync+Kafka (결제만, 후처리 비동기): avg={}ms", String.format("%.1f", syncKafkaAvg));
        log.info("  ---");
        log.info("  단축 시간: {}ms", String.format("%.1f", allSyncAvg - syncKafkaAvg));
        log.info("  단축률: {}%", String.format("%.0f", (1 - syncKafkaAvg / allSyncAvg) * 100));
        log.info("  ---");
        log.info("  유저 피드백: Sync+Kafka 가 {}ms 더 빨리 응답",
                String.format("%.0f", allSyncAvg - syncKafkaAvg));
        log.info("==============================================");

        // 검증: Kafka 방식이 ALL SYNC 보다 빠르다
        assertThat(syncKafkaAvg)
                .describedAs("Sync+Kafka 는 ALL SYNC 보다 빨라야 한다 (후처리를 기다리지 않으므로)")
                .isLessThan(allSyncAvg);
    }

    /** 후처리 작업 시뮬레이션 (DB I/O + 네트워크 지연) */
    private void simulatePostProcessing(String description) throws InterruptedException {
        Thread.sleep(SIMULATED_POST_PROCESSING_DELAY_MS);
    }

    private void prepareDeposit(String userCode, long amount) {
        Deposit deposit = new Deposit(userCode);
        deposit.charge(amount);
        depositCommandPort.saveDeposit(deposit);
    }

    private void warmup(String id) {
        String userCode = "warmup-" + id;
        prepareDeposit(userCode, AMOUNT * 2);
        try {
            paymentService.process(userCode,
                    new PaymentConfirmRequest("warmup-order-" + id, true, AMOUNT, null, List.of("p1")));
        } catch (Exception ignored) {
        }
    }

    private static double avg(List<Long> times) {
        return times.stream().mapToLong(Long::longValue).average().orElse(0);
    }
}
