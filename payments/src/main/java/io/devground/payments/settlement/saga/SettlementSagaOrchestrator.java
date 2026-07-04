package io.devground.payments.settlement.saga;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.devground.core.commands.deposit.SettlementChargeDeposit;
import io.devground.core.event.deposit.SettlementDepositChargedSuccess;
import io.devground.core.events.settlement.SettlementCreatedSuccess;

import io.devground.payments.common.saga.entity.Saga;
import io.devground.payments.common.saga.service.SagaService;
import io.devground.payments.common.saga.vo.SagaStep;
import io.devground.payments.common.saga.vo.SagaType;
import io.devground.payments.settlement.model.entity.Settlement;
import io.devground.payments.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Settlement 정산 입금 Saga Orchestrator
 *
 * 역할:
 * 1. Settlement 정산 프로세스의 전체 흐름 제어
 * 2. Deposit 충전 커맨드 발행 및 결과 처리
 * 3. 정산 완료 이벤트 발행
 * 4. 실패 시 보상 트랜잭션 처리
 * 5. Saga 상태 관리
 */
@Slf4j
@Component
@Transactional
@RequiredArgsConstructor
public class SettlementSagaOrchestrator {

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final SagaService sagaService;
	private final SettlementRepository settlementRepository;

	@Value("${deposits.command.topic.name}")
	private String depositsCommandTopicName;

	@Value("${settlements.event.topic.name}")
	private String settlementsEventTopicName;

	/**
	 * 정산 입금 Saga 시작
	 * Settlement → Deposit 충전 커맨드 발행
	 */
	public void startSettlementDepositChargeSaga(SettlementChargeDeposit command) {

		String sagaId = sagaService.startSaga(command.orderCode(), SagaType.SETTLEMENT_DEPOSIT_CHARGE);

		log.info("정산 입금 Saga 시작 - SagaId: {}, OrderCode: {}, UserCode: {}, Amount: {}",
			sagaId, command.orderCode(), command.userCode(), command.amount());

		try {
			// Deposit 충전 커맨드 발행
			kafkaTemplate.send(depositsCommandTopicName, command);

			sagaService.updateStep(sagaId, SagaStep.SETTLEMENT_COMMAND_SENT);

			log.info("정산 입금 커맨드 발행 완료 - SagaId: {}, OrderCode: {}", sagaId, command.orderCode());

		} catch (Exception e) {
			log.error("정산 입금 커맨드 발행 실패 - SagaId: {}, OrderCode: {}, Exception: ",
				sagaId, command.orderCode(), e);

			sagaService.updateToFail(sagaId, "정산 입금 커맨드 발행 실패: " + e.getMessage());

			// 보상: Settlement 상태 FAILED 로 변경
			failSettlement(command.orderCode());
		}
	}

	/**
	 * Deposit 충전 성공 이벤트 처리
	 * Settlement 완료 이벤트 발행
	 */
	public void handleDepositChargeSuccess(SettlementDepositChargedSuccess event) {

		Saga saga = sagaService.findLatestSagaByReferenceCode(event.orderCode());
		String sagaId = saga.getSagaId();

		if (saga.getSagaStatus().isTerminal()) {
			log.info("이미 처리된 성공 Saga - SagaId: {}, OrderCode: {}", sagaId, event.orderCode());
			return;
		}

		log.info("정산 입금 성공 이벤트 수신 - SagaId: {}, OrderCode: {}, UserCode: {}, Amount: {}",
			sagaId, event.orderCode(), event.userCode(), event.amount());

		try {
			sagaService.updateStep(sagaId, SagaStep.DEPOSIT_CHARGE_SUCCESS);

			// Settlement 완료 이벤트 발행
			SettlementCreatedSuccess settlementEvent = SettlementCreatedSuccess.builder()
				.orderCodes(List.of(event.orderCode()))
				.build();

			kafkaTemplate.send(settlementsEventTopicName, settlementEvent);

			sagaService.updateStep(sagaId, SagaStep.SETTLEMENT_EVENT_PUBLISHED);
			sagaService.updateToSuccess(sagaId);

			log.info("정산 완료 이벤트 발행 완료 - SagaId: {}, OrderCode: {}", sagaId, event.orderCode());

		} catch (Exception e) {
			log.error("정산 완료 이벤트 발행 실패 - SagaId: {}, OrderCode: {}, Exception: ",
				sagaId, event.orderCode(), e);

			sagaService.updateToFail(sagaId, "정산 완료 이벤트 발행 실패: " + e.getMessage());

			// 보상: Settlement 상태 FAILED 로 변경
			failSettlement(event.orderCode());
		}
	}

	/**
	 * Deposit 충전 실패 처리
	 */
	public void handleDepositChargeFailure(String orderCode, String errorMessage) {

		try {
			Saga saga = sagaService.findLatestSagaByReferenceCode(orderCode);
			String sagaId = saga.getSagaId();

			if (saga.getSagaStatus().isTerminal()) {
				log.info("이미 처리된 실패 Saga - SagaId: {}, OrderCode: {}", sagaId, orderCode);
				return;
			}

			log.error("정산 입금 실패 - SagaId: {}, OrderCode: {}, ErrorMessage: {}",
				sagaId, orderCode, errorMessage);

			sagaService.updateToFail(sagaId, "정산 입금 실패: " + errorMessage);

			// 보상: Settlement 상태 FAILED로 변경
			failSettlement(orderCode);

		} catch (Exception e) {
			log.error("정산 입금 실패 처리 중 오류 - OrderCode: {}, Exception: ", orderCode, e);
		}
	}

	/**
	 * Settlement 상태를 FAILED 로 변경한다.
	 * 정산 입금이 실패한 경우 호출하여 데이터 정합성을 유지한다.
	 */
	private void failSettlement(String orderCode) {
		settlementRepository.findByOrderCode(orderCode).ifPresent(settlement -> {
			settlement.fail();
			settlementRepository.save(settlement);
			log.warn("정산 실패로 Settlement 상태 변경: orderCode={}, status=SETTLEMENT_FAILED", orderCode);
		});
	}
}
