package io.devground.dbay.order.application.port.out.payment;

/**
 * 예치금 잔액 조회 포트 — 비동기 결제의 <b>사전 확인</b> 전용 (설계 §7).
 *
 * <p><b>이건 확정이 아니라 힌트다.</b> 최종 판정은 {@code PaymentServiceImpl.process()} 의
 * 비관적 락 구간이 한다. 여기서 통과했더라도 동시 결제 경합으로 실제 차감이 실패할 수 있고,
 * 그게 정상이다. 이 조회 결과를 근거로 차감을 건너뛰거나 잔액을 예약하면 안 된다.
 *
 * <p><b>기본적으로 꺼져 있다</b>({@code order.payment.pre-check-balance: false}).
 * 켜면 접수 경로에 원격 호출이 하나 늘어난다 — 비동기화로 덜어낸 것을 일부 되돌리는 셈이라,
 * UX 요구가 분명할 때만 켠다.
 */
public interface OrderDepositPort {

    /** 사용자의 현재 예치금 잔액. 조회에 실패하면 예외를 던진다. */
    long getBalance(String userCode);
}
