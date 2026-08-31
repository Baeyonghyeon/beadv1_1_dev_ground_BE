package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.core.model.web.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Commerce → Payments 예치금 잔액 조회 클라이언트 (결제 사전 확인용).
 */
@FeignClient(
        name = "order-to-deposit",
        url = "${external.payment-url:http://localhost:8085}",
        path = "/api/deposits"
)
public interface DepositFeignClient {

    @GetMapping("/balance")
    BaseResponse<DepositBalanceResponse> getBalance(@RequestHeader("X-CODE") String userCode);

    record DepositBalanceResponse(Long balance) {
    }
}
