package io.devground.dbay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * commerce 모듈 통합 테스트 베이스 클래스.
 *
 * MySQL + Kafka 컨테이너를 Testcontainers 로 자동 실행한다.
 * 단위 테스트(H2)와 구분하기 위해 통합 테스트는 *IntegrationTest 이름 규칙을 사용한다.
 */
@Testcontainers
public abstract class CommerceIntegrationTestBase {

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

        // Eureka 비활성화
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");

        // Elasticsearch / OpenAI 비활성화 (통합 테스트에서 불필요)
        registry.add("spring.elasticsearch.uris", () -> "http://localhost:19200");
        registry.add("spring.ai.openai.api-key", () -> "test-key");

        // OpenFeign 결제 URL → 실제 payments 모듈이 아닌 테스트 더미
        registry.add("external.payment-url", () -> "http://localhost:18085");

        // Kafka 테스트 설정
        registry.add("spring.kafka.producer.properties.allow.auto.create.topics", () -> "true");
        registry.add("spring.kafka.consumer.properties.allow.auto.create.topics", () -> "true");
        registry.add("spring.kafka.consumer.group-id", () -> "commerce-test-group");
    }
}
