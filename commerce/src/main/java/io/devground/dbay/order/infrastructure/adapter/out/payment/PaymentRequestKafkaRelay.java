package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.core.commands.payment.PaymentRequestedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * 주문 트랜잭션이 <b>커밋된 뒤</b> 결제 커맨드를 Kafka 로 발행한다.
 * 이유는 {@link KafkaPaymentAdapter} 클래스 주석 참조.
 *
 * <p>파티션 키는 {@code userCode} 다. 예치금 잔액이 유저 단위 자원이라,
 * 같은 유저의 결제를 한 파티션에 모아 비관적 락 경합을 줄인다 (설계 문서 §5.2).
 * {@code orderCode} 로 키를 주면 같은 유저의 동시 결제가 파티션 수만큼 흩어져 락 대기가 늘어난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestKafkaRelay {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${payments.command.topic.purchase}")
    private String paymentsCommandTopic;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommit(KafkaPaymentAdapter.PaymentRequestReady request) {
        PaymentRequestedCommand command = new PaymentRequestedCommand(
                request.orderCode(),
                request.userCode(),
                request.totalAmount(),
                request.productCodes(),
                Instant.now()
        );

        try {
            kafkaTemplate.send(paymentsCommandTopic, request.userCode(), command);

            log.info("[payment:kafka] 결제 요청 발행: userCode={}, orderCode={}, amount={}",
                    request.userCode(), request.orderCode(), request.totalAmount());
        } catch (Exception e) {
            // 주문은 이미 커밋됐다. 여기서 예외를 던져도 되돌릴 게 없으므로 로그만 남기고
            // 회수는 PendingPaymentReconciler 에 맡긴다.
            log.error("[payment:kafka] 결제 요청 발행 실패 — 주문이 PAYMENT_PENDING 에 남는다: orderCode={}",
                    request.orderCode(), e);
        }
    }
}
