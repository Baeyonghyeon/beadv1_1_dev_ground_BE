package io.devground.dbay.order.infrastructure.adapter.out.kafka;

import io.devground.core.commands.cart.DeleteCartItemsCommand;
import io.devground.core.commands.order.CompleteOrderCommand;
import io.devground.core.commands.payment.DepositRefundCommand;
import io.devground.core.commands.product.ProductSoldCommand;
import io.devground.dbay.order.application.port.out.kafka.OrderKafkaEventPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderKafkaEventPublisherAdapter implements OrderKafkaEventPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${orders.command.topic.purchase}")
    private String ordersCommandTopicName;

    @Value("${payments.command.topic.purchase}")
    private String paymentsCommandTopicName;

    @Value("${deposits.command.topic.purchase}")
    private String depositsCommandTopicName;

    @Value("${carts.command.topic.purchase}")
    private String cartsCommandTopicName;

    @Value("${products.command.purchase}")
    private String productsCommandTopicName;

    @Override
    public void publishDepositSuccessCompleteOrder(String userCode, String orderCode) {
        kafkaTemplate.send(ordersCommandTopicName, orderCode, new CompleteOrderCommand(
                userCode,
                orderCode
        ));
    }

    @Override
    public void publishDepositSuccessCompleteDeleteCart(String userCode, String orderCode, List<String> productCodes) {
        kafkaTemplate.send(cartsCommandTopicName, orderCode, new DeleteCartItemsCommand(
                userCode,
                productCodes
        ));
    }

    @Override
    public void publishDepositRefundCreated(String userCode, Long amount, String orderCode) {
        kafkaTemplate.send(paymentsCommandTopicName, orderCode, new DepositRefundCommand(userCode, amount, orderCode));
    }

    @Override
    public void publishDepositSuccessCompleteProduct(String orderCode, List<String> productCodes) {
        kafkaTemplate.send(productsCommandTopicName, orderCode, new ProductSoldCommand(productCodes));
    }
}
