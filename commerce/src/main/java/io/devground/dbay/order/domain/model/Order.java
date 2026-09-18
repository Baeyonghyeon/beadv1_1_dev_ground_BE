package io.devground.dbay.order.domain.model;

import io.devground.dbay.cart.domain.exception.DomainError;
import io.devground.dbay.order.domain.vo.OrderCode;
import io.devground.dbay.order.domain.vo.OrderProduct;
import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.domain.vo.UserCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class Order {
    private final OrderCode orderCode;

    private final UserCode userCode;

    private OrderStatus orderStatus = OrderStatus.PENDING;

    private final List<OrderItem> orderItems;

    private void validate(OrderCode orderCode, UserCode userCode) {
        if (orderCode == null) {
            throw DomainError.CODE_INVALID.throwDomainException();
        }

        if (userCode == null) {
            throw DomainError.CODE_INVALID.throwDomainException();
        }
    }

    private Order(OrderCode orderCode, UserCode userCode, List<OrderItem> orderItems) {
        validate(orderCode, userCode);

        if (orderItems == null) {
            throw DomainError.ORDER_NOT_FOUND.throwDomainException();
        }

        this.orderCode = orderCode;
        this.userCode = userCode;
        this.orderItems = orderItems;
    }

    // 단건 생성
    public static Order createOne(UserCode userCode, OrderProduct orderProduct) {
        if (userCode == null) {
            throw DomainError.CODE_INVALID.throwDomainException();
        }

        if (orderProduct == null) {
            throw DomainError.ORDER_ITEM_NOT_SELECTED.throwDomainException();
        }

        OrderCode orderCode = OrderCode.create();

        return new Order(orderCode, userCode, List.of(OrderItem.create(orderCode, orderProduct)));
    }

    // 선택 생성
    public static Order createSelected(UserCode userCode, List<OrderProduct> orderProducts) {
        if (userCode == null) {
            throw DomainError.CODE_INVALID.throwDomainException();
        }

        if (orderProducts == null) {
            throw DomainError.ORDER_ITEM_NOT_SELECTED.throwDomainException();
        }

        if (orderProducts.stream().anyMatch(Objects::isNull)) {
            throw DomainError.ORDER_ITEM_NOT_SELECTED.throwDomainException();
        }

        OrderCode orderCode = OrderCode.create();

        List<OrderItem> orderItems = orderProducts.stream()
                .map(op -> OrderItem.create(orderCode, op))
                .toList();

        return new Order(orderCode, userCode, orderItems);
    }

    public static Order restore(OrderCode orderCode, UserCode userCode, List<OrderItem> orderItems) {
        return new Order(orderCode, userCode, orderItems);
    }

    // 주문이 가지는 행동
    // 주문 상태 변경
    public void cancel() {
        if (this.orderStatus == OrderStatus.CANCELLED) {
            throw DomainError.ORDER_ALREADY_CANCELLED.throwDomainException();
        }

        if (this.orderStatus == OrderStatus.DELIVERED) {
            throw DomainError.ORDER_ALREADY_DELIVERED.throwDomainException();
        }

        if (this.orderStatus == OrderStatus.CONFIRMED) {
            throw DomainError.ORDER_ALREADY_CONFIRMED.throwDomainException();
        }

        this.orderStatus = OrderStatus.CANCELLED;
    }

    public void confirm(LocalDateTime updatedAt) {
        if (this.orderStatus == OrderStatus.CONFIRMED) {
            throw DomainError.ORDER_ALREADY_CONFIRMED.throwDomainException();
        }

        if (this.orderStatus != OrderStatus.DELIVERED) {
            throw DomainError.ORDER_CONFIRM_NOT_ALLOWED_BEFORE_DELIVERED.throwDomainException();
        }

        LocalDateTime twoWeeksAfterDelivery = updatedAt.plusWeeks(2);

        if (updatedAt.isAfter(twoWeeksAfterDelivery)) {
            throw DomainError.ORDER_CONFIRM_NOT_ALLOWED_BEFORE_TWO_WEEKS.throwDomainException();
        }

        this.orderStatus = OrderStatus.CONFIRMED;
    }

    public void paid() {
        this.orderStatus = OrderStatus.PAID;
    }

    /**
     * 결제 결과를 기다리는 상태로 표시한다 (비동기 결제 경로의 최초 상태).
     *
     * <p>동기 경로는 이 메서드를 호출하지 않는다 — 응답 시점에 이미 결제가 끝나 있으므로
     * 생성 기본값 {@code PENDING}(결제 완료, 후처리 대기) 그대로 저장된다.
     */
    public void awaitPayment() {
        this.orderStatus = OrderStatus.PAYMENT_PENDING;
    }

    /**
     * 결제 실패로 종료한다.
     *
     * <p>{@code cancel()} 과 구분하는 이유: 취소는 "결제됐던 주문을 되돌린 것" 이고,
     * 이쪽은 "애초에 결제가 성립하지 않은 것" 이다. 환불 보상 대상이 아니다.
     */
    public void failPayment() {
        this.orderStatus = OrderStatus.PAYMENT_FAILED;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public long totalPrice(List<OrderItem> orderItems) {
        return orderItems.stream().mapToLong(OrderItem::getProductPrice).sum();
    }

    public OrderCode getOrderCode() {
        return orderCode;
    }

    public UserCode getUserCode() {
        return userCode;
    }

    public List<OrderItem> getOrderItems() {
        return List.copyOf(orderItems);
    }
}
