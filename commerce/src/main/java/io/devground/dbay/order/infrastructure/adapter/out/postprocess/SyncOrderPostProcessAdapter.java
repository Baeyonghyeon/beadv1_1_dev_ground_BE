package io.devground.dbay.order.infrastructure.adapter.out.postprocess;

import io.devground.dbay.cart.domain.port.in.CartUseCase;
import io.devground.dbay.order.application.port.out.persistence.OrderPersistencePort;
import io.devground.dbay.order.application.port.out.postprocess.OrderPostProcessPort;
import io.devground.dbay.order.domain.vo.OrderCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Arm A 구현체: 후속 처리를 <b>동기 DB 쓰기</b>로 수행한다. 사용자가 전부 기다린다.
 *
 * <p>Kafka 소비자({@code OrderCommandConsumer}, {@code CartCommandConsumer})가 하던 일을
 * 그대로 호출부 스레드에서 실행한다. 같은 작업을 <b>언제 하는가</b> 만 다르게 해서
 * Arm B 와의 차이가 "동기냐 비동기냐" 에서만 발생하도록 통제한 것이다.
 *
 * <p><b>상품 판매완료 처리는 하지 않는다.</b> product 는 별도 서비스이고 동기 호출용 포트가 없다.
 * bench 환경에는 product 서비스를 띄우지 않으므로 Arm B 의 {@code ProductSoldCommand} 도
 * 소비자가 없어 실제 작업이 일어나지 않는다. 즉 두 Arm 모두 상품 단계는 비어 있고,
 * Arm B 만 발행 비용(버퍼링되는 send 1회)을 추가로 낸다. <b>결과 문서에 이탈 사항으로 적을 것.</b>
 *
 * <p><b>전제:</b> 사용자에게 장바구니가 있어야 한다. 없으면 {@code CART_NOT_FOUND} 로
 * 주문 요청 자체가 실패한다 (Arm B 는 소비자에서 실패해 DLT 로 가므로 응답에는 영향이 없다).
 * 이 차이 자체가 동기/비동기의 실제 semantics 이므로 예외를 삼키지 않는다.
 * 대신 <b>부하 테스트 시드에 장바구니를 반드시 포함</b>해야 한다 (설계 §8 산출물 ⑦).
 */
@Slf4j
@Component
@Qualifier("syncOrderPostProcessAdapter")
@RequiredArgsConstructor
public class SyncOrderPostProcessAdapter implements OrderPostProcessPort {

    private final OrderPersistencePort orderPersistencePort;
    private final CartUseCase cartUseCase;

    @Override
    public void completeOrder(String userCode, String orderCode, List<String> productCodes) {
        // 1. 주문 완료 (OrderCommandConsumer.handle(CompleteOrderCommand) 와 동일한 작업)
        orderPersistencePort.paid(new OrderCode(orderCode));

        // 2. 장바구니 항목 삭제 (CartCommandConsumer.handleOrderComplete 와 동일한 작업)
        cartUseCase.removeCartItems(
                new io.devground.dbay.cart.domain.vo.UserCode(userCode),
                productCodes.stream()
                        .map(io.devground.dbay.cart.domain.vo.ProductCode::new)
                        .toList()
        );

        // 3. 상품 판매완료 — 동기 대응 경로 없음 (클래스 주석 참조)

        log.debug("[postprocess:sync] 후속 처리 동기 완료: orderCode={}", orderCode);
    }
}
