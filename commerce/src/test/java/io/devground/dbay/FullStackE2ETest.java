package io.devground.dbay;

import io.devground.core.commands.deposit.ChargeDeposit;
import io.devground.core.commands.deposit.CreateDeposit;
import io.devground.core.model.vo.DepositHistoryType;
import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.vo.ProductSnapShot;
import io.devground.dbay.order.domain.vo.ProductStatus;
import io.devground.dbay.order.infrastructure.adapter.out.product.ProductFeignClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * payments(8085) + commerce(8081) 동시 기동 MSA E2E 테스트.
 *
 * 실제 MySQL + Kafka 컨테이너 위에서 두 모듈을 띄우고,
 * POST /api/commerce/order/{productCode} 의 전체 응답 시간을 측정한다.
 *
 * Feign 전략: 결제는 동기, 후처리(주문완료,장바구니삭제)는 Kafka 비동기.
 */
@Testcontainers
class FullStackE2ETest {

    private static final Logger log = LoggerFactory.getLogger(FullStackE2ETest.class);

    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dbay_db").withUsername("mysqlId").withPassword("mysqlPwd");
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.5"));

    private static ConfigurableApplicationContext payments, commerce;
    private static final RestTemplate rest = new RestTemplate();
    private static final int ITERATIONS = 10;
    private static final long AMOUNT = 5000L;
    private static final String USER = "fs-user";

    @BeforeAll
    static void start() {
        String jdbc = MYSQL.getJdbcUrl();
        String kafka = KAFKA.getBootstrapServers();
        log.info("MySQL JDBC: {}", jdbc);
        log.info("Kafka: {}", kafka);
        log.info("MySQL host={} port={}", MYSQL.getHost(), MYSQL.getMappedPort(3306));

        String[] props = {
            "spring.datasource.url=" + jdbc,
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.username=" + MYSQL.getUsername(),
            "spring.datasource.password=" + MYSQL.getPassword(),
            "spring.kafka.bootstrap-servers=" + kafka,
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "springdoc.api-docs.enabled=false",
            "springdoc.swagger-ui.enabled=false",
            "spring.kafka.producer.properties.allow.auto.create.topics=true",
            "spring.kafka.consumer.properties.allow.auto.create.topics=true",
            "spring.batch.job.enabled=false",
            "spring.batch.jdbc.initialize-schema=never",
            "spring.jpa.hibernate.ddl-auto=update",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
            "spring.autoconfigure.exclude="
                + "org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration,"
                + "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration",
            "spring.elasticsearch.uris=http://localhost:9999",
            "spring.ai.openai.api-key=test",
        };

        log.info("payments (8085)...");
        payments = new SpringApplicationBuilder(io.devground.payments.PaymentsApplication.class)
                .properties(props)
                .properties("server.port=8085", "spring.kafka.consumer.group-id=fs-pay",
                        "external.openfeign-url=http://localhost:8085",
                        "custom.toss.secretKey=test-key")
                .run();
        log.info("payments OK");

        log.info("commerce (8081)...");
        commerce = new SpringApplicationBuilder(io.devground.dbay.CommerceApplication.class)
                .sources(ProductFeignMockConfig.class)  // product service 없으므로 mock
                .properties(props)
                .properties("server.port=8081", "spring.kafka.consumer.group-id=fs-comm",
                        "external.payment-url=http://localhost:8085",
                        "order.payment.strategy=feign")
                .run();
        log.info("commerce OK");
        log.info("=== 2 modules ready ===");
    }

    @AfterAll
    static void stop() {
        if (commerce != null) commerce.close();
        if (payments != null) payments.close();
    }

    @Test
    @DisplayName("payments+commerce 동시 기동: POST /api/commerce/order 전체 응답 시간")
    void measure() throws Exception {
        // 유저 세팅
        var kafka = payments.getBean(KafkaTemplate.class);
        kafka.send("deposits-join-commands", USER, new CreateDeposit(USER));
        Thread.sleep(2000);
        topUp(kafka);
        Thread.sleep(1000);

        // warmup
        HttpHeaders h = new HttpHeaders();
        h.set("X-CODE", USER);
        rest.postForEntity("http://localhost:8081/api/commerce/order/p1", new HttpEntity<>(h), String.class);
        Thread.sleep(2000);

        // 측정
        List<Long> times = new ArrayList<>();
        log.info("--- {}회 ---", ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            topUp(kafka);
            long t = System.nanoTime();
            var r = rest.postForEntity("http://localhost:8081/api/commerce/order/p1",
                    new HttpEntity<>(h), String.class);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t);
            times.add(ms);
            log.info("  #{} {}ms status={}", i, ms, r.getStatusCode().value());
        }

        double avg = avg(times);
        log.info("=== Full Stack({}modules,{}runs) avg={}ms ===", 2, ITERATIONS, String.format("%.1f", avg));
        assertThat(avg).isGreaterThan(0);
    }

    @SuppressWarnings("unchecked")
    private void topUp(KafkaTemplate kafka) throws Exception {
        kafka.send("deposits-commands", USER,
                new ChargeDeposit(USER, "t-" + System.nanoTime(), AMOUNT * 2, DepositHistoryType.CHARGE_TRANSFER));
        Thread.sleep(500);
    }

    private static double avg(List<Long> ts) {
        return ts.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /** Product 서비스가 없으므로 FeignClient 를 Mock 으로 대체 */
    @org.springframework.boot.test.context.TestConfiguration
    static class ProductFeignMockConfig {
        @Bean @Primary
        ProductFeignClient productFeignClient() {
            var mock = mock(ProductFeignClient.class);
            when(mock.getProductDetail(anyString(), eq("p1")))
                .thenReturn(BaseResponse.success(200,
                    new io.devground.dbay.order.infrastructure.vo.ProductDetailResponse(
                        "p1", "sale-1", "seller1", "테스트상품",
                        "설명", "카테고리", 5000L,
                        io.devground.dbay.order.domain.vo.ProductStatus.ON_SALE,
                        List.of())));
            when(mock.getCartProducts(any()))
                .thenReturn(BaseResponse.success(200, List.of(
                    new io.devground.dbay.order.infrastructure.vo.CartProductsResponse(
                        "p1", "sale-1", "seller1", "테스트상품",
                        "설명", "썸네일", "카테고리", 5000L))));
            return mock;
        }
    }
}
