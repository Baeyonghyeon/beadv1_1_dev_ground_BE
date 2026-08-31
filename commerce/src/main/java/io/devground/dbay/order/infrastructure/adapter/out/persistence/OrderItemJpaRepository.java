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

    /**
     * 주문의 상품 코드 목록.
     *
     * <p>회수 스케줄러가 "결제는 됐는데 결과 이벤트가 유실된" 주문을 복구할 때,
     * 후처리 커맨드(장바구니 정리·상품 상태)에 실을 상품 코드를 여기서 다시 읽는다.
     * payments 는 상품 코드를 보관하지 않으므로 결제 조회로는 알 수 없다.
     */
    @Query("""
        SELECT oi.productCode
        FROM OrderItemEntity oi
        WHERE oi.orderEntity.code = :orderCode
        """)
    List<String> findProductCodesByOrderCode(String orderCode);

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
