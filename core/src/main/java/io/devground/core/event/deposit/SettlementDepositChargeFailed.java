package io.devground.core.event.deposit;

/**
 * 정산 예치금 충전 실패 이벤트.
 * {@link io.devground.payments.settlement.saga.SettlementSagaOrchestrator} 가 소비하여
 * Saga 종료 + Settlement 상태 FAILED 변경을 수행한다.
 */
public record SettlementDepositChargeFailed(
	String userCode,
	Long amount,
	String orderCode,
	String msg
) {
}
