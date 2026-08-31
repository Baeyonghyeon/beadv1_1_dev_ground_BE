package io.devground.dbay.order.application.port.out.payment;

import java.util.List;

/**
 * 비동기 결제 요청 포트 ({@code order.payment.strategy=kafka}).
 *
 * <p><b>{@link OrderPaymentPort} 와 별도로 둔 이유:</b> 동기 포트는 {@link PaymentResult} 를
 * 돌려주겠다고 약속한다. 비동기 경로에는 그 시점에 돌려줄 결과가 없다.
 * 같은 인터페이스에 {@code PaymentResult.accepted()} 같은 값을 끼워 넣으면
 * 호출부가 "성공" 으로 오해할 여지가 생긴다 — 결제 코드에서 그 오해는 돈이 걸린 사고가 된다.
 * 그래서 <b>반환값이 없는 별도 포트</b>로 분리했다. 설계 문서 §4.2 안 B.
 */
public interface OrderPaymentAsyncPort {

    /**
     * 결제를 요청만 하고 즉시 반환한다. 결과는 {@code payments-purchase-events} 로 돌아온다.
     *
     * <p>구현체는 반드시 <b>주문 트랜잭션이 커밋된 뒤</b> 발행해야 한다.
     * 커밋 전에 발행하면 컨슈머가 주문을 못 찾는다 (스파이크 문서 §7.2 에서 밟은 함정).
     */
    void requestPayment(String userCode, String orderCode, long totalAmount, List<String> productCodes);
}
