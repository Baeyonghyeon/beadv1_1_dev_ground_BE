package io.devground.dbay.order.application.port.out.postprocess;

import java.util.List;

/**
 * 결제 성공 후 후속 처리를 위한 아웃바운드 포트 (전략 인터페이스).
 *
 * <p>후속 처리는 세 가지다: 주문 완료 처리 / 장바구니 항목 삭제 / 상품 판매완료 표시.
 * 이걸 <b>사용자를 기다리게 하면서 할지, 큐에 넘기고 바로 응답할지</b> 가
 * 이번 부하 테스트의 핵심 비교 축이다 (설계 문서 §3, P1 명제).
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code SyncOrderPostProcessAdapter}  — Arm A: 동기 DB 쓰기. 사용자가 전부 기다린다</li>
 *   <li>{@code KafkaOrderPostProcessAdapter} — Arm B: Kafka 발행 후 즉시 반환</li>
 * </ul>
 *
 * <p>{@code order.postprocess.strategy} 프로퍼티로 전환한다 ({@code sync} | {@code kafka}).
 *
 * @see io.devground.dbay.order.application.port.out.payment.OrderPaymentPort
 */
public interface OrderPostProcessPort {

    /**
     * 결제 성공 후 후속 처리를 수행한다.
     *
     * @param userCode     사용자 코드
     * @param orderCode    주문 코드
     * @param productCodes 주문에 포함된 상품 코드 목록
     */
    void completeOrder(String userCode, String orderCode, List<String> productCodes);
}
