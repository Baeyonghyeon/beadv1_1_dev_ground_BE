package io.devground.payments.deposit.infrastructure.adapter.in.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import io.devground.core.commands.deposit.ChargeDeposit;
import io.devground.core.commands.deposit.CreateDeposit;
import io.devground.core.commands.deposit.DeleteDeposit;
import io.devground.core.commands.deposit.RefundDeposit;
import io.devground.core.commands.deposit.SettlementChargeDeposit;
import io.devground.core.commands.deposit.WithdrawDeposit;
import io.devground.core.event.deposit.DepositChargeFailed;
import io.devground.core.event.deposit.DepositChargedSuccess;
import io.devground.core.event.deposit.DepositCreateFailed;
import io.devground.core.event.deposit.DepositCreatedSuccess;
import io.devground.core.event.deposit.DepositDeleteFailed;
import io.devground.core.event.deposit.DepositDeletedSuccess;
import io.devground.core.event.deposit.DepositRefundFailed;
import io.devground.core.event.deposit.DepositRefundedSuccess;
import io.devground.core.event.deposit.DepositWithdrawFailed;
import io.devground.core.event.deposit.DepositWithdrawnSuccess;
import io.devground.core.event.deposit.SettlementDepositChargeFailed;
import io.devground.core.event.deposit.SettlementDepositChargedSuccess;

import io.devground.payments.deposit.application.service.DepositEventApplication;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.deposit.domain.depositHistory.DepositHistory;
import io.devground.payments.deposit.domain.depositHistory.DepositHistoryType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@KafkaListener(
	topics = {
		"${deposits.command.topic.name}",
		"${deposits.command.topic.join}",
		"${deposits.command.topic.purchase}"
	}
)
public class DepositKafkaConsumer {

	private final DepositEventApplication depositEventApplication;
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final String depositsEventTopicName;
	private final String depositsJoinEventTopicName;
	private final String depositsPurchaseEventTopicName;
	private final String depositsPaymentEventTopicName;

	public DepositKafkaConsumer(DepositEventApplication depositEventApplication, KafkaTemplate<String, Object> kafkaTemplate,
		@Value("${deposits.event.topic.name}") String depositsEventTopicName,
		@Value("${deposits.event.topic.payment}") String depositsPaymentEventTopicName,
		@Value("${deposits.event.topic.join}") String depositsJoinEventTopicName,
		@Value("${deposits.event.topic.purchase}") String depositsPurchaseEventTopicName
	) {
		this.depositEventApplication = depositEventApplication;
		this.depositsPaymentEventTopicName = depositsPaymentEventTopicName;
		this.kafkaTemplate = kafkaTemplate;
		this.depositsEventTopicName = depositsEventTopicName;
		this.depositsJoinEventTopicName = depositsJoinEventTopicName;
		this.depositsPurchaseEventTopicName = depositsPurchaseEventTopicName;
	}

	@KafkaHandler
	public void handleCreateCommand(@Payload CreateDeposit command) {

		try {
			log.info("CreateDeposit userCode: {}", command.userCode());
			Deposit deposit = depositEventApplication.createDeposit(command.userCode());

			DepositCreatedSuccess depositCreatedEvent = new DepositCreatedSuccess(
				deposit.getUserCode(),
				deposit.getCode()
			);

			kafkaTemplate.send(depositsJoinEventTopicName, command.userCode(), depositCreatedEvent);
			log.error("예치금을 생성 성공 했습니다! userCode={}, depositCode={}", deposit.getUserCode(), deposit.getCode());

		} catch (Exception e) {

			DepositCreateFailed depositCreateFailed = new DepositCreateFailed(
				command.userCode(),
				"예치금 생성에 실패했어요"
			);

			kafkaTemplate.send(depositsJoinEventTopicName, command.userCode(), depositCreateFailed);
		}
	}

	@KafkaHandler
	public void handleChargeCommand(@Payload ChargeDeposit command) {
		log.info("Received ChargeDeposit command: {}", command);

		try {
			DepositHistory depositHistory = depositEventApplication.charge(
				command.userCode(),
				DepositHistoryType.valueOf(command.type().name()),
				command.amount()
			);

			log.info("depositCharge userCode : {}",command.userCode());


			DepositChargedSuccess depositChargedSuccessEvent = new DepositChargedSuccess(
				depositHistory.getUserCode(),
				depositHistory.getCode(),
				depositHistory.getAmount(),
				depositHistory.getBalanceAfter()
			);

			log.info("depositChargeSuccess userCode : {}",command.userCode());

			kafkaTemplate.send(depositsPaymentEventTopicName, depositChargedSuccessEvent);

			log.info("예치금 충전 완료: userCode={}, amount={}", command.userCode(), command.amount());

		} catch (Exception e) {

			DepositChargeFailed depositChargeFailed = new DepositChargeFailed(
				command.userCode(),
				command.paymentKey(),
				command.amount(),
				"예치금 충전에 실패했어요"
			);

			kafkaTemplate.send(depositsPaymentEventTopicName, depositChargeFailed);
		}
	}

	@KafkaHandler
	public void handleWithdrawCommand(@Payload WithdrawDeposit command) {
		log.info("Received WithdrawDeposit command: {}", command);

		try {
			DepositHistory depositHistory = depositEventApplication.withdraw(
				command.userCode(),
				DepositHistoryType.valueOf(command.type().name()),
				command.amount()
			);

			DepositWithdrawnSuccess depositWithdrawnSuccess = new DepositWithdrawnSuccess(
				depositHistory.getUserCode(),
				depositHistory.getCode(),
				depositHistory.getAmount(),
				depositHistory.getBalanceAfter(),
				command.orderCode(),
				command.productCodes()
			);

			kafkaTemplate.send(depositsPurchaseEventTopicName, command.orderCode(), depositWithdrawnSuccess);

			log.info("예치금 인출 완료: userCode={}, amount={}", command.userCode(), command.amount());

		} catch (Exception e) {
			log.error("예치금을 인출하는데 오류가 발생했습니다!", e);

			DepositWithdrawFailed depositWithdrawFailed = new DepositWithdrawFailed(
				command.userCode(),
				command.amount(),
				"예치금 인출에 실패했어요",
				command.orderCode()
			);

			kafkaTemplate.send(depositsPurchaseEventTopicName, command.orderCode(), depositWithdrawFailed);
		}
	}

	@KafkaHandler
	public void handleRefundCommand(@Payload RefundDeposit command) {
		log.info("Received RefundDeposit command: {}", command);

		try {
			DepositHistory depositHistory = depositEventApplication.refund(
				command.userCode(),
				DepositHistoryType.valueOf(command.type().name()),
				command.amount()
			);

			DepositRefundedSuccess depositRefundedSuccessEvent = new DepositRefundedSuccess(
				depositHistory.getUserCode(),
				depositHistory.getCode(),
				depositHistory.getAmount(),
				depositHistory.getBalanceAfter()
			);

			kafkaTemplate.send(depositsPaymentEventTopicName, command.userCode(), depositRefundedSuccessEvent);

			log.info("예치금 환불 완료: userCode={}, amount={}", command.userCode(), command.amount());

		} catch (Exception e) {

			log.error("예치금을 환불하는데 오류가 발생했습니다!", e);

			DepositRefundFailed depositRefundFailed = new DepositRefundFailed(
				command.userCode(),
				command.amount(),
				"예치금 환불에 실패했어요"
			);

			kafkaTemplate.send(depositsPaymentEventTopicName, depositRefundFailed);
		}
	}

	@KafkaHandler
	public void handleDeleteCommand(@Payload DeleteDeposit command) {
		log.info("Received DeleteDeposit command: {}", command);

		try {
			depositEventApplication.deleteDeposit(command.userCode());

			kafkaTemplate.send(depositsJoinEventTopicName, command.userCode(),
				new DepositDeletedSuccess(command.userCode(), "예치금 삭제 완료"));

			log.info("예치금 삭제 완료: userCode={}", command.userCode());

		} catch (Exception e) {

			log.error("예치금을 삭제 하는데 오류가 발생했습니다!", e);

			DepositDeleteFailed depositDeleteFailed = new DepositDeleteFailed(command.userCode(),
				"예치금 삭제에 실패했어요"
			);

			kafkaTemplate.send(depositsJoinEventTopicName, command.userCode(), depositDeleteFailed);
		}
	}

	@KafkaHandler
	public void handleSettlementChargeCommand(@Payload SettlementChargeDeposit command) {
		log.info("Received SettlementChargeDeposit command: {}", command);

		try {
			DepositHistory depositHistory = depositEventApplication.charge(
				command.userCode(),
				DepositHistoryType.SETTLEMENT,
				command.amount()
			);

			SettlementDepositChargedSuccess settlementDepositChargedSuccess = new SettlementDepositChargedSuccess(
				depositHistory.getUserCode(),
				depositHistory.getCode(),
				depositHistory.getAmount(),
				depositHistory.getBalanceAfter(),
				command.orderCode()
			);

			kafkaTemplate.send(depositsEventTopicName, command.orderCode(), settlementDepositChargedSuccess);

			log.info("정산 예치금 충전 완료: userCode={}, amount={}, orderCode={}",
				command.userCode(), command.amount(), command.orderCode());

		} catch (Exception e) {
			log.error("정산 예치금을 충전하는데 오류가 발생했습니다!", e);

			SettlementDepositChargeFailed failedEvent = new SettlementDepositChargeFailed(
				command.userCode(),
				command.amount(),
				command.orderCode(),
				"정산 예치금 충전에 실패했어요"
			);

			kafkaTemplate.send(depositsEventTopicName, command.orderCode(), failedEvent);
		}
	}

}
