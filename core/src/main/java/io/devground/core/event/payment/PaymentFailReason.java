package io.devground.core.event.payment;

/**
 * 결제 실패 사유.
 *
 * <p>commerce 가 <b>보상이 필요한 실패</b>와 <b>불필요한 실패</b>를 구분하기 위해 존재한다.
 * 문자열 메시지로는 그 판단을 할 수 없다.
 */
public enum PaymentFailReason {

	/** 잔액 부족 — 차감이 일어나지 않았으므로 보상 불필요. 주문만 실패 처리한다. */
	INSUFFICIENT_BALANCE,

	/** 예치금 계정 없음 — 차감 없음, 보상 불필요. */
	DEPOSIT_NOT_FOUND,

	/** 그 외 예외 — <b>차감됐을 수 있다.</b> 보상 판단이 필요하다. */
	INTERNAL_ERROR
}
