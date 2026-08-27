package io.devground.dbay.order.infrastructure.adapter.out.persistence;

import io.devground.dbay.order.domain.vo.UnsettledOrderItemResponse;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import io.devground.dbay.order.infrastructure.model.persistence.OrderItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderItemJpaRepository extends JpaRepository<OrderItemEntity, Long> {

    List<OrderItemEntity> findAllByOrderEntity_Id(Long orderId);

    @Query("""
        SELECT oi
        FROM OrderItemEntity oi
        WHERE oi.orderEntity.id IN :orderIds
        """)
    List<OrderItemEntity> findAllByOrderIds(List<Long> orderIds);

    @Query("""
        SELECT new io.devground.dbay.order.domain.vo.UnsettledOrderItemResponse(
        o.code,
        o.userCode,
        oi.code,
        oi.sellerCode,
        oi.productPrice
        )
        FROM OrderItemEntity oi
        JOIN oi.orderEntity o
        WHERE o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.DELIVERED
        AND o.updatedAt <= :cutoff
        """)
    Page<UnsettledOrderItemResponse> findOrderItemsDelivered(LocalDateTime cutoff, Pageable pageable);
}
