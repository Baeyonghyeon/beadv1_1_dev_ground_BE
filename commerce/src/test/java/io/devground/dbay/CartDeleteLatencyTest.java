package io.devground.dbay;

import io.devground.core.commands.cart.DeleteCartItemsCommand;
import io.devground.dbay.cart.infrastructure.adapter.out.persistence.CartItemJpaRepository;
import io.devground.dbay.cart.infrastructure.adapter.out.persistence.CartJpaRepository;
import io.devground.dbay.cart.infrastructure.model.persistence.CartEntity;
import io.devground.dbay.cart.infrastructure.model.persistence.CartItemEntity;
import io.devground.dbay.support.CommerceIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * 장바구니 삭제 Kafka Consumer 처리 시간 측정.
 *
 * 실제 MySQL + Kafka 컨테이너 위에서:
 * 1. 장바구니 + 상품 데이터 생성
 * 2. DeleteCartItemsCommand → Kafka 발행
 * 3. CartCommandConsumer → CartApplication.removeCartItems() → DB DELETE
 * 4. 완료 시간 측정
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration,"
        + "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration"
})
class CartDeleteLatencyTest extends CommerceIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(CartDeleteLatencyTest.class);

    @MockBean private VectorStore vectorStore;

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private CartJpaRepository cartJpaRepository;
    @Autowired private CartItemJpaRepository cartItemJpaRepository;
    @Autowired private org.springframework.transaction.support.TransactionTemplate tx;

    private static final int ITERATIONS = 5;
    private static final String TOPIC = "carts-purchase-commands";
    private final List<Long> cartIdsToClean = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        tx.executeWithoutResult(status -> {
            for (Long id : cartIdsToClean) {
                cartJpaRepository.findById(id).ifPresent(cart -> {
                    cartItemJpaRepository.deleteCartItemEntityByCartCode(cart);
                    cartJpaRepository.delete(cart);
                });
            }
        });
        cartIdsToClean.clear();
    }

    @Test
    @DisplayName("장바구니 삭제 Kafka Consumer round-trip 시간 측정")
    void measureCartDeleteKafkaLatency() throws Exception {
        List<Long> syncTimes = new ArrayList<>();
        List<Long> kafkaTimes = new ArrayList<>();

        for (int i = 0; i < 3; i++) warmup();

        log.info("--- 측정 ({}회) ---", ITERATIONS);

        for (int i = 0; i < ITERATIONS; i++) {
            String userCode = "cart-user-" + UUID.randomUUID().toString().substring(0, 6);
            String productCode = "prod-" + i;
            CartEntity cart = createCartWithItem(userCode, productCode);

            // === 동기 삭제 (기준선) ===
            CartEntity syncCart = createCartWithItem(userCode + "-sync", productCode + "-sync");
            long syncMs = syncDelete(syncCart, productCode + "-sync");
            syncTimes.add(syncMs);

            // === Kafka 비동기 삭제 ===
            long kafkaStart = System.nanoTime();
            kafkaTemplate.send(TOPIC, userCode,
                    new DeleteCartItemsCommand(userCode, List.of(productCode)));
            long publishMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - kafkaStart);

            // 완료 대기 (아이템이 실제로 삭제될 때까지)
            boolean deleted = waitForDeletion(cart.getId(), 5_000);
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - kafkaStart);
            if (deleted) kafkaTimes.add(totalMs);

            log.info("  #{} 동기DELETE={}ms Kafka발행={}ms Kafka완료={}ms",
                    i, syncMs, publishMs, deleted ? totalMs : -1);
        }

        double syncAvg = avg(syncTimes);
        double kafkaAvg = kafkaTimes.isEmpty() ? 0 : avg(kafkaTimes);

        log.info("=== 장바구니 삭제: 동기 avg={}ms  Kafka round-trip avg={}ms ===",
                String.format("%.1f", syncAvg), String.format("%.1f", kafkaAvg));

        assertThat(syncAvg).isGreaterThan(0);
    }

    private CartEntity createCartWithItem(String userCode, String productCode) {
        return tx.execute(status -> {
            CartEntity cart = CartEntity.builder()
                    .userCode(userCode).cartCode(UUID.randomUUID().toString()).build();
            cart = cartJpaRepository.save(cart);
            CartItemEntity item = CartItemEntity.builder()
                    .cartEntity(cart).productCode(productCode).build();
            cartItemJpaRepository.save(item);
            cartIdsToClean.add(cart.getId());
            return cart;
        });
    }

    /** 트랜잭션 내에서 동기 삭제 */
    private long syncDelete(CartEntity cart, String productCode) {
        long start = System.nanoTime();
        tx.executeWithoutResult(status ->
            cartItemJpaRepository.deleteCartItemEntityByProductCodes(cart, List.of(productCode)));
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** 트랜잭션 내에서 아이템 개수 확인 */
    private long countItems(Long cartId) {
        return tx.execute(status -> {
            var cart = cartJpaRepository.findById(cartId).orElse(null);
            if (cart == null) return 0L;
            return cartItemJpaRepository.countByCartEntity(cart);
        });
    }

    private boolean waitForDeletion(Long cartId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countItems(cartId) == 0) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void warmup() throws InterruptedException {
        String uc = "warmup-" + UUID.randomUUID().toString().substring(0, 6);
        CartEntity cart = createCartWithItem(uc, "warmup-p");
        kafkaTemplate.send(TOPIC, uc, new DeleteCartItemsCommand(uc, List.of("warmup-p")));
        waitForDeletion(cart.getId(), 3_000);
    }

    private static double avg(List<Long> ts) {
        return ts.stream().mapToLong(Long::longValue).average().orElse(0);
    }
}
