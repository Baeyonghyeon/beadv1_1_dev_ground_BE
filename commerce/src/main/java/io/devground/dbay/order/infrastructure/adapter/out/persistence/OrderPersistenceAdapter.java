package io.devground.dbay.order.infrastructure.adapter.out.persistence;

import io.devground.core.model.vo.ErrorCode;
import io.devground.dbay.order.application.port.out.persistence.OrderPersistencePort;
import io.devground.dbay.order.application.vo.UserInfo;
import io.devground.dbay.order.domain.model.Order;
import io.devground.dbay.order.domain.model.OrderItem;
import io.devground.dbay.order.domain.vo.*;
import io.devground.dbay.order.domain.vo.pagination.PageDto;
import io.devground.dbay.order.domain.vo.pagination.PageQuery;
import io.devground.dbay.order.infrastructure.mapper.OrderMapper;
import io.devground.dbay.order.infrastructure.mapper.PageMapper;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import io.devground.dbay.order.infrastructure.model.persistence.OrderItemEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements OrderPersistencePort {

    private final OrderJpaRepository orderJpaRepository;
    private final OrderItemJpaRepository orderItemJpaRepository;

    @Override
    public Order getOrder(OrderCode orderCode) {
        if (orderCode == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        OrderEntity orderEntity = orderJpaRepository.findByCode(orderCode.value())
                .orElseThrow(ErrorCode.ORDER_NOT_FOUND::throwServiceException);

        return OrderMapper.toOrderDomain(orderEntity);
    }

    @Override
    public LocalDateTime getUpdatedAtByOrder(OrderCode orderCode) {
        if (orderCode == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        return orderJpaRepository.findUpdatedAtByCode(orderCode.value());
    }

    @Override
    public void createSingleOrder(UserInfo userInfo, Order order, OrderProduct orderProduct) {

        if (userInfo == null) {
            throw ErrorCode.USER_NOT_FOUNT.throwServiceException();
        }

        if (order == null || orderProduct == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        OrderEntity orderEntity = OrderEntity.builder()
                .orderCode(order.getOrderCode().value())
                .userCode(order.getUserCode().value())
                .nickName(userInfo.nickname())
                .address(userInfo.address())
                .addressDetail(userInfo.addressDetail())
                .totalAmount(order.totalPrice(order.getOrderItems()))
                .build();



        orderJpaRepository.save(orderEntity);

        OrderItemEntity orderItemEntity = OrderItemEntity.builder()
                .orderEntity(orderEntity)
                .productCode(orderProduct.productCode())
                .sellerCode(orderProduct.sellerCode())
                .productName(orderProduct.productName())
                .productPrice(orderProduct.productPrice())
                .build();

        orderItemJpaRepository.save(orderItemEntity);
    }

    @Override
    public void createSelectedOrder(UserInfo userInfo, Order order, List<OrderProduct> orderProducts) {
        if (userInfo == null) {
            throw ErrorCode.USER_NOT_FOUNT.throwServiceException();
        }

        if (order == null || orderProducts == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        if (orderProducts.stream().anyMatch(Objects::isNull)) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        OrderEntity orderEntity = OrderEntity.builder()
                .orderCode(order.getOrderCode().value())
                .userCode(order.getUserCode().value())
                .nickName(userInfo.nickname())
                .address(userInfo.address())
                .addressDetail(userInfo.addressDetail())
                .totalAmount(order.totalPrice(order.getOrderItems()))
                .build();

        orderJpaRepository.save(orderEntity);

        List<OrderItemEntity> orderItems = orderProducts.stream()
                .map(op -> OrderItemEntity.builder()
                        .orderEntity(orderEntity)
                        .productCode(op.productCode())
                        .sellerCode(op.sellerCode())
                        .productName(op.productName())
                        .productPrice(op.productPrice())
                        .build()
                ).toList();

        orderItemJpaRepository.saveAll(orderItems);
    }

    @Override
    public PageDto<OrderDescription> getOrders(UserCode userCode, RoleType roleType, PageQuery pageQuery, OrderStatus orderStatus) {
        return orderListByRole(userCode, roleType, pageQuery, orderStatus);
    }

    @Override
    public List<OrderItemInfo> getOrderItems(List<String> orderCodes) {

        if (orderCodes == null || orderCodes.stream().anyMatch(Objects::isNull)) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        List<Long> OrderIds = orderJpaRepository.findIdByOrderCodes(orderCodes);

        List<OrderItemEntity> orderItems = orderItemJpaRepository.findAllByOrderIds(OrderIds);

        return orderItems.stream().map(OrderMapper::toOrderItemInfo).toList();
    }

    @Override
    public OrderDetailDescription getOrderDetail(UserCode userCode, OrderCode orderCode) {
        if (userCode == null) {
            throw ErrorCode.USER_NOT_FOUNT.throwServiceException();
        }

        if (orderCode == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        OrderEntity orderEntity = orderJpaRepository.findByCode(orderCode.value())
                .orElseThrow(ErrorCode.ORDER_NOT_FOUND::throwServiceException);

        long productTotalAmount = orderEntity.getOrderItems().stream().mapToLong(OrderItemEntity::getProductPrice).sum();

        return OrderMapper.toOrderDetailDescription(orderEntity, productTotalAmount);
    }

    @Override
    public void cancel(OrderCode orderCode) {
        if (orderCode == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        orderJpaRepository.cancelByCode(orderCode.value());
    }

    @Override
    public void confirm(OrderCode orderCode) {
        if (orderCode == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        orderJpaRepository.confirmByCode(orderCode.value());
    }

    @Override
    public void paid(OrderCode orderCode) {
        if (orderCode == null) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        orderJpaRepository.paidByCode(orderCode.value());
    }

    @Override
    public PageDto<UnsettledOrderItemResponse> getUnsettledOrderItems(PageQuery pageQuery, LocalDateTime cutoff) {
        Pageable pageable = PageMapper.toPageable(pageQuery);

        Page<UnsettledOrderItemResponse> unsettledOrderPage = orderItemJpaRepository.findOrderItemsDelivered(cutoff, pageable);

        return new PageDto<>(
                unsettledOrderPage.getNumber(),
                unsettledOrderPage.getSize(),
                unsettledOrderPage.getTotalPages(),
                unsettledOrderPage.getTotalElements(),
                unsettledOrderPage.getContent()
        );
    }

    @Override
    public List<Long> getPaidOrders(LocalDateTime oneDayAgo) {
        return orderJpaRepository.findOrdersToPaid(oneDayAgo);
    }

    @Override
    public int changeStatusPaidToDelivery(List<Long> ids) {
        if (ids == null || ids.stream().anyMatch(Objects::isNull)) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        return orderJpaRepository.changePaidToDelivery(ids);
    }

    @Override
    public List<Long> getDeliveryOrders(LocalDateTime threeDaysAgo) {
        return orderJpaRepository.findOrdersToDelivered(threeDaysAgo);
    }

    @Override
    public int changeStatusDeliveryToDelivered(List<Long> ids) {
        if (ids == null || ids.stream().anyMatch(Objects::isNull)) {
            throw ErrorCode.ORDER_NOT_FOUND.throwServiceException();
        }

        return orderJpaRepository.changeDeliveryToDelivered(ids);
    }

    private PageDto<OrderDescription> orderListByRole(UserCode userCode, RoleType roleType, PageQuery pageQuery, OrderStatus orderStatus) {
        Pageable pageable = PageMapper.toPageable(pageQuery);

        Page<OrderEntity> orderPage = roleType == RoleType.USER ?
                orderJpaRepository.findByNotDeletedOrders(userCode.value(),pageable, orderStatus)
                : orderJpaRepository.findAllByNotDeletedOrders(pageable, orderStatus);

        List<OrderDescription> orders = orderPage.getContent().stream()
                .map(OrderMapper::toOrderDescription)
                .toList();

        return new PageDto<>(
                orderPage.getNumber(),
                orderPage.getSize(),
                orderPage.getTotalPages(),
                orderPage.getTotalElements(),
                orders
        );
    }
}
