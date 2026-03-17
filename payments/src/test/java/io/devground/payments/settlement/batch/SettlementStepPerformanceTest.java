package io.devground.payments.settlement.batch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.devground.core.model.web.BaseResponse;
import io.devground.core.model.web.PageDto;
import io.devground.payments.settlement.client.OrderFeignClient;
import io.devground.payments.settlement.model.dto.UnsettledOrderItemResponse;
import io.devground.payments.settlement.repository.SettlementRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBatchTest
@ActiveProfiles("test")
@SpringBootTest(properties = {
	"spring.batch.job.enabled=false",
	"spring.batch.jdbc.initialize-schema=always",
	"spring.datasource.url=jdbc:h2:mem:settlement_perf;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"custom.toss.secretKey=test-secret-key",
	"spring.kafka.listener.auto-startup=false",
	"eureka.client.enabled=false",
	"spring.cloud.discovery.enabled=false",
	"logging.level.org.apache.kafka=ERROR",
	"logging.level.org.springframework.kafka=ERROR",
	"logging.level.org.springframework.batch=ERROR",
	"logging.level.io.devground.payments.settlement.batch=ERROR",
	"logging.level.io.devground.payments.settlement.batch.SettlementStepPerformanceTest=INFO"
})
@DisplayName("Settlement Step 성능 테스트")
@EnabledIfEnvironmentVariable(named = "RUN_PERFORMANCE_TESTS", matches = "true")
class SettlementStepPerformanceTest {

	private static final int WARM_UP_ITEMS = 1_000;
	private static final int MEASURE_ITEMS = 10_000;

	@Autowired
	private JobLauncherTestUtils jobLauncherTestUtils;

	@Autowired
	private JobRepositoryTestUtils jobRepositoryTestUtils;

	@Autowired
	private SettlementRepository settlementRepository;

	@MockitoBean
	private OrderFeignClient orderFeignClient;

	@BeforeEach
	void setUp() {
		settlementRepository.deleteAllInBatch();
		jobRepositoryTestUtils.removeJobExecutions();
	}

	@Test
	@Tag("performance")
	@DisplayName("settlementStep 처리량/소요시간을 측정한다")
	void measureSettlementStepPerformance() throws Exception {
		// warm-up
		stubOrderFeignClientWith(WARM_UP_ITEMS);
		runSettlementStepAndAssertCompleted();
		settlementRepository.deleteAllInBatch();
		jobRepositoryTestUtils.removeJobExecutions();

		// measure
		stubOrderFeignClientWith(MEASURE_ITEMS);
		long elapsedMs = runSettlementStepAndAssertCompleted();

		long persistedCount = settlementRepository.count();
		assertThat(persistedCount).isEqualTo(MEASURE_ITEMS);

		double tps = (MEASURE_ITEMS * 1000.0) / Math.max(1L, elapsedMs);
		log.info("[PERF] settlementStep items={}, elapsedMs={}, throughput={} items/s",
			MEASURE_ITEMS, elapsedMs, String.format("%.2f", tps));
	}

	private long runSettlementStepAndAssertCompleted() throws Exception {
		JobParameters jobParameters = new JobParametersBuilder()
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		long startNano = System.nanoTime();
		JobExecution execution = jobLauncherTestUtils.launchStep("settlementStep", jobParameters);
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);

		assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
		return elapsedMs;
	}

	private void stubOrderFeignClientWith(int totalItems) {
		given(orderFeignClient.getUnsettledOrderItems(anyInt(), anyInt()))
			.willAnswer(invocation -> {
				int page = invocation.getArgument(0);
				int size = invocation.getArgument(1);

				int start = page * size;
				int end = Math.min(start + size, totalItems);

				List<UnsettledOrderItemResponse> items = start >= totalItems
					? List.of()
					: IntStream.range(start, end)
						.mapToObj(this::createItem)
						.toList();

				long totalPages = (long)Math.ceil((double)totalItems / size);

				PageDto<UnsettledOrderItemResponse> pageDto = new PageDto<>(
					page + 1,
					size,
					totalPages,
					totalItems,
					items
				);

				return BaseResponse.success(200, pageDto, "ok");
			});
	}

	private UnsettledOrderItemResponse createItem(int index) {
		return new UnsettledOrderItemResponse(
			"ORDER-" + index,
			"BUYER-" + index,
			"ORDER-ITEM-" + index,
			"SELLER-" + (index % 1000),
			10_000L
		);
	}
}
