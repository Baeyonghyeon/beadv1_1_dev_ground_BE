package io.devground.dbay.order.infrastructure.adapter.in.kafka;

import io.devground.core.event.payment.OrderPaymentCompleted;
import io.devground.core.event.payment.OrderPaymentFailed;
import io.devground.dbay.order.domain.port.in.OrderUseCase;
import io.devground.dbay.order.domain.vo.OrderCode;
import io.devground.dbay.order.domain.vo.UserCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 결제 결과 이벤트 소비자 (payments → commerce).
 *
 * <p>비동기 결제 경로에서 주문의 최종 상태를 정하는 지점이다.
 * {@code PAYMENT_PENDING} 주문을 {@code PAID} 또는 {@code PAYMENT_FAILED} 로 전이시킨다.
 *
 * <p><b>중복 소비는 정상이다.</b> Kafka 는 at-least-once 이고 컨슈머 리밸런싱·재시도로
 * 같은 이벤트가 두 번 올 수 있다. 전이가 조건부 UPDATE 라 두 번째는 아무 일도 하지 않는다
 * ({@code OrderApplication.applyPaymentSuccess} 참조).
 *
 * <p>이 토픽({@code payments-purchase-events})은 현재 다른 소비자가 없다.
 * 같은 토픽에 다른 {@code @KafkaListener} 클래스를 추가하면 같은 컨슈머 그룹 안에서
 * 파티션이 나뉘어 서로 처리 못 하는 타입을 받게 되므로, 새 이벤트는 이 클래스에 핸들러로 추가할 것.
 */
@Slf4j
@Component
@KafkaListener(topics = {
	"${payments.event.topic.purchase}"
})
@RequiredArgsConstructor
public class PaymentResultConsumer {

	private final OrderUseCase orderUseCase;

	@KafkaHandler
	public void handle(@Payload OrderPaymentCompleted event) {
		log.debug("[payment:kafka] 결제 성공 수신: orderCode={}", event.orderCode());

		orderUseCase.applyPaymentSuccess(
			new UserCode(event.userCode()),
			new OrderCode(event.orderCode()),
			event.productCodes() == null ? List.of() : event.productCodes()
		);
	}

	@KafkaHandler
	public void handle(@Payload OrderPaymentFailed event) {
		log.debug("[payment:kafka] 결제 실패 수신: orderCode={}, reason={}", event.orderCode(), event.reason());

		orderUseCase.applyPaymentFailure(
			new UserCode(event.userCode()),
			new OrderCode(event.orderCode()),
			event.reason(),
			event.message()
		);
	}
}
