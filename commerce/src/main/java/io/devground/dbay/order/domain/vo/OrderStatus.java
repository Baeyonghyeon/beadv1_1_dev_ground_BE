package io.devground.dbay.order.domain.vo;

public enum OrderStatus {
    ALL,
    /**
     * 결제 미확정 — 주문은 접수됐으나 결제 결과를 아직 받지 못한 상태.
     *
     * <p>{@code order.payment.strategy=kafka} 인 비동기 경로에서만 생긴다.
     * 화면에는 "결제 대기중" 으로 보여준다.
     * 이 상태로 오래 머무는 주문은 {@code PendingPaymentReconciler} 가 회수한다.
     */
    PAYMENT_PENDING,
    /** 결제 실패(잔액 부족 등) — 종착 상태. */
    PAYMENT_FAILED,
    /**
     * 결제 완료, 후처리 대기.
     *
     * <p>⚠️ 동기 결제 경로의 기본 생성 상태이기도 하다. {@code PAYMENT_PENDING} 과 혼동하지 말 것 —
     * 이쪽은 <b>돈이 이미 빠진</b> 상태다.
     */
    PENDING,
    PAID,
    START_DELIVERY,
    DELIVERED,
    CONFIRMED,
    CANCELLED;

    public boolean isCancellable() {
        return switch (this) {
            case PAYMENT_PENDING, PENDING, PAID, START_DELIVERY -> true;
            default -> false;
        };
    }

    /** 결제 결과를 기다리는 중인가 — 회수 스케줄러의 대상 판정. */
    public boolean isAwaitingPayment() {
        return this == PAYMENT_PENDING;
    }
}
