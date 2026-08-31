package io.devground.dbay.order.domain;

import io.devground.dbay.order.domain.model.Order;
import io.devground.dbay.order.domain.vo.OrderProduct;
import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.domain.vo.UserCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비동기 결제 도입으로 추가된 주문 상태 전이 검증.
 *
 * <p>여기서 지키려는 것은 <b>PENDING 과 PAYMENT_PENDING 이 다른 의미</b>라는 것이다.
 * 전자는 "돈이 이미 빠졌고 후처리만 남음", 후자는 "돈이 빠졌는지 아직 모름" 이다.
 * 이 둘을 섞으면 회수 스케줄러가 결제 완료된 주문을 취소하게 된다.
 */
@DisplayName("주문 결제 상태 전이")
class OrderPaymentStateTest {

    private static final UserCode USER = new UserCode("USER-1");

    private Order newOrder() {
        return Order.createOne(USER, new OrderProduct("PROD-1", "SELLER-1", "테스트상품", 5_000L));
    }

    @Test
    @DisplayName("생성 직후 기본 상태는 PENDING 이다 — 동기 결제 경로가 그대로 쓴다")
    void 생성_기본상태는_PENDING() {
        assertThat(newOrder().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("awaitPayment() 는 결제 미확정 상태로 표시한다")
    void awaitPayment_는_PAYMENT_PENDING() {
        Order order = newOrder();

        order.awaitPayment();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("failPayment() 는 취소가 아니라 결제 실패로 끝낸다 — 환불 보상 대상이 아니다")
    void failPayment_는_PAYMENT_FAILED() {
        Order order = newOrder();
        order.awaitPayment();

        order.failPayment();

        assertThat(order.getOrderStatus())
                .isEqualTo(OrderStatus.PAYMENT_FAILED)
                .isNotEqualTo(OrderStatus.CANCELLED);
    }

    @Nested
    @DisplayName("OrderStatus 판정")
    class StatusPredicate {

        @Test
        @DisplayName("PAYMENT_PENDING 만 결제 대기로 판정된다 — PENDING 은 아니다")
        void 결제대기_판정은_PAYMENT_PENDING_뿐() {
            assertThat(OrderStatus.PAYMENT_PENDING.isAwaitingPayment()).isTrue();

            // 이 단언이 이 테스트의 핵심이다. PENDING 이 결제 대기로 잡히면
            // 회수 스케줄러가 이미 결제된 주문을 취소해 버린다.
            assertThat(OrderStatus.PENDING.isAwaitingPayment()).isFalse();
            assertThat(OrderStatus.PAID.isAwaitingPayment()).isFalse();
            assertThat(OrderStatus.PAYMENT_FAILED.isAwaitingPayment()).isFalse();
        }

        @Test
        @DisplayName("결제 대기 주문은 취소 가능하고, 결제 실패 주문은 취소 대상이 아니다")
        void 취소가능_여부() {
            assertThat(OrderStatus.PAYMENT_PENDING.isCancellable()).isTrue();
            assertThat(OrderStatus.PAYMENT_FAILED.isCancellable()).isFalse();
        }
    }
}
