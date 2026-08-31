package io.devground.dbay.order.service;

import io.devground.core.event.payment.PaymentFailReason;
import io.devground.dbay.order.application.port.out.event.OrderPublishEventPort;
import io.devground.dbay.order.application.port.out.payment.OrderDepositPort;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentAsyncPort;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentLookupPort;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentPort;
import io.devground.dbay.order.application.port.out.persistence.OrderPersistencePort;
import io.devground.dbay.order.application.port.out.postprocess.OrderPostProcessPort;
import io.devground.dbay.order.application.port.out.product.OrderProductPort;
import io.devground.dbay.order.application.port.out.user.OrderUserPort;
import io.devground.dbay.order.application.service.OrderApplication;
import io.devground.dbay.order.application.port.out.payment.PaymentResult;
import io.devground.dbay.order.application.vo.OrderAcceptance;
import io.devground.dbay.order.application.vo.ProductSnapShot;
import io.devground.dbay.order.application.vo.UserInfo;
import io.devground.dbay.order.domain.model.Order;
import io.devground.dbay.order.domain.vo.OrderCode;
import io.devground.dbay.order.domain.vo.OrderPaymentStatus;
import io.devground.dbay.order.domain.vo.OrderProduct;
import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.domain.vo.ProductCode;
import io.devground.dbay.order.domain.vo.ProductStatus;
import io.devground.dbay.order.domain.vo.UserCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 비동기 결제 경로({@code order.payment.strategy=kafka})의 응용 서비스 검증.
 *
 * <p>세 가지를 지키려는 테스트다:
 * <ol>
 *   <li><b>전략 분기</b> — kafka 일 때 동기 Feign 결제를 절대 부르지 않는다</li>
 *   <li><b>멱등성</b> — 같은 결과 이벤트를 두 번 받아도 후처리가 두 번 나가지 않는다</li>
 *   <li><b>회수 판정</b> — 결제 기록 유무에 따라 복구/취소가 갈린다.
 *       여기를 틀리면 <b>돈이 빠진 주문을 취소</b>하게 된다</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderApplication - 비동기 결제")
class OrderApplicationAsyncPaymentTest {

    @Mock private OrderUserPort orderUserPort;
    @Mock private OrderProductPort orderProductPort;
    @Mock private OrderPersistencePort orderPersistencePort;
    @Mock private OrderPublishEventPort orderPublishEventPort;
    @Mock private OrderPaymentPort orderPaymentPort;                 // 동기 (feign)
    @Mock private OrderPaymentAsyncPort orderPaymentAsyncPort;       // 비동기 (kafka)
    @Mock private OrderPaymentLookupPort orderPaymentLookupPort;
    @Mock private OrderDepositPort orderDepositPort;
    @Mock private OrderPostProcessPort syncPostProcess;
    @Mock private OrderPostProcessPort kafkaPostProcess;

    private OrderApplication orderApplication;

    private static final UserCode USER = new UserCode("USER-1");
    private static final ProductCode PRODUCT = new ProductCode("PROD-1");
    private static final OrderCode ORDER = new OrderCode("ORDER-1");

    @BeforeEach
    void setUp() {
        orderApplication = new OrderApplication(
                orderUserPort, orderProductPort, orderPersistencePort, orderPublishEventPort,
                orderPaymentPort, orderPaymentAsyncPort, orderPaymentLookupPort, orderDepositPort,
                syncPostProcess, kafkaPostProcess);

        setStrategy("kafka");
        ReflectionTestUtils.setField(orderApplication, "postProcessStrategy", "kafka");
        ReflectionTestUtils.setField(orderApplication, "paymentTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(orderApplication, "reconcileBatchSize", 100);
        ReflectionTestUtils.setField(orderApplication, "preCheckBalance", false);
    }

    private void setStrategy(String strategy) {
        ReflectionTestUtils.setField(orderApplication, "paymentStrategy", strategy);
    }

    private void givenOrderableProduct() {
        when(orderUserPort.getUserInfo(USER)).thenReturn(new UserInfo("닉", "주소", "상세주소"));
        when(orderProductPort.getProduct(USER, PRODUCT)).thenReturn(
                new ProductSnapShot("PROD-1", "SELLER-1", "테스트상품", 5_000L, ProductStatus.ON_SALE));
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("주문 접수 시 전략 분기")
    class AcceptPath {

        @Test
        @DisplayName("kafka 전략이면 주문을 PAYMENT_PENDING 으로 남기고 커맨드만 예약한다")
        void kafka전략_주문은_결제대기로_저장되고_커맨드가_예약된다() {
            givenOrderableProduct();

            orderApplication.createOrderByOne(USER, PRODUCT);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderPersistencePort).createSingleOrder(any(), saved.capture(), any());

            assertThat(saved.getValue().getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            verify(orderPaymentAsyncPort).requestPayment(
                    eq(USER.value()), anyString(), eq(5_000L), eq(List.of("PROD-1")));
        }

        @Test
        @DisplayName("kafka 전략이면 동기 Feign 결제를 절대 호출하지 않는다 — 접수 경로 경량화의 전부")
        void kafka전략은_동기결제를_호출하지_않는다() {
            givenOrderableProduct();

            orderApplication.createOrderByOne(USER, PRODUCT);

            verifyNoInteractions(orderPaymentPort);
        }

        @Test
        @DisplayName("kafka 전략이면 접수 시점에 후처리를 발행하지 않는다 — 결제 결과를 받은 뒤에 한다")
        void kafka전략은_접수시점에_후처리하지_않는다() {
            givenOrderableProduct();

            orderApplication.createOrderByOne(USER, PRODUCT);

            verifyNoInteractions(kafkaPostProcess);
            verifyNoInteractions(syncPostProcess);
        }

        @Test
        @DisplayName("kafka 전략은 주문 코드와 함께 '결제 대기' 를 돌려준다 — 클라이언트가 폴링하려면 코드가 필요하다")
        void kafka전략은_주문코드와_대기상태를_반환한다() {
            givenOrderableProduct();

            OrderAcceptance acceptance = orderApplication.createOrderByOne(USER, PRODUCT);

            assertThat(acceptance.awaitingPayment()).isTrue();
            assertThat(acceptance.orderCode()).isNotBlank();
        }

        @Test
        @DisplayName("feign 전략이면 기존대로 동기 결제하고 주문은 PENDING 으로 저장된다")
        void feign전략은_기존_동기경로를_탄다() {
            setStrategy("feign");
            givenOrderableProduct();
            when(orderPaymentPort.processPayment(anyString(), anyString(), eq(5_000L), anyList()))
                    .thenReturn(PaymentResult.success("ORDER-1", 5_000L));

            orderApplication.createOrderByOne(USER, PRODUCT);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderPersistencePort).createSingleOrder(any(), saved.capture(), any());

            assertThat(saved.getValue().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            verify(orderPaymentPort).processPayment(anyString(), anyString(), eq(5_000L), anyList());
            verifyNoInteractions(orderPaymentAsyncPort);

            // 동기 경로는 '확정' 으로 돌아와야 컨트롤러가 기존과 같은 204 를 낸다
            assertThat(orderApplication.createOrderByOne(USER, PRODUCT).awaitingPayment()).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("결제 결과 반영")
    class ApplyResult {

        @Test
        @DisplayName("성공 이벤트는 PAID 로 전이시키고 후처리를 발행한다")
        void 성공이벤트는_전이후_후처리를_발행한다() {
            when(orderPersistencePort.markPaidIfAwaitingPayment(ORDER)).thenReturn(true);

            orderApplication.applyPaymentSuccess(USER, ORDER, List.of("PROD-1"));

            verify(kafkaPostProcess).completeOrder(USER.value(), ORDER.value(), List.of("PROD-1"));
        }

        @Test
        @DisplayName("중복 성공 이벤트는 후처리를 다시 발행하지 않는다 — Kafka 는 at-least-once 다")
        void 중복_성공이벤트는_후처리를_중복발행하지_않는다() {
            // 첫 번째는 전이 성공, 두 번째는 이미 PAID 라 0행 갱신
            when(orderPersistencePort.markPaidIfAwaitingPayment(ORDER))
                    .thenReturn(true)
                    .thenReturn(false);

            orderApplication.applyPaymentSuccess(USER, ORDER, List.of("PROD-1"));
            orderApplication.applyPaymentSuccess(USER, ORDER, List.of("PROD-1"));

            // 이게 깨지면 장바구니 정리·상품 상태 커맨드가 주문당 두 번씩 나간다
            verify(kafkaPostProcess, times(1)).completeOrder(anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("실패 이벤트는 PAYMENT_FAILED 로 전이시키고 후처리를 발행하지 않는다")
        void 실패이벤트는_후처리를_발행하지_않는다() {
            when(orderPersistencePort.markPaymentFailedIfAwaitingPayment(ORDER)).thenReturn(true);

            orderApplication.applyPaymentFailure(USER, ORDER, PaymentFailReason.INSUFFICIENT_BALANCE, "잔액 부족");

            verify(orderPersistencePort).markPaymentFailedIfAwaitingPayment(ORDER);
            verifyNoInteractions(kafkaPostProcess);
        }

        @Test
        @DisplayName("이미 전이된 주문의 실패 이벤트는 무시한다")
        void 중복_실패이벤트는_무시된다() {
            when(orderPersistencePort.markPaymentFailedIfAwaitingPayment(ORDER)).thenReturn(false);

            orderApplication.applyPaymentFailure(USER, ORDER, PaymentFailReason.INTERNAL_ERROR, "오류");

            verifyNoInteractions(kafkaPostProcess);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("결제 지연 주문 회수")
    class Reconcile {

        private void givenTimedOut(String... codes) {
            when(orderPersistencePort.findTimedOutAwaitingPayment(any(), anyInt()))
                    .thenReturn(List.of(codes));
        }

        @Test
        @DisplayName("결제 기록이 없으면 취소한다 — 커맨드가 유실된 경우, 차감이 없으므로 안전하다")
        void 결제기록_없으면_취소한다() {
            givenTimedOut("ORDER-1");
            when(orderPaymentLookupPort.findByOrderCode("ORDER-1")).thenReturn(Optional.empty());
            when(orderPersistencePort.cancelIfAwaitingPayment(any())).thenReturn(true);

            int handled = orderApplication.reconcileAwaitingPayments();

            assertThat(handled).isEqualTo(1);
            verify(orderPersistencePort).cancelIfAwaitingPayment(any());
            verify(orderPersistencePort, never()).markPaidIfAwaitingPayment(any());
        }

        @Test
        @DisplayName("결제가 완료돼 있으면 취소하지 않고 PAID 로 복구하고 후처리를 잇는다 — 결과 이벤트 유실")
        void 결제완료면_취소하지않고_복구한다() {
            givenTimedOut("ORDER-1");
            when(orderPaymentLookupPort.findByOrderCode("ORDER-1")).thenReturn(Optional.of(
                    new OrderPaymentLookupPort.PaymentSnapshot(
                            "ORDER-1", "PAY-1", "PAYMENT_COMPLETED", 5_000L)));
            when(orderPersistencePort.markPaidIfAwaitingPayment(any())).thenReturn(true);
            when(orderPersistencePort.getProductCodes(any())).thenReturn(List.of("PROD-1"));
            when(orderPersistencePort.getOrder(any())).thenReturn(
                    Order.createOne(USER, new OrderProduct("PROD-1", "SELLER-1", "테스트상품", 5_000L)));

            int handled = orderApplication.reconcileAwaitingPayments();

            assertThat(handled).isEqualTo(1);
            // 이 단언이 깨지면 이미 돈이 빠진 주문을 취소하게 된다 — 가장 비싼 버그
            verify(orderPersistencePort, never()).cancelIfAwaitingPayment(any());
            verify(kafkaPostProcess).completeOrder(anyString(), eq("ORDER-1"), eq(List.of("PROD-1")));
        }

        @Test
        @DisplayName("결제 기록이 있어도 완료 상태가 아니면 취소한다")
        void 결제기록이_미완료면_취소한다() {
            givenTimedOut("ORDER-1");
            when(orderPaymentLookupPort.findByOrderCode("ORDER-1")).thenReturn(Optional.of(
                    new OrderPaymentLookupPort.PaymentSnapshot(
                            "ORDER-1", "PAY-1", "PAYMENT_PENDING", 5_000L)));
            when(orderPersistencePort.cancelIfAwaitingPayment(any())).thenReturn(true);

            orderApplication.reconcileAwaitingPayments();

            verify(orderPersistencePort).cancelIfAwaitingPayment(any());
        }

        @Test
        @DisplayName("조회가 실패한 건은 건너뛰고 나머지는 계속 처리한다 — 배치 전체가 멈추면 안 된다")
        void 조회실패는_건너뛰고_계속_처리한다() {
            givenTimedOut("ORDER-1", "ORDER-2");
            when(orderPaymentLookupPort.findByOrderCode("ORDER-1"))
                    .thenThrow(new IllegalStateException("payments 응답 없음"));
            when(orderPaymentLookupPort.findByOrderCode("ORDER-2")).thenReturn(Optional.empty());
            when(orderPersistencePort.cancelIfAwaitingPayment(any())).thenReturn(true);

            int handled = orderApplication.reconcileAwaitingPayments();

            assertThat(handled).isEqualTo(1);   // ORDER-2 만 처리됨
            // 조회에 실패한 ORDER-1 은 손대지 않는다. "조회 실패" 를 "결제 없음" 으로
            // 오판해 취소하면 돈이 빠진 주문을 취소하게 된다.
            verify(orderPersistencePort, times(1)).cancelIfAwaitingPayment(any());
        }

        @Test
        @DisplayName("회수 대상이 없으면 payments 를 호출하지 않는다")
        void 대상이_없으면_원격호출도_없다() {
            when(orderPersistencePort.findTimedOutAwaitingPayment(any(), anyInt())).thenReturn(List.of());

            assertThat(orderApplication.reconcileAwaitingPayments()).isZero();
            verifyNoInteractions(orderPaymentLookupPort);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("잔액 사전 확인")
    class PreCheckBalance {

        private void enablePreCheck() {
            ReflectionTestUtils.setField(orderApplication, "preCheckBalance", true);
        }

        @Test
        @DisplayName("기본적으로 꺼져 있어 예치금을 조회하지 않는다 — 접수 경로에 원격 호출을 늘리지 않는다")
        void 기본값은_꺼짐이다() {
            givenOrderableProduct();

            orderApplication.createOrderByOne(USER, PRODUCT);

            verifyNoInteractions(orderDepositPort);
        }

        @Test
        @DisplayName("켜면 잔액이 모자랄 때 주문을 만들지 않고 즉시 거절한다")
        void 잔액부족이면_주문을_만들지_않는다() {
            enablePreCheck();
            givenOrderableProduct();
            when(orderDepositPort.getBalance(USER.value())).thenReturn(1_000L);   // 필요 5,000

            assertThatThrownBy(() -> orderApplication.createOrderByOne(USER, PRODUCT))
                    .isInstanceOf(RuntimeException.class);

            verifyNoInteractions(orderPaymentAsyncPort);
            verify(orderPersistencePort, never()).createSingleOrder(any(), any(), any());
        }

        @Test
        @DisplayName("잔액이 충분하면 평소대로 진행한다")
        void 잔액이_충분하면_통과한다() {
            enablePreCheck();
            givenOrderableProduct();
            when(orderDepositPort.getBalance(USER.value())).thenReturn(100_000L);

            orderApplication.createOrderByOne(USER, PRODUCT);

            verify(orderPaymentAsyncPort).requestPayment(anyString(), anyString(), eq(5_000L), anyList());
        }

        @Test
        @DisplayName("조회 자체가 실패하면 주문을 막지 않는다 — 부가 확인이 주문을 막는 결합을 만들면 안 된다")
        void 조회실패는_주문을_막지_않는다() {
            enablePreCheck();
            givenOrderableProduct();
            when(orderDepositPort.getBalance(USER.value()))
                    .thenThrow(new IllegalStateException("payments 응답 없음"));

            orderApplication.createOrderByOne(USER, PRODUCT);

            verify(orderPaymentAsyncPort).requestPayment(anyString(), anyString(), eq(5_000L), anyList());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("결제 상태 폴링")
    class PollStatus {

        @Test
        @DisplayName("대기 중이면 awaitingPayment=true 로 알려준다 — 클라이언트가 폴링을 계속할 신호")
        void 대기중이면_계속_폴링하라고_알린다() {
            when(orderPersistencePort.getPaymentStatus(ORDER)).thenReturn(
                    new OrderPaymentStatus("ORDER-1", USER.value(), OrderStatus.PAYMENT_PENDING));

            OrderPaymentStatus status = orderApplication.getPaymentStatus(USER, ORDER);

            assertThat(status.isAwaitingPayment()).isTrue();
        }

        @Test
        @DisplayName("확정되면 awaitingPayment=false 가 되어 폴링이 끝난다")
        void 확정되면_폴링이_끝난다() {
            when(orderPersistencePort.getPaymentStatus(ORDER)).thenReturn(
                    new OrderPaymentStatus("ORDER-1", USER.value(), OrderStatus.PAID));

            assertThat(orderApplication.getPaymentStatus(USER, ORDER).isAwaitingPayment()).isFalse();
        }

        @Test
        @DisplayName("결제 실패도 확정이다 — 실패 화면을 띄우고 폴링을 멈춰야 한다")
        void 결제실패도_확정이다() {
            when(orderPersistencePort.getPaymentStatus(ORDER)).thenReturn(
                    new OrderPaymentStatus("ORDER-1", USER.value(), OrderStatus.PAYMENT_FAILED));

            assertThat(orderApplication.getPaymentStatus(USER, ORDER).isAwaitingPayment()).isFalse();
        }

        @Test
        @DisplayName("남의 주문은 조회되지 않는다")
        void 남의_주문은_조회되지_않는다() {
            when(orderPersistencePort.getPaymentStatus(ORDER)).thenReturn(
                    new OrderPaymentStatus("ORDER-1", "OTHER-USER", OrderStatus.PAID));

            assertThatThrownBy(() -> orderApplication.getPaymentStatus(USER, ORDER))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("동기 결제 실패 분류")
    class SyncFailureHandling {

        @BeforeEach
        void useSyncStrategy() {
            setStrategy("feign");
            givenOrderableProduct();
        }

        private OrderAcceptance orderWith(PaymentResult result) {
            when(orderPaymentPort.processPayment(anyString(), anyString(), anyLong(), anyList()))
                    .thenReturn(result);
            return orderApplication.createOrderByOne(USER, PRODUCT);
        }

        @Test
        @DisplayName("성공이면 SETTLED — 후처리가 발행된다")
        void 성공이면_확정이다() {
            OrderAcceptance acceptance = orderWith(PaymentResult.success("O", 5_000L));

            assertThat(acceptance.state()).isEqualTo(OrderAcceptance.State.SETTLED);
            verify(kafkaPostProcess).completeOrder(anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("거절(잔액 부족)이면 REJECTED + 주문 취소 — 차감이 없으므로 안전하다")
        void 거절이면_주문을_취소한다() {
            OrderAcceptance acceptance = orderWith(PaymentResult.rejected("O", "잔액이 부족합니다."));

            assertThat(acceptance.state()).isEqualTo(OrderAcceptance.State.REJECTED);
            verify(orderPersistencePort).cancel(any());
            verifyNoInteractions(kafkaPostProcess);
        }

        @Test
        @DisplayName("연결 실패면 PAYMENT_UNAVAILABLE + 주문 취소 — 요청이 가지 않았으므로 안전하다")
        void 연결실패면_주문을_취소한다() {
            OrderAcceptance acceptance = orderWith(PaymentResult.unavailable("O", "연결 실패"));

            assertThat(acceptance.state()).isEqualTo(OrderAcceptance.State.PAYMENT_UNAVAILABLE);
            verify(orderPersistencePort).cancel(any());
        }

        @Test
        @DisplayName("결과 불명이면 주문을 취소하지 않고 회수 대상으로 남긴다 ← 가장 중요")
        void 결과불명이면_취소하지_않는다() {
            OrderAcceptance acceptance = orderWith(PaymentResult.unknown("O", "응답 시간 초과"));

            assertThat(acceptance.state()).isEqualTo(OrderAcceptance.State.PAYMENT_UNCERTAIN);

            // 이 단언이 깨지면 돈이 빠진 주문을 취소하게 된다 — 복구 불가능
            verify(orderPersistencePort, never()).cancel(any());
            verify(orderPersistencePort).markAwaitingPayment(any());
            verifyNoInteractions(kafkaPostProcess);
        }

        @Test
        @DisplayName("결과 불명이어도 주문 코드는 돌려준다 — 클라이언트가 나중에 상태를 조회할 수 있어야 한다")
        void 결과불명이어도_주문코드를_돌려준다() {
            assertThat(orderWith(PaymentResult.unknown("O", "타임아웃")).orderCode()).isNotBlank();
        }
    }
}
