package io.devground.payments.settlement.scheduler;

import java.time.LocalDateTime;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementJobScheduler {

	private final JobLauncher jobLauncher;
	private final Job settlementJob;

	/**
	 * 정산 배치 작업 실행
	 * - 매달 2일 새벽 2시 실행 (cron: 0 0 2 2 * *)
	 * - 2주 지난 확정 주문에 대한 정산 처리
	 */
	// bench 프로파일에서 "-" 로 비활성화 (부하 테스트 중 정산 배치 개입 방지)
	@Scheduled(cron = "${custom.settlement.cron:0 0 2 2 * *}")
	public void runSettlementBatch() {
		try {
			log.info("=== 정산 배치 작업 시작 ===");

			JobParameters jobParameters = new JobParametersBuilder()
				.addString("executeTime", LocalDateTime.now().toString())
				.toJobParameters();

			jobLauncher.run(settlementJob, jobParameters);

			log.info("=== 정산 배치 작업 완료 ===");

		} catch (Exception e) {
			log.error("정산 배치 작업 실행 중 오류 발생", e);
		}
	}
}
