package io.devground.dbay.order.application.port.out.event;

import io.devground.dbay.order.domain.vo.OrderCode;
import io.devground.dbay.order.domain.vo.UserCode;


public interface OrderPublishEventPort {
    void publishRefundEvent(UserCode userCode, Long amount, OrderCode orderCode);
}
