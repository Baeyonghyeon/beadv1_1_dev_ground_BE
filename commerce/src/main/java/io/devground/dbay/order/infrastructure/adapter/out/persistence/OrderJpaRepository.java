package io.devground.dbay.order.infrastructure.adapter.out.persistence;

import io.devground.dbay.order.domain.vo.OrderPaymentStatus;
import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.domain.vo.UnsettledOrderItemResponse;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByCode(String orderCode);

    @Query("""
        SELECT o.updatedAt
        FROM OrderEntity o
        WHERE o.code = :orderCode
        """)
    LocalDateTime findUpdatedAtByCode(String orderCode);

    @Query("""
        SELECT o.id
        FROM OrderEntity o
        WHERE o.code IN :orderCodes
        """)
    List<Long> findIdByOrderCodes(List<String> orderCodes);

    @Query("""
        SELECT o
        FROM OrderEntity o
        WHERE o.userCode = :userCode
        AND o.deleteStatus = io.devground.core.model.vo.DeleteStatus.N
        AND (:orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.ALL OR o.orderStatus = :orderStatus)
        """)
    Page<OrderEntity> findByNotDeletedOrders(String userCode, Pageable pageable, OrderStatus orderStatus);

    @Query("""
        SELECT o
        FROM OrderEntity o
        WHERE o.deleteStatus = io.devground.core.model.vo.DeleteStatus.N
        AND (:orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.ALL OR o.orderStatus = :orderStatus)
        """)
    Page<OrderEntity> findAllByNotDeletedOrders(Pageable pageable, OrderStatus orderStatus);

    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.CANCELLED,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        """)
    void cancelByCode(String orderCode);

    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.CONFIRMED,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        """)
    void confirmByCode(String orderCode);

    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAID,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        """)
    void paidByCode(String orderCode);

    /**
     * 결제 성공 반영 — {@code PAYMENT_PENDING} 인 주문만 {@code PAID} 로 바꾼다.
     *
     * <p>WHERE 절의 상태 조건이 멱등성을 만든다. 같은 결과 이벤트를 두 번 소비해도
     * 두 번째는 0행을 갱신하고 끝난다({@code paidByCode} 는 무조건 덮어쓰므로 쓰면 안 된다).
     *
     * @return 갱신된 행 수. 0이면 이미 다른 상태로 전이된 것이다.
     */
    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAID,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        AND o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAYMENT_PENDING
        """)
    int paidByCodeIfAwaitingPayment(String orderCode);

    /**
     * 결제 실패 반영 — {@code PAYMENT_PENDING} 인 주문만 {@code PAYMENT_FAILED} 로 바꾼다.
     *
     * @return 갱신된 행 수. 0이면 이미 다른 상태로 전이된 것이다.
     */
    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAYMENT_FAILED,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        AND o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAYMENT_PENDING
        """)
    int failPaymentByCodeIfAwaitingPayment(String orderCode);

    /**
     * 주문을 결제 미확정 상태로 표시한다 — 동기 경로에서 **결제 여부를 알 수 없을 때** 쓴다.
     *
     * <p>타임아웃처럼 결제가 됐는지 모르는 경우, 주문을 취소하면 돈이 빠진 주문을 지우게 된다.
     * 대신 이 상태로 남겨 회수 스케줄러가 payments 에 물어본 뒤 판정하게 한다.
     * 비동기 경로를 위해 만든 회수 장치를 동기 경로에서도 그대로 재사용하는 것이다.
     */
    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAYMENT_PENDING,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        """)
    void markAwaitingPaymentByCode(String orderCode);

    /**
     * 결제 상태 폴링용 경량 조회 — 인덱스로 한 행만 읽고 조인하지 않는다.
     *
     * <p>{@code findByCode} 로 엔티티를 통째로 읽지 않는 이유는 비용이다. 폴링은 결제 1건당
     * 여러 번 호출되므로, 이 쿼리가 무거우면 비동기화로 덜어낸 부하를 폴링이 도로 만든다(설계 §8).
     */
    @Query("""
        SELECT new io.devground.dbay.order.domain.vo.OrderPaymentStatus(o.code, o.userCode, o.orderStatus)
        FROM OrderEntity o
        WHERE o.code = :orderCode
        """)
    Optional<OrderPaymentStatus> findPaymentStatusByCode(String orderCode);

    /**
     * 회수 스케줄러 전용 취소 — 결제 흔적이 없는 {@code PAYMENT_PENDING} 주문을 접는다.
     *
     * @return 갱신된 행 수. 0이면 그사이 결제 결과가 도착한 것이므로 건드리지 않은 게 맞다.
     */
    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.CANCELLED,
        o.updatedAt = CURRENT_TIMESTAMP
        WHERE o.code = :orderCode
        AND o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAYMENT_PENDING
        """)
    int cancelByCodeIfAwaitingPayment(String orderCode);

    /**
     * 결제 확정을 기다리다 시한을 넘긴 주문 — 회수 스케줄러의 입력.
     * {@code createdAt} 기준이다({@code updatedAt} 은 회수 시도마다 갱신될 수 있어 기준이 못 된다).
     */
    @Query("""
        SELECT o.code
        FROM OrderEntity o
        WHERE o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAYMENT_PENDING
        AND o.createdAt <= :cutoff
        ORDER BY o.createdAt ASC
        """)
    List<String> findTimedOutAwaitingPayment(LocalDateTime cutoff, Pageable pageable);

    @Query("""
        SELECT o.id
        FROM OrderEntity o
        WHERE o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.PAID
        AND o.updatedAt <= :oneDayAgo
        """)
    List<Long> findOrdersToPaid(LocalDateTime oneDayAgo);

    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.START_DELIVERY
        WHERE o.id IN :ids
        """)
    int changePaidToDelivery(List<Long> ids);

    @Query("""
        SELECT o.id
        FROM OrderEntity o
        WHERE o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.START_DELIVERY
        AND o.updatedAt <= :threeDaysAgo
        """)
    List<Long> findOrdersToDelivered(LocalDateTime threeDaysAgo);

    @Modifying
    @Query("""
        UPDATE OrderEntity o
        SET o.orderStatus = io.devground.dbay.order.domain.vo.OrderStatus.DELIVERED
        WHERE o.id IN :ids
        """)
    int changeDeliveryToDelivered(List<Long> ids);
}
