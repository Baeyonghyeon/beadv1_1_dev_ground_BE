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
    PageDto<UnsettledOrderItemResponse> getUnsettledOrderItems(PageQuery pageQuery, LocalDateTime cutoff);
    List<Long> getPaidOrders(LocalDateTime oneDayAgo);
    int changeStatusPaidToDelivery(List<Long> ids);
    List<Long> getDeliveryOrders(LocalDateTime threeDaysAgo);
    int changeStatusDeliveryToDelivered(List<Long> ids);
}
