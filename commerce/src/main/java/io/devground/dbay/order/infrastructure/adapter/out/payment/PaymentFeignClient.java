package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.core.model.web.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * Commerce → Payments 동기 결제 호출을 위한 OpenFeign 클라이언트.
 */
@FeignClient(
        name = "order-to-payment",
        url = "${external.payment-url:http://localhost:8085}",
        path = "/api/payments"
)
public interface PaymentFeignClient {

    @PostMapping("/process")
    BaseResponse<PaymentFeignResponse> processPayment(
            @RequestHeader("X-CODE") String userCode,
            @RequestBody PaymentFeignRequest request
    );

    record PaymentFeignRequest(
            String orderCode,
            Boolean useDeposit,
            Long amount,
            String paymentKey,
            List<String> productCodes
    ) {
        public static PaymentFeignRequest of(String orderCode, long amount, List<String> productCodes) {
            return new PaymentFeignRequest(orderCode, true, amount, null, productCodes);
        }
    }

    record PaymentFeignResponse(
            String orderCode,
            String paymentCode
    ) {
    }
}
