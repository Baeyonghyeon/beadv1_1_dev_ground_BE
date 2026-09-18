package io.devground.dbay.order.application.vo;

/**
 * 주문 접수 결과 — 응용 계층이 "무슨 일이 있었는지" 를 알려주고, 컨트롤러가 HTTP 로 옮긴다.
 *
 * <p><b>왜 응용 계층에서 예외를 던지지 않는가:</b> {@code createOrderByOne} 은 {@code @Transactional} 이다.
 * 예외를 던지면 트랜잭션이 롤백되어 <b>주문 기록 자체가 사라진다.</b>
 * 결제가 거절된 주문도 "시도했다" 는 기록은 남아야 하고, 결제 여부가 불명인 주문은
 * 회수 대상으로 <b>반드시 남아 있어야</b> 한다. 그래서 정상 반환으로 커밋시키고 상태만 전달한다.
 */
public record OrderAcceptance(String orderCode, State state, String message) {

    public enum State {
        /** 동기 경로 — 응답 시점에 결제가 확정됐다 (204) */
        SETTLED,
        /** 비동기 경로 — 결제 결과를 기다린다. 클라이언트는 폴링한다 (202) */
        AWAITING_PAYMENT,
        /** 결제가 거절됐다 — 잔액 부족 등. 차감 없음이 확실하다 (400) */
        REJECTED,
        /** 결제 서비스에 닿지 못했다 — 차감 없음이 확실하다 (503) */
        PAYMENT_UNAVAILABLE,
        /** 결제 여부를 모른다 — 타임아웃. 주문은 회수 대상으로 남는다 (503) */
        PAYMENT_UNCERTAIN
    }

    public static OrderAcceptance settled(String orderCode) {
        return new OrderAcceptance(orderCode, State.SETTLED, "주문 생성 완료");
    }

    public static OrderAcceptance awaiting(String orderCode) {
        return new OrderAcceptance(orderCode, State.AWAITING_PAYMENT, "주문이 접수되었습니다. 결제를 처리하고 있습니다.");
    }

    public static OrderAcceptance rejected(String orderCode, String message) {
        return new OrderAcceptance(orderCode, State.REJECTED, message);
    }

    public static OrderAcceptance unavailable(String orderCode, String message) {
        return new OrderAcceptance(orderCode, State.PAYMENT_UNAVAILABLE, message);
    }

    public static OrderAcceptance uncertain(String orderCode, String message) {
        return new OrderAcceptance(orderCode, State.PAYMENT_UNCERTAIN, message);
    }

    /** 비동기 경로로 접수되어 클라이언트가 폴링해야 하는가 */
    public boolean awaitingPayment() {
        return state == State.AWAITING_PAYMENT;
    }
}
