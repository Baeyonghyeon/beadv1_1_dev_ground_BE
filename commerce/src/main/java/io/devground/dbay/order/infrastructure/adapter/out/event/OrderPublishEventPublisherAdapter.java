package io.devground.dbay.order.infrastructure.adapter.out.event;

import io.devground.core.commands.payment.DepositRefundCommand;
import io.devground.dbay.order.application.port.out.event.OrderPublishEventPort;
import io.devground.dbay.order.domain.vo.OrderCode;
import io.devground.dbay.order.domain.vo.UserCode;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class OrderPublishEventPublisherAdapter implements OrderPublishEventPort {

    private final ApplicationEventPublisher publisher;

    @Override
    public void publishRefundEvent(UserCode userCode, Long amount, OrderCode orderCode) {
        publisher.publishEvent(new DepositRefundCommand(userCode.value(), amount, orderCode.value()));
    }
}
