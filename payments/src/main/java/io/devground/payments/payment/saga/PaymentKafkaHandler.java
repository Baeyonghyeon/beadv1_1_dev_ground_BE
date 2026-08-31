package io.devground.payments.payment.saga;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import io.devground.core.commands.deposit.RefundDeposit;
import io.devground.core.commands.payment.DepositRefundCommand;
import io.devground.core.commands.payment.PaymentRequestedCommand;
import io.devground.core.event.payment.OrderPaymentCompleted;
import io.devground.core.event.payment.OrderPaymentFailed;
import io.devground.core.event.payment.PaymentFailReason;
import io.devground.core.event.deposit.DepositChargeFailed;
import io.devground.core.event.deposit.DepositChargedSuccess;
import io.devground.core.event.deposit.DepositRefundFailed;
import io.devground.core.model.vo.DepositHistoryType;

import io.devground.payments.payment.model.dto.request.RefundRequest;
import io.devground.payments.payment.model.dto.request.TossRefundRequest;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.service.PaymentService;
import io.devground.payments.deposit.application.exception.ServiceException;
import io.devground.payments.deposit.application.exception.vo.ServiceErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 관련 Kafka 핸들러.
 *
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

	/**
	 * 주문 결제 요청 (commerce → payments, 비동기 결제 경로).
	 *
	 * <p><b>{@code PaymentServiceImpl.process()} 를 그대로 재사용한다.</b> 동기 경로와 완전히 같은
	 * 트랜잭션·비관적 락·이력 전략을 쓴다는 뜻이다. 그 안의 락 순서와 {@code DepositHistoryRecorder}
	 * 처리는 lock wait timeout(50초)을 잡느라 어렵게 맞춘 것이라 건드리지 않는다.
	 * 바뀐 것은 <b>호출자가 HTTP 워커 스레드에서 Kafka 컨슈머로 옮겨간 것</b>뿐이다.
	 *
	 * <p><b>실패를 두 종류로 나눠 발행한다.</b> commerce 가 보상 필요 여부를 판정해야 하기 때문이다:
	 * 잔액 부족은 차감이 없어 주문만 접으면 되지만, 그 외 예외는 차감됐을 수 있어 회수 대상이다.
	 *
	 * <p><b>중복 커맨드</b>는 {@code Payment.orderCode} 의 unique 제약에 걸린다.
	 * 이때는 실패가 아니라 <b>이미 성공한 것</b>이므로, 기존 결제를 찾아 같은 성공 이벤트를 다시 발행한다.
	 * 실패로 처리하면 정상 결제된 주문이 취소되어 버린다.
	 */
	@KafkaHandler
	public void handleEvent(@Payload PaymentRequestedCommand command) {
		try {
			Payment payment = paymentService.process(
				command.userCode(),
				new PaymentConfirmRequest(
					command.orderCode(),
					true,
					command.amount(),
					null,
					command.productCodes()
				)
			);

			publishCompleted(command, payment.getCode());

		} catch (DataIntegrityViolationException e) {
			// 중복 커맨드 — 이미 처리된 결제다. 결과 이벤트만 다시 내보낸다.
			paymentService.findByOrderCode(command.orderCode()).ifPresentOrElse(
				existing -> {
					log.info("[payment:kafka] 중복 결제 커맨드 — 기존 결과 재발행: orderCode={}", command.orderCode());
					publishCompleted(command, existing.getCode());
				},
				() -> {
					log.error("[payment:kafka] unique 위반인데 기존 결제를 못 찾음: orderCode={}", command.orderCode(), e);
					publishFailed(command, PaymentFailReason.INTERNAL_ERROR, e.getMessage());
				}
			);

		} catch (ServiceException e) {
			// process() 가 던지는 업무 실패. 둘 다 **차감이 일어나지 않았으므로 보상 불필요**다.
			PaymentFailReason reason = switch (e.getErrorCode()) {
				case INSUFFICIENT_BALANCE -> PaymentFailReason.INSUFFICIENT_BALANCE;
				case DEPOSIT_NOT_FOUND -> PaymentFailReason.DEPOSIT_NOT_FOUND;
				default -> PaymentFailReason.INTERNAL_ERROR;
			};

			log.info("[payment:kafka] 결제 실패: orderCode={}, reason={}", command.orderCode(), reason);
			publishFailed(command, reason, e.getMessage());
		}
		// 그 외 예외는 잡지 않는다 — 재시도 후 DLT 로 보내고, 주문은 회수 스케줄러가 처리한다.
		// 여기서 삼키면 "차감됐는데 실패로 기록" 같은 잘못된 확정이 생긴다.
	}

	private void publishCompleted(PaymentRequestedCommand command, String paymentCode) {
		kafkaTemplate.send(paymentPurchaseEventTopic, command.userCode(),
			new OrderPaymentCompleted(
				command.orderCode(), command.userCode(), paymentCode,
				command.amount(), command.productCodes()));
	}

	private void publishFailed(PaymentRequestedCommand command, PaymentFailReason reason, String message) {
		kafkaTemplate.send(paymentPurchaseEventTopic, command.userCode(),
			new OrderPaymentFailed(
				command.orderCode(), command.userCode(), command.amount(), reason, message));
	}

	// 예치금 환불 실패 콜백
	@KafkaHandler
	public void handleEvent(@Payload DepositRefundFailed depositRefundFailed) {
		log.error("예치금 환불 실패 이벤트 수신 - userCode={}, amount={}, msg={}",
			depositRefundFailed.userCode(), depositRefundFailed.amount(), depositRefundFailed.msg());
		// TODO: 관리자 알림 발송 및 재시도 큐 등록
	}
}
