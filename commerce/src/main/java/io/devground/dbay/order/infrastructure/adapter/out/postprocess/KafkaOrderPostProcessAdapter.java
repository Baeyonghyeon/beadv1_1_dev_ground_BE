package io.devground.dbay.order.infrastructure.adapter.out.postprocess;

import io.devground.dbay.order.application.port.out.kafka.OrderKafkaEventPort;
import io.devground.dbay.order.application.port.out.postprocess.OrderPostProcessPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Arm B 구현체: 후속 처리를 Kafka 커맨드로 발행하고 즉시 반환한다.
 *
 * <p>사용자는 세 건의 DB 작업을 기다리지 않는다. 실제 처리는
 * {@code OrderCommandConsumer} / {@code CartCommandConsumer} 가 백그라운드에서 수행한다.
 *
 * <p>대가는 <b>최종 일관성 지연</b>이다 — 응답 시점에 주문은 아직 {@code PENDING} 이다.
 * 이 지연은 {@code Orders.createdAt → PAID updatedAt} 으로 측정한다 (설계 §4.1).
 */
@Slf4j
@Component
@Qualifier("kafkaOrderPostProcessAdapter")
@RequiredArgsConstructor
public class KafkaOrderPostProcessAdapter implements OrderPostProcessPort {

    private final OrderKafkaEventPort orderKafkaEventPort;

    @Override
    public void completeOrder(String userCode, String orderCode, List<String> productCodes) {
        orderKafkaEventPort.publishDepositSuccessCompleteOrder(userCode, orderCode);
        orderKafkaEventPort.publishDepositSuccessCompleteDeleteCart(userCode, orderCode, productCodes);
        orderKafkaEventPort.publishDepositSuccessCompleteProduct(orderCode, productCodes);

        log.debug("[postprocess:kafka] 후속 커맨드 3건 발행: orderCode={}", orderCode);
    }
}
