package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.dbay.order.application.port.out.payment.OrderPaymentAsyncPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 비동기 결제 요청 어댑터 — 결제 커맨드를 Kafka 로 발행한다.
 *
 * <p><b>왜 여기서 바로 보내지 않는가:</b> 이 메서드는 주문 INSERT 와 같은 트랜잭션 안에서 호출된다.
 * 그 자리에서 Kafka 로 보내면 <b>커밋 전에</b> 메시지가 나가고, payments 가 즉시 소비해
 * 아직 존재하지 않는 주문의 결제를 처리해 버린다. 결과 이벤트가 돌아와도 commerce 는
 * 갱신할 주문이 없다 — 스파이크 문서 §7.2 에서 컨슈머가 {@code ORDER_NOT_FOUND} 로
 * 지수 백오프 5회에 빠졌던 것과 같은 함정이다.
 *
 * <p>그래서 여기서는 <b>스프링 애플리케이션 이벤트만 발행</b>하고, 실제 Kafka 전송은
 * {@link PaymentRequestKafkaRelay} 가 {@code AFTER_COMMIT} 에서 수행한다.
 * payments 모듈의 {@code DepositHistoryRecorder} 가 쓰는 것과 같은 관용구다.
 *
 * <p><b>대가:</b> 커밋 후 전송이 실패하면 주문이 {@code PAYMENT_PENDING} 에 갇힌다.
 * 그 유실은 {@code PendingPaymentReconciler} 가 회수한다 (설계 문서 §6.1 · §6.4).
 * 유실 자체를 없애려면 Outbox 가 필요하지만, 회수 장치가 있으면 "영구 손실" 이 아니라
 * "지연된 취소" 로 끝나므로 우선 이 방식으로 간다.
 */
@Slf4j
@Component
@Qualifier("kafkaPaymentAdapter")
@RequiredArgsConstructor
public class KafkaPaymentAdapter implements OrderPaymentAsyncPort {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void requestPayment(String userCode, String orderCode, long totalAmount, List<String> productCodes) {
        eventPublisher.publishEvent(new PaymentRequestReady(userCode, orderCode, totalAmount, productCodes));

        log.debug("[payment:kafka] 결제 요청 예약(커밋 후 발행): userCode={}, orderCode={}", userCode, orderCode);
    }

    /** 커밋 이후 Kafka 로 내보낼 결제 요청. 이 모듈 밖으로 나가지 않는 내부 신호다. */
    public record PaymentRequestReady(String userCode, String orderCode, long totalAmount, List<String> productCodes) {
    }
}
