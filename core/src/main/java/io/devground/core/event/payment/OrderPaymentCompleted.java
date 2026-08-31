package io.devground.core.event.payment;

/**
 * 결제 성공 이벤트 (payments → commerce).
 * 토픽 {@code payments-purchase-events}, 파티션 키는 {@code userCode}.
 */
public record OrderPaymentCompleted(
	String orderCode,
	String userCode,
	String paymentCode,
	Long amount,
	java.util.List<String> productCodes
) {
}
