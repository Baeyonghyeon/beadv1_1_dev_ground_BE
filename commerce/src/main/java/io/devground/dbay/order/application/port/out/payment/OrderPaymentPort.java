package io.devground.dbay.order.application.port.out.payment;

import java.util.List;

/**
 * 결제 처리를 위한 아웃바운드 포트 (전략 인터페이스).
 * 구현체:
 * - {@code FeignPaymentAdapter} : OpenFeign 동기 호출 (TO-BE)
 * - {@code KafkaPaymentAdapter} : Kafka 비동기 Saga (AS-IS, 비교 벤치마크용)
 */
public interface OrderPaymentPort {

    /**
     * 주문에 대한 결제를 처리한다.
     *
     * @param userCode     사용자 코드
     * @param orderCode    주문 코드
     * @param totalAmount  결제 금액
     * @param productCodes 상품 코드 목록
     * @return 결제 처리 결과
     */
    PaymentResult processPayment(String userCode, String orderCode, long totalAmount, List<String> productCodes);
}
