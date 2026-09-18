package io.devground.payments.support;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 정산 배치 수동 실행기.
 * payments 서버가 떠 있는 상태에서 이 테스트만 단독 실행하면 배치가 돌아간다.
 */
@SpringBootTest
@ActiveProfiles("batch")
class SettlementBatchRunner {

	@Autowired
	private JobLauncher jobLauncher;

	@Autowired
	private Job settlementJob;

	@Test
	void runBatch() throws Exception {
		System.out.println("==================================================");
		System.out.println("[BATCH] 정산 배치 Job 실행 시작");
		System.out.println("==================================================");

		JobParameters params = new JobParametersBuilder()
			.addString("executeTime", LocalDateTime.now().toString())
			.toJobParameters();

		long start = System.nanoTime();
		JobExecution execution = jobLauncher.run(settlementJob, params);
		long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		System.out.println();
		System.out.println("==================================================");
		System.out.println("[BATCH] 완료! JobStatus=" + execution.getStatus());
		System.out.println("  Total duration: " + totalMs + "ms");

		for (StepExecution step : execution.getStepExecutions()) {
			System.out.println();
			System.out.println("  Step: " + step.getStepName());
			System.out.println("    ReadCount:   " + step.getReadCount());
			System.out.println("    WriteCount:  " + step.getWriteCount());
			System.out.println("    SkipCount:   " + step.getSkipCount());
			long stepMs = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
			System.out.println("    Duration:    " + stepMs + "ms");
			System.out.println("    Status:      " + step.getExitStatus());
		}

		long totalItems = execution.getStepExecutions().stream()
			.mapToLong(StepExecution::getWriteCount).sum();
		double tps = (totalItems * 1000.0) / Math.max(1L, totalMs);
		System.out.println();
		System.out.println("  TPS: " + String.format("%.2f", tps) + " items/s");
		System.out.println("==================================================");
	}
}
