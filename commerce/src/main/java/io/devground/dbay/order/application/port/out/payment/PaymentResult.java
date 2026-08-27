package io.devground.dbay.order.application.port.out.payment;

/**
 * 결제 처리 결과를 담는 공통 응답 모델.
 * FeignPaymentAdapter 와 KafkaPaymentAdapter 모두 이 타입을 반환한다.
 */
public record PaymentResult(
        boolean success,
        String orderCode,
        long amount,
        String message
) {
    public static PaymentResult success(String orderCode, long amount) {
        return new PaymentResult(true, orderCode, amount, "결제 성공");
    }

    public static PaymentResult fail(String orderCode, String message) {
        return new PaymentResult(false, orderCode, 0, message);
    }
}
