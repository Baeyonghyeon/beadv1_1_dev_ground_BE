package io.devground.dbay.order.persistence;

import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.infrastructure.adapter.out.persistence.OrderJpaRepository;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 상태 전이 쿼리 검증 (H2).
 *
 * <p><b>멱등성이 실제로 사는 곳은 여기다.</b> 응용 계층의 분기가 아니라
 * {@code WHERE ... AND orderStatus = PAYMENT_PENDING} 이라는 조건부 UPDATE 다.
 * 이 조건이 빠지면 중복 이벤트마다 상태가 덮어써지고 후처리가 중복 발행된다.
 */
@DataJpaTest
@DisplayName("결제 상태 전이 쿼리")
class OrderPaymentStateQueryTest {

    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private EntityManager em;

    private String saveOrder(OrderStatus status) {
        String code = "ORDER-" + System.nanoTime();

        OrderEntity order = OrderEntity.builder()
                .orderCode(code)
                .userCode("USER-1")
                .nickName("닉")
                .address("주소")
                .addressDetail("상세")
                .totalAmount(5_000L)
                .build();
        order.setOrderStatus(status);

        orderJpaRepository.saveAndFlush(order);
        em.clear();

        return code;
    }

    private OrderStatus statusOf(String code) {
        em.clear();
        return orderJpaRepository.findByCode(code).orElseThrow().getOrderStatus();
    }

    @Test
    @DisplayName("PAYMENT_PENDING 주문은 PAID 로 전이되고 1행을 갱신한다")
    void 결제대기_주문은_PAID로_전이된다() {
        String code = saveOrder(OrderStatus.PAYMENT_PENDING);

        int updated = orderJpaRepository.paidByCodeIfAwaitingPayment(code);

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf(code)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("두 번째 호출은 0행을 갱신한다 — 중복 이벤트가 후처리를 재발행하지 못하게 하는 근거")
    void 두번째_전이는_0행이다() {
        String code = saveOrder(OrderStatus.PAYMENT_PENDING);

        assertThat(orderJpaRepository.paidByCodeIfAwaitingPayment(code)).isEqualTo(1);
        assertThat(orderJpaRepository.paidByCodeIfAwaitingPayment(code)).isZero();
    }

    @Test
    @DisplayName("PENDING(결제 완료·후처리 대기) 주문은 건드리지 않는다 — 두 상태를 섞으면 안 된다")
    void PENDING_주문은_전이대상이_아니다() {
        String code = saveOrder(OrderStatus.PENDING);

        assertThat(orderJpaRepository.paidByCodeIfAwaitingPayment(code)).isZero();
        assertThat(statusOf(code)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("이미 취소된 주문은 결제 성공 이벤트로 되살아나지 않는다")
    void 취소된_주문은_되살아나지_않는다() {
        String code = saveOrder(OrderStatus.CANCELLED);

        assertThat(orderJpaRepository.paidByCodeIfAwaitingPayment(code)).isZero();
        assertThat(statusOf(code)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("실패 전이도 PAYMENT_PENDING 일 때만 일어난다")
    void 실패전이도_결제대기일때만() {
        String pending = saveOrder(OrderStatus.PAYMENT_PENDING);
        String paid = saveOrder(OrderStatus.PAID);

        assertThat(orderJpaRepository.failPaymentByCodeIfAwaitingPayment(pending)).isEqualTo(1);
        assertThat(statusOf(pending)).isEqualTo(OrderStatus.PAYMENT_FAILED);

        // 이미 결제된 주문이 실패로 뒤집히면 돈이 빠진 채 실패로 기록된다
        assertThat(orderJpaRepository.failPaymentByCodeIfAwaitingPayment(paid)).isZero();
        assertThat(statusOf(paid)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("회수 취소도 PAYMENT_PENDING 일 때만 일어난다 — 결제된 주문을 취소하면 안 된다")
    void 회수취소도_결제대기일때만() {
        String paid = saveOrder(OrderStatus.PAID);

        assertThat(orderJpaRepository.cancelByCodeIfAwaitingPayment(paid)).isZero();
        assertThat(statusOf(paid)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("회수 대상 조회는 PAYMENT_PENDING 만 골라낸다")
    void 회수대상은_결제대기만_조회된다() {
        String awaiting = saveOrder(OrderStatus.PAYMENT_PENDING);
        saveOrder(OrderStatus.PENDING);
        saveOrder(OrderStatus.PAID);

        List<String> found = orderJpaRepository.findTimedOutAwaitingPayment(
                LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 10));

        assertThat(found).containsExactly(awaiting);
    }

    @Test
    @DisplayName("회수 대상 조회는 배치 크기를 넘지 않는다 — 결제 경로와 커넥션을 다투지 않기 위함")
    void 회수대상은_배치크기로_제한된다() {
        saveOrder(OrderStatus.PAYMENT_PENDING);
        saveOrder(OrderStatus.PAYMENT_PENDING);
        saveOrder(OrderStatus.PAYMENT_PENDING);

        List<String> found = orderJpaRepository.findTimedOutAwaitingPayment(
                LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 2));

        assertThat(found).hasSize(2);
    }

    @Test
    @DisplayName("폴링 조회는 주문 코드로 상태와 소유자를 함께 돌려준다")
    void 폴링조회는_상태와_소유자를_돌려준다() {
        String code = saveOrder(OrderStatus.PAYMENT_PENDING);

        var status = orderJpaRepository.findPaymentStatusByCode(code).orElseThrow();

        assertThat(status.orderCode()).isEqualTo(code);
        assertThat(status.userCode()).isEqualTo("USER-1");
        assertThat(status.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(status.isAwaitingPayment()).isTrue();
    }

    @Test
    @DisplayName("없는 주문 코드는 빈 결과다")
    void 없는_주문은_빈결과다() {
        assertThat(orderJpaRepository.findPaymentStatusByCode("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("시한 전 주문은 회수 대상이 아니다")
    void 시한_전_주문은_회수대상이_아니다() {
        saveOrder(OrderStatus.PAYMENT_PENDING);

        List<String> found = orderJpaRepository.findTimedOutAwaitingPayment(
                LocalDateTime.now().minusMinutes(5), PageRequest.of(0, 10));

        assertThat(found).isEmpty();
    }
}
