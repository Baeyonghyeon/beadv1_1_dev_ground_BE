package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.port.out.payment.OrderDepositPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeignDepositAdapter implements OrderDepositPort {

    private final DepositFeignClient depositFeignClient;

    @Override
    public long getBalance(String userCode) {
        BaseResponse<DepositFeignClient.DepositBalanceResponse> response =
                depositFeignClient.getBalance(userCode);

        if (!response.isSuccess() || response.data() == null || response.data().balance() == null) {
            throw new IllegalStateException("예치금 잔액 조회 실패: userCode=" + userCode + ", msg=" + response.msg());
        }

        return response.data().balance();
    }
}
