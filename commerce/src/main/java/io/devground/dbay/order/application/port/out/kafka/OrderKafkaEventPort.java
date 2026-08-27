package io.devground.dbay.order.application.port.out.kafka;

import java.util.List;

/**
 * 주문 도메인의 Kafka 이벤트 발행 포트.
 *
 * publishOrderCreated, publishPaymentSuccessToDeposit 등은
 * Feign 전략에서 더 이상 사용하지 않음 (Kafka 비교 전략에서만 사용).
 */
public interface OrderKafkaEventPort {
	// Kafka 비교 전략용 (OrderSaga 가 소비)

	// Feign 전략용 (OrderApplication 에서 결제 성공 후 직접 호출)
	void publishDepositSuccessCompleteOrder(String userCode, String orderCode);
	void publishDepositSuccessCompleteDeleteCart(String userCode, String orderCode, List<String> productCodes);
	void publishDepositRefundCreated(String userCode, Long amount, String orderCode);
	void publishDepositSuccessCompleteProduct(String orderCode, List<String> productCodes);
}
