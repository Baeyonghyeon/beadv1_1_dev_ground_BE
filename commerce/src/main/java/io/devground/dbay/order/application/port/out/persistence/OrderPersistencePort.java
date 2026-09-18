package io.devground.dbay.order.application.port.out.persistence;

import io.devground.dbay.order.application.vo.UserInfo;
import io.devground.dbay.order.domain.model.Order;
import io.devground.dbay.order.domain.model.OrderItem;
import io.devground.dbay.order.domain.vo.*;
import io.devground.dbay.order.domain.vo.pagination.PageDto;
import io.devground.dbay.order.domain.vo.pagination.PageQuery;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderPersistencePort {
    Order getOrder(OrderCode orderCode);
    LocalDateTime getUpdatedAtByOrder(OrderCode orderCode);
    void createSingleOrder(UserInfo userInfo, Order order, OrderProduct orderProduct);
    void createSelectedOrder(UserInfo userInfo, Order order, List<OrderProduct> orderProducts);
    PageDto<OrderDescription> getOrders(UserCode userCode, RoleType roleType, PageQuery pageQuery, OrderStatus orderStatus);
    List<OrderItemInfo> getOrderItems(List<String> orderCodes);
    OrderDetailDescription getOrderDetail(UserCode userCode, OrderCode orderCode);
    void cancel(OrderCode orderCode);
    void confirm(OrderCode orderCode);
    void paid(OrderCode orderCode);

    // ── 비동기 결제 경로 (order.payment.strategy=kafka) ──────────────────────────
    // 아래 셋은 모두 "PAYMENT_PENDING 인 주문만" 전이시키고 갱신된 행 수를 돌려준다.
    // 0을 반환하면 이미 다른 상태로 전이된 것이므로 호출부는 조용히 넘어가야 한다(멱등).

    /** 결제 성공 반영. @return 전이가 실제로 일어났으면 true */
    boolean markPaidIfAwaitingPayment(OrderCode orderCode);

    /** 결제 실패 반영. @return 전이가 실제로 일어났으면 true */
    boolean markPaymentFailedIfAwaitingPayment(OrderCode orderCode);

    /** 회수 스케줄러의 취소. @return 전이가 실제로 일어났으면 true */
    boolean cancelIfAwaitingPayment(OrderCode orderCode);

    /** 결제 확정을 기다리다 시한을 넘긴 주문 코드 목록 (오래된 것부터, 최대 {@code limit} 건) */
    List<String> findTimedOutAwaitingPayment(LocalDateTime cutoff, int limit);

    /** 주문의 상품 코드 목록 — 유실 복구 시 후처리 커맨드에 실을 값 */
    List<String> getProductCodes(OrderCode orderCode);

    /** 결제 여부 불명 시 회수 대상으로 표시 (동기 경로의 타임아웃 처리) */
    void markAwaitingPayment(OrderCode orderCode);

    /** 결제 상태 폴링용 경량 조회 (인덱스 1행, 조인 없음) */
    OrderPaymentStatus getPaymentStatus(OrderCode orderCode);
    PageDto<UnsettledOrderItemResponse> getUnsettledOrderItems(PageQuery pageQuery, LocalDateTime cutoff);
    List<Long> getPaidOrders(LocalDateTime oneDayAgo);
    int changeStatusPaidToDelivery(List<Long> ids);
    List<Long> getDeliveryOrders(LocalDateTime threeDaysAgo);
    int changeStatusDeliveryToDelivered(List<Long> ids);
}
