package io.devground.dbay.order.application.port.out.payment;

import java.util.Optional;

/**
 * 결제 존재 여부 조회 포트 — 회수 스케줄러 전용.
 *
 * <p>"결제 커맨드가 유실된 것인가, 결과 이벤트가 유실된 것인가" 는
 * commerce 의 DB 만 봐서는 구분할 수 없다. 둘 다 주문이 {@code PAYMENT_PENDING} 으로 보인다.
 * payments 에 실제 결제 기록이 있는지 물어봐야 판정이 된다.
 *
 * <p>⚠️ 이 호출은 <b>트랜잭션 밖에서</b> 이뤄져야 한다. 커넥션을 쥔 채 원격 호출을 하면
 * 회수 배치가 결제 경로와 커넥션을 다툰다 (설계 문서 §6.4).
 */
public interface OrderPaymentLookupPort {

    Optional<PaymentSnapshot> findByOrderCode(String orderCode);

    /** payments 가 보관 중인 결제 기록의 최소 표현. */
    record PaymentSnapshot(String orderCode, String paymentCode, String paymentStatus, Long amount) {

        /** 결제가 실제로 완료된 기록인가. */
        public boolean isCompleted() {
            return "PAYMENT_COMPLETED".equals(paymentStatus);
        }
    }
}
