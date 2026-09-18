package io.devground.dbay.order.domain.vo;

/**
 * 결제 상태 폴링용 최소 조회 결과.
 *
 * <p>주문 상세({@code OrderDetailDescription})를 쓰지 않는 이유는 비용이다.
 * 폴링은 결제 1건당 여러 번 호출되므로 총 요청 수가 3~4배가 된다.
 * 현재 처리량 상한을 정하는 게 DB CPU 이므로(설계 §1.1) 이 조회는
 * <b>인덱스로 한 행만 읽고 조인을 하지 않아야</b> 한다.
 */
public record OrderPaymentStatus(String orderCode, String userCode, OrderStatus orderStatus) {

    /** 아직 결제 결과를 기다리는가 — 클라이언트가 폴링을 계속할지 판단하는 신호. */
    public boolean isAwaitingPayment() {
        return orderStatus.isAwaitingPayment();
    }
}
