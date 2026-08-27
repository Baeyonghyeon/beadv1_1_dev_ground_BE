package io.devground.payments.settlement.controller;

import java.time.LocalDateTime;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.devground.core.model.web.BaseResponse;

import io.devground.payments.settlement.model.dto.request.CreateSettlementRequest;
import io.devground.payments.settlement.model.dto.response.SettlementResponse;
import io.devground.payments.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settlements")
public class SettlementController {

	private final SettlementService settlementService;
	private final JobLauncher jobLauncher;
	private final Job settlementJob;

	/**
	 * 판매자별 정산 내역 조회
	 * GET /api/settlements/seller?page=0&size=20
	 */
	@GetMapping("/seller")
	public BaseResponse<Page<SettlementResponse>> getSettlementsBySeller(
		@RequestHeader("X-CODE") String sellerCode,
		@PageableDefault Pageable pageable) {

		Page<SettlementResponse> settlements = settlementService.getSettlementsBySeller(sellerCode, pageable);
		return BaseResponse.success(200, settlements, "판매자 정산 내역 조회 성공");
	}

	/**
	 * 정산 생성
	 * POST /api/settlements
	 */
	@PostMapping("")
	public BaseResponse<SettlementResponse> createSettlements(
		@RequestBody @Validated CreateSettlementRequest request) {

		SettlementResponse settlement = settlementService.createSettlement(request);
		return BaseResponse.success(201, settlement, "정산 생성 성공");
	}

	/**
	 * 정산 배치 수동 실행 (성능 테스트용)
	 * POST /api/settlements/trigger-batch
	 */
	@PostMapping("/trigger-batch")
	public BaseResponse<String> triggerBatch() {
		log.info("정산 배치 수동 실행 요청");
		try {
			JobParameters params = new JobParametersBuilder()
				.addString("executeTime", LocalDateTime.now().toString())
				.toJobParameters();

			jobLauncher.run(settlementJob, params);
			log.info("정산 배치 실행 완료");
			return BaseResponse.success(200, "배치 실행 완료", "ok");
		} catch (Exception e) {
			log.error("정산 배치 실행 실패", e);
			return BaseResponse.success(500, e.getMessage(), "배치 실행 실패");
		}
	}

}
