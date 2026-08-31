package io.devground.dbay.order.domain.port.in;

import io.devground.core.event.payment.PaymentFailReason;
import io.devground.dbay.order.application.vo.OrderAcceptance;
import io.devground.dbay.order.domain.vo.*;
import io.devground.dbay.order.domain.vo.pagination.PageDto;
import io.devground.dbay.order.domain.vo.pagination.PageQuery;

import java.util.List;

public interface OrderUseCase {
    /** @return 접수 결과 — 비동기 경로면 {@code awaitingPayment=true} 이고 클라이언트는 폴링해야 한다 */
    OrderAcceptance createOrderByOne(UserCode userCode, ProductCode productCode);
    OrderAcceptance createOrderBySelected(UserCode userCode, List<ProductCode> productCodes);
    PageDto<OrderDescription> getOrderLists(UserCode userCode, RoleType roleType, PageQuery pageQuery, OrderStatus orderStatus);
    OrderDetailDescription getOrderDetail(UserCode userCode, OrderCode orderCode);
    void confirmOrder(UserCode userCode, OrderCode orderCode);
    void cancelOrder(UserCode userCode, OrderCode orderCode);
    void paidOrder(UserCode userCode, OrderCode orderCode);

    // ── 비동기 결제 경로 (order.payment.strategy=kafka) ──────────────────────
    /** 결제 성공 반영 + 후처리 발행. 중복 이벤트에 대해 멱등하다. */
    void applyPaymentSuccess(UserCode userCode, OrderCode orderCode, List<String> productCodes);

    /** 결제 실패 반영. 중복 이벤트에 대해 멱등하다. */
    void applyPaymentFailure(UserCode userCode, OrderCode orderCode, PaymentFailReason reason, String message);

    /** 결제 확정을 기다리다 시한을 넘긴 주문 회수. @return 회수 건수 */
    int reconcileAwaitingPayments();

    /** 결제 상태 폴링 — 본인 주문만 조회된다 */
    OrderPaymentStatus getPaymentStatus(UserCode userCode, OrderCode orderCode);
    PageDto<UnsettledOrderItemResponse> getUnsettledOrderItems(PageQuery pageQuery);
    Progress autoUpdateOrderStatus();
}
