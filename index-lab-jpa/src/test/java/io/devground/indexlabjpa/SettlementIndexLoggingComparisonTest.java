package io.devground.indexlabjpa;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.devground.indexlabjpa.domain.LabSettlement;
import io.devground.indexlabjpa.domain.LabSettlementRepository;
import io.devground.indexlabjpa.domain.LabSettlementStatus;
import jakarta.persistence.EntityManager;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.jpa.properties.hibernate.format_sql=true",
	"spring.jpa.properties.hibernate.generate_statistics=true",
	"logging.level.org.hibernate.SQL=DEBUG",
	"logging.level.org.hibernate.orm.jdbc.bind=TRACE",
	"logging.level.p6spy=INFO"
})
@DisplayName("JPA 인덱스 전/후 로그 비교 (PostgreSQL)")
@EnabledIfEnvironmentVariable(named = "RUN_INDEX_LAB", matches = "true")
class SettlementIndexLoggingComparisonTest {

	private static final Logger log = LoggerFactory.getLogger(SettlementIndexLoggingComparisonTest.class);

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
		.withDatabaseName("indexlab")
		.withUsername("postgres")
		.withPassword("postgres");

	@DynamicPropertySource
	static void registerProps(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private LabSettlementRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private DataSource dataSource;

	@Test
	@Transactional
	@DisplayName("같은 JPA 쿼리에서 인덱스 전/후 평균 시간을 비교한다")
	void compareIndexOnOffByJpaQuery() {
		initSchema();
		seedData();
		analyzeTable();

		dropIndexIfExists();
		double withoutIndexMs = runAndMeasure("NO_INDEX");
		String withoutPlan = explainQueryPlan();

		createIndex();
		analyzeTable();
		double withIndexMs = runAndMeasure("WITH_INDEX");
		String withPlan = explainQueryPlan();

		log.info("========================================");
		log.info("[INDEX LAB] datasource = {}", dataSource.getClass().getName());
		log.info("[INDEX LAB] withoutIndexAvgMs = {}", String.format("%.3f", withoutIndexMs));
		log.info("[INDEX LAB] withIndexAvgMs    = {}", String.format("%.3f", withIndexMs));
		log.info("[INDEX LAB] improvement(%)    = {}", String.format("%.2f",
			((withoutIndexMs - withIndexMs) / Math.max(0.001, withoutIndexMs)) * 100.0));
		log.info("[INDEX LAB] EXPLAIN without index:\n{}", withoutPlan);
		log.info("[INDEX LAB] EXPLAIN with index:\n{}", withPlan);
		log.info("========================================");
	}

	private void initSchema() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS lab_settlement");
		jdbcTemplate.execute("""
			CREATE TABLE lab_settlement (
				id BIGSERIAL PRIMARY KEY,
				seller_code VARCHAR(32) NOT NULL,
				settlement_status VARCHAR(24) NOT NULL,
				settlement_date TIMESTAMP NOT NULL,
				settlement_balance BIGINT NOT NULL
			)
			""");
	}

	private void seedData() {
		jdbcTemplate.execute("""
			INSERT INTO lab_settlement (seller_code, settlement_status, settlement_date, settlement_balance)
			SELECT
				'SELLER-' || LPAD(((gs % 1200) + 1)::text, 4, '0'),
				CASE
					WHEN gs % 10 < 6 THEN 'SETTLEMENT_CREATED'
					WHEN gs % 10 < 9 THEN 'SETTLEMENT_SUCCESS'
					ELSE 'SETTLEMENT_FAILED'
				END,
				TIMESTAMP '2024-01-01'
				  + ((gs % 730) || ' days')::interval
				  + ((gs % 24) || ' hours')::interval,
				10000 + ((gs * 17) % 300000)
			FROM generate_series(1, 300000) gs
			""");
	}

	private void analyzeTable() {
		jdbcTemplate.execute("ANALYZE lab_settlement");
	}

	private void createIndex() {
		jdbcTemplate.execute("""
			CREATE INDEX IF NOT EXISTS idx_lab_settlement_seller_status_date
			ON lab_settlement (seller_code, settlement_status, settlement_date DESC)
			""");
	}

	private void dropIndexIfExists() {
		jdbcTemplate.execute("DROP INDEX IF EXISTS idx_lab_settlement_seller_status_date");
	}

	private double runAndMeasure(String label) {
		// warm-up
		for (int i = 0; i < 5; i++) {
			runQueryOnce();
		}

		int runs = 20;
		long totalNanos = 0;
		for (int i = 0; i < runs; i++) {
			long start = System.nanoTime();
			runQueryOnce();
			totalNanos += (System.nanoTime() - start);
		}

		double avgMs = (totalNanos / (double) runs) / 1_000_000.0;
		log.info("[INDEX LAB] {} avgMs={}", label, String.format("%.3f", avgMs));
		return avgMs;
	}

	private List<LabSettlement> runQueryOnce() {
		List<LabSettlement> result = repository.findBySellerCodeAndSettlementStatusOrderBySettlementDateDesc(
			"SELLER-0001",
			LabSettlementStatus.SETTLEMENT_CREATED,
			PageRequest.of(0, 200)
		);
		entityManager.clear();
		return result;
	}

	private String explainQueryPlan() {
		List<String> lines = jdbcTemplate.queryForList("""
			EXPLAIN (ANALYZE, BUFFERS)
			SELECT id, seller_code, settlement_status, settlement_date, settlement_balance
			FROM lab_settlement
			WHERE seller_code = 'SELLER-0001'
			  AND settlement_status = 'SETTLEMENT_CREATED'
			ORDER BY settlement_date DESC
			LIMIT 200
			""", String.class);
		return String.join("\n", lines);
	}
}
