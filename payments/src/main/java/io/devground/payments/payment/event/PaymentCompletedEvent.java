package io.devground.payments.payment.event;

/**
 * 결제+예치금 차감이 완료된 후 발행되는 Spring 이벤트.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 으로 소비하여 예치금 이력을 비동기 저장한다.
 */
public record PaymentCompletedEvent(
        String userCode,
        String depositCode,
        long amount,
        long balanceAfter,
        String orderCode
) {
}
