package io.devground.core.commands.payment;

import java.time.Instant;
import java.util.List;

/**
 * 주문 접수 → 결제 요청 커맨드 (commerce → payments).
 *
 * <p>토픽 {@code payments-purchase-commands}, 파티션 키는 {@code userCode} 다.
 * 예치금 잔액이 유저 단위 자원이므로, 같은 유저의 결제를 한 파티션에서 직렬화해
 * 비관적 락 경합을 줄인다. 설계 문서 §5.2 참조.
 *
 * <p>{@code orderCode} 가 멱등키를 겸한다 — payments 는 {@code Payment.orderCode} 의
 * unique 제약으로 중복 커맨드를 걸러낸다.
 */
public record PaymentRequestedCommand(
	String orderCode,
	String userCode,
	Long amount,
	List<String> productCodes,
	Instant requestedAt
) {
}
