package io.devground.dbay.order.infrastructure.adapter.out.event;

import io.devground.core.commands.payment.DepositRefundCommand;
import io.devground.dbay.order.application.port.out.kafka.OrderKafkaEventPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 취소 시 환불 커맨드를 커밋 이후에 Kafka 로 발행한다.
 *
 * <p>이전에는 주문 생성 이벤트({@code OrderCreatedEvent})도 여기서 발행해 Kafka Saga 결제를 시작했으나,
 * 결제를 동기 처리로 되돌리면서(2026-08-28) 그 경로를 제거했다. 지금은 환불만 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRefundEventPublisher {

    private final OrderKafkaEventPort orderEventPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundCreated(DepositRefundCommand command) {
        orderEventPort.publishDepositRefundCreated(command.userCode(), command.amount(), command.orderCode());
    }
}
