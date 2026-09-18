package io.devground.payments.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * payments 모듈 통합 테스트 베이스 클래스.
 *
 * MySQL + Kafka 컨테이너를 Testcontainers 로 자동 실행한다.
 * 컨테이너는 static 으로 선언되어 같은 클래스 내 모든 테스트 메서드가 공유하며,
 * JVM 당 하나의 컨테이너만 생성된다 (ryuk 컨테이너가 종료 시 정리).
 */
@Testcontainers
public abstract class PaymentIntegrationTestBase {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.0")
    )
            .withDatabaseName("dbay_db")
            .withUsername("mysqlId")
            .withPassword("mysqlPwd");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.5")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Eureka 비활성화 (통합 테스트에서는 불필요)
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");

        // Kafka 토픽 자동 생성 활성화 (테스트에서만)
        registry.add("spring.kafka.producer.properties.allow.auto.create.topics", () -> "true");
        registry.add("spring.kafka.consumer.properties.allow.auto.create.topics", () -> "true");

        // 테스트용 Kafka 그룹 ID
        registry.add("spring.kafka.consumer.group-id", () -> "payments-test-group");

        // Toss Payments 테스트용 시크릿 키
        registry.add("custom.toss.secretKey", () -> "test_toss_secret_key");
        registry.add("custom.toss.confirm-url", () -> "https://api.tosspayments.com/v1/payments/confirm");

        // OpenFeign 테스트 URL
        registry.add("external.openfeign-url", () -> "http://localhost:8085");

        // 외부 API 설정
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }
}
