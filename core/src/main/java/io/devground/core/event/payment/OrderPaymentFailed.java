package io.devground.core.event.payment;

/**
 * 결제 실패 이벤트 (payments → commerce).
 * 토픽 {@code payments-purchase-events}, 파티션 키는 {@code userCode}.
 */
public record OrderPaymentFailed(
	String orderCode,
	String userCode,
	Long amount,
	PaymentFailReason reason,
	String message
) {
}
