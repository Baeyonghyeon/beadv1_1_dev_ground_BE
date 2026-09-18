package io.devground.dbay.order.application.port.out.payment;

/**
 * 동기 결제 호출의 결과.
 *
 * <p><b>왜 성공/실패 두 가지로 부족한가:</b> 실패를 하나로 뭉치면
 * <b>"결제가 실제로 일어났는지"</b> 를 구분할 수 없다. 그런데 그 구분이 주문을 취소해도 되는지를 정한다.
 *
 * <table border="1">
 *   <caption>실패 종류와 안전한 처리</caption>
 *   <tr><th>결과</th><th>결제가 됐나</th><th>주문 취소</th><th>응답</th></tr>
 *   <tr><td>{@link Outcome#SUCCESS}</td><td>됨</td><td>—</td><td>204</td></tr>
 *   <tr><td>{@link Outcome#REJECTED}</td><td><b>확실히 안 됨</b> (잔액 부족 등)</td><td>안전</td><td>400</td></tr>
 *   <tr><td>{@link Outcome#UNAVAILABLE}</td><td><b>확실히 안 됨</b> (연결 거부)</td><td>안전</td><td>503</td></tr>
 *   <tr><td>{@link Outcome#UNKNOWN}</td><td><b>알 수 없음</b> (타임아웃)</td><td><b>절대 금지</b></td><td>503</td></tr>
 * </table>
 *
 * <p>{@code UNKNOWN} 이 핵심이다. payments 가 차감을 커밋한 뒤 응답만 유실된 경우가 여기 해당하는데,
 * 이때 주문을 취소하면 <b>돈은 빠졌는데 주문은 CANCELLED</b> 가 된다.
 * 실측된 "유령 주문" 892건이 이 경로다. 그래서 이 경우 주문을 {@code PAYMENT_PENDING} 으로 남겨
 * 회수 스케줄러가 payments 에 물어본 뒤 판정하게 한다.
 */
public record PaymentResult(
        Outcome outcome,
        String orderCode,
        long amount,
        String message
) {
    public enum Outcome {
        /** 결제 성공 */
        SUCCESS,
        /** 결제가 거절됨 — 잔액 부족 등. 차감 없음이 확실하다 */
        REJECTED,
        /** 결제 서비스에 닿지 못함 — 연결 거부. 차감 없음이 확실하다 */
        UNAVAILABLE,
        /** 결과를 모름 — 타임아웃. <b>차감됐을 수 있다</b> */
        UNKNOWN
    }

    public static PaymentResult success(String orderCode, long amount) {
        return new PaymentResult(Outcome.SUCCESS, orderCode, amount, "결제 성공");
    }

    public static PaymentResult rejected(String orderCode, String message) {
        return new PaymentResult(Outcome.REJECTED, orderCode, 0, message);
    }

    public static PaymentResult unavailable(String orderCode, String message) {
        return new PaymentResult(Outcome.UNAVAILABLE, orderCode, 0, message);
    }

    public static PaymentResult unknown(String orderCode, String message) {
        return new PaymentResult(Outcome.UNKNOWN, orderCode, 0, message);
    }

    public boolean success() {
        return outcome == Outcome.SUCCESS;
    }

    /** 주문을 취소해도 안전한가 — 결제가 일어나지 않은 것이 확실한 경우에만 true */
    public boolean safeToCancel() {
        return outcome == Outcome.REJECTED || outcome == Outcome.UNAVAILABLE;
    }
}
