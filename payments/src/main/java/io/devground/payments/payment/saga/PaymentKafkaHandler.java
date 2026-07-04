package io.devground.payments.payment.saga;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import io.devground.core.commands.deposit.RefundDeposit;
import io.devground.core.commands.payment.DepositRefundCommand;
import io.devground.core.commands.payment.PaymentCreateCommand;
import io.devground.core.event.deposit.DepositChargeFailed;
import io.devground.core.event.deposit.DepositChargedSuccess;
import io.devground.core.event.deposit.DepositRefundFailed;
import io.devground.core.event.payment.PaymentCreatedEvent;
import io.devground.core.event.payment.PaymentCreatedFailed;
import io.devground.core.model.vo.DepositHistoryType;

import io.devground.payments.payment.model.dto.request.RefundRequest;
import io.devground.payments.payment.model.dto.request.TossRefundRequest;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 관련 Kafka 핸들러.
 *
 * - PaymentCreateCommand: Kafka 비교 전략에서만 사용 (Feign 전략 미사용)
 * - DepositRefundCommand: 환불 플로우
 * - DepositChargedSuccess/DepositChargeFailed: Toss 결제 콜백
 */
@Slf4j
@Component
@KafkaListener(
	topics = {
		"${payments.command.topic.purchase}",
		"${payments.event.topic.name}",
		"${deposits.event.topic.payment}"
	}
)
@RequiredArgsConstructor
public class PaymentKafkaHandler {
	private final PaymentService paymentService;
	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${payments.event.topic.purchase}")
	private String paymentPurchaseEventTopic;

	@Value("${deposits.command.topic.name}")
	private String depositsCommandTopic;

	/**
	 * Kafka 비교 전략용: PaymentCreateCommand 를 수신하여 결제 처리.
	 * Feign 전략에서는 사용되지 않음 (OpenFeign 이 직접 PaymentController 호출).
	 */
	@KafkaHandler
	public void handleEvent(@Payload PaymentCreateCommand command) {
		try {
			PaymentConfirmRequest request = new PaymentConfirmRequest(
				command.orderCode(), true, command.totalAmount(), null, command.productCodes()
			);
			Payment payment = paymentService.process(command.userCode(), request);

			// 결제 성공 이벤트 → OrderSaga 가 후속 처리
			PaymentCreatedEvent event = new PaymentCreatedEvent(
				command.userCode(),
				command.totalAmount(),
				DepositHistoryType.PAYMENT_INTERNAL,
				command.orderCode(),
				command.productCodes()
			);
			kafkaTemplate.send(paymentPurchaseEventTopic, event.orderCode(), event);
			log.info("[KafkaPayment] 결제 성공 이벤트 발행: orderCode={}", command.orderCode());

		} catch (Exception e) {
			log.error("[KafkaPayment] 결제 실패: orderCode={}", command.orderCode(), e);
			PaymentCreatedFailed failedEvent = new PaymentCreatedFailed(
				command.orderCode(),
				command.userCode(),
				"결제 처리 중 오류: " + e.getMessage()
			);
			kafkaTemplate.send(paymentPurchaseEventTopic, command.orderCode(), failedEvent);
		}
	}

	//예치금 환불(결제 취소)
	@KafkaHandler
	public void handleEvent(@Payload DepositRefundCommand command) {
		RefundRequest request = new RefundRequest(command.userCode(), command.orderCode(), command.amount());
		paymentService.refund(request);

		RefundDeposit refundDeposit = new RefundDeposit(command.userCode(), command.amount(), DepositHistoryType.REFUND_INTERNAL);
		kafkaTemplate.send(depositsCommandTopic, refundDeposit);
	}

	//예치금 충전 성공 (Toss 결제 완료 콜백)
	@KafkaHandler
	public void handleEvent(@Payload DepositChargedSuccess depositChargedSuccessEvent) {
		log.info("예치금 충전 완료 userCode : {}", depositChargedSuccessEvent.userCode());
		paymentService.applyDepositCharge(depositChargedSuccessEvent.userCode());
	}

	//예치금 충전 실패 (Toss 결제 실패 콜백)
	@KafkaHandler
	public void handleEvent(@Payload DepositChargeFailed depositChargeFailed) {
		log.info("예치금 충전 실패 userCode: {}", depositChargeFailed.userCode());
		TossRefundRequest request = new TossRefundRequest(depositChargeFailed.userCode(), depositChargeFailed.paymentKey(), depositChargeFailed.amount());
		paymentService.tossRefund(request);
	}

	// 예치금 환불 실패 콜백
	@KafkaHandler
	public void handleEvent(@Payload DepositRefundFailed depositRefundFailed) {
		log.error("예치금 환불 실패 이벤트 수신 - userCode={}, amount={}, msg={}",
			depositRefundFailed.userCode(), depositRefundFailed.amount(), depositRefundFailed.msg());
		// TODO: 관리자 알림 발송 및 재시도 큐 등록
	}
}
