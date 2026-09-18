package io.devground.payments.payment.saga;

import io.devground.core.commands.payment.PaymentRequestedCommand;
import io.devground.core.event.payment.OrderPaymentCompleted;
import io.devground.core.event.payment.OrderPaymentFailed;
import io.devground.core.event.payment.PaymentFailReason;
import io.devground.payments.deposit.application.exception.ServiceException;
import io.devground.payments.deposit.application.exception.vo.ServiceErrorCode;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 커맨드 컨슈머 검증 (비동기 결제 경로의 payments 쪽).
 *
 * <p>핵심은 <b>실패를 어떻게 나누느냐</b>다. commerce 는 이 이벤트만 보고
 * "보상이 필요한가" 를 판단하므로, 잘못 분류하면 차감된 돈이 방치되거나
 * 멀쩡한 주문이 취소된다.
 *
 * <table border="1">
 *   <caption>실패 분류</caption>
 *   <tr><th>상황</th><th>발행</th><th>이유</th></tr>
 *   <tr><td>잔액 부족</td><td>INSUFFICIENT_BALANCE</td><td>차감 없음 → 보상 불필요</td></tr>
 *   <tr><td>예치금 계정 없음</td><td>DEPOSIT_NOT_FOUND</td><td>차감 없음 → 보상 불필요</td></tr>
 *   <tr><td>중복 커맨드</td><td>Completed 재발행</td><td>이미 성공 — 실패로 처리하면 정상 주문이 취소된다</td></tr>
 *   <tr><td>그 외 예외</td><td>발행 안 함 (전파)</td><td>재시도 → DLT. 삼키면 잘못된 확정이 생긴다</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentKafkaHandler - 결제 커맨드 처리")
class PaymentKafkaHandlerAsyncPaymentTest {

	@Mock private PaymentService paymentService;
	@Mock private KafkaTemplate<String, Object> kafkaTemplate;

	private PaymentKafkaHandler handler;

	private static final String EVENT_TOPIC = "payments-purchase-events";

	private final PaymentRequestedCommand command = new PaymentRequestedCommand(
			"ORDER-1", "USER-1", 5_000L, List.of("PROD-1"), Instant.now());

	@BeforeEach
	void setUp() {
		handler = new PaymentKafkaHandler(paymentService, kafkaTemplate);
		ReflectionTestUtils.setField(handler, "paymentPurchaseEventTopic", EVENT_TOPIC);
		ReflectionTestUtils.setField(handler, "depositsCommandTopic", "deposits-commands");
	}

	private Payment paymentWithCode(String code) {
		Payment payment = Payment.builder()
				.userCode("USER-1").amount(5_000L).orderCode("ORDER-1").build();
		payment.register(code);
		return payment;
	}

	private Object capturePublished() {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(kafkaTemplate).send(eq(EVENT_TOPIC), eq("USER-1"), captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("결제에 성공하면 완료 이벤트를 발행한다 — 파티션 키는 userCode 다")
	void 성공하면_완료이벤트를_발행한다() {
		when(paymentService.process(eq("USER-1"), any(PaymentConfirmRequest.class)))
				.thenReturn(paymentWithCode("PAY-1"));

		handler.handleEvent(command);

		Object published = capturePublished();
		assertThat(published).isInstanceOf(OrderPaymentCompleted.class);

		OrderPaymentCompleted event = (OrderPaymentCompleted) published;
		assertThat(event.orderCode()).isEqualTo("ORDER-1");
		assertThat(event.paymentCode()).isEqualTo("PAY-1");
		assertThat(event.productCodes()).containsExactly("PROD-1");
	}

	@Test
	@DisplayName("결제는 동기 경로와 같은 process() 를 그대로 재사용한다 — 락·이력 전략을 바꾸지 않는다")
	void 동기경로와_같은_process를_호출한다() {
		when(paymentService.process(anyString(), any())).thenReturn(paymentWithCode("PAY-1"));

		handler.handleEvent(command);

		ArgumentCaptor<PaymentConfirmRequest> captor = ArgumentCaptor.forClass(PaymentConfirmRequest.class);
		verify(paymentService).process(eq("USER-1"), captor.capture());

		PaymentConfirmRequest request = captor.getValue();
		assertThat(request.orderCode()).isEqualTo("ORDER-1");
		assertThat(request.amount()).isEqualTo(5_000L);
		assertThat(request.useDeposit()).isTrue();
	}

	@Test
	@DisplayName("잔액이 부족하면 INSUFFICIENT_BALANCE 로 발행한다 — 차감이 없으므로 보상 대상이 아니다")
	void 잔액부족은_보상불필요_사유로_발행한다() {
		when(paymentService.process(anyString(), any()))
				.thenThrow(new ServiceException(ServiceErrorCode.INSUFFICIENT_BALANCE));

		handler.handleEvent(command);

		Object published = capturePublished();
		assertThat(published).isInstanceOf(OrderPaymentFailed.class);
		assertThat(((OrderPaymentFailed) published).reason())
				.isEqualTo(PaymentFailReason.INSUFFICIENT_BALANCE);
	}

	@Test
	@DisplayName("예치금 계정이 없으면 DEPOSIT_NOT_FOUND 로 발행한다")
	void 예치금없음은_전용_사유로_발행한다() {
		when(paymentService.process(anyString(), any()))
				.thenThrow(new ServiceException(ServiceErrorCode.DEPOSIT_NOT_FOUND));

		handler.handleEvent(command);

		assertThat(((OrderPaymentFailed) capturePublished()).reason())
				.isEqualTo(PaymentFailReason.DEPOSIT_NOT_FOUND);
	}

	@Test
	@DisplayName("중복 커맨드는 실패가 아니라 기존 결과를 재발행한다 — 실패로 처리하면 정상 주문이 취소된다")
	void 중복커맨드는_기존_성공결과를_재발행한다() {
		when(paymentService.process(anyString(), any()))
				.thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'orderCode'"));
		when(paymentService.findByOrderCode("ORDER-1"))
				.thenReturn(Optional.of(paymentWithCode("PAY-EXISTING")));

		handler.handleEvent(command);

		Object published = capturePublished();
		assertThat(published).isInstanceOf(OrderPaymentCompleted.class);
		assertThat(((OrderPaymentCompleted) published).paymentCode()).isEqualTo("PAY-EXISTING");
	}

	@Test
	@DisplayName("unique 위반인데 기존 결제를 못 찾으면 INTERNAL_ERROR 로 발행한다 — 회수 대상으로 넘긴다")
	void unique위반인데_기존결제가_없으면_내부오류로_발행한다() {
		when(paymentService.process(anyString(), any()))
				.thenThrow(new DataIntegrityViolationException("Duplicate entry"));
		when(paymentService.findByOrderCode("ORDER-1")).thenReturn(Optional.empty());

		handler.handleEvent(command);

		assertThat(((OrderPaymentFailed) capturePublished()).reason())
				.isEqualTo(PaymentFailReason.INTERNAL_ERROR);
	}

	@Test
	@DisplayName("분류되지 않은 예외는 삼키지 않고 전파한다 — 재시도 후 DLT 로 가야 한다")
	void 알수없는_예외는_전파된다() {
		when(paymentService.process(anyString(), any()))
				.thenThrow(new RuntimeException("DB 커넥션 획득 실패"));

		assertThatThrownBy(() -> handler.handleEvent(command))
				.isInstanceOf(RuntimeException.class);

		// 여기서 실패 이벤트를 내보내면 "차감됐는데 실패로 확정" 되는 사고가 난다.
		verify(kafkaTemplate, never()).send(eq(EVENT_TOPIC), anyString(), any());
	}
}
