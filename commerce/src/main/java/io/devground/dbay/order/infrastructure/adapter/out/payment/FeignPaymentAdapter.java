package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentPort;
import io.devground.dbay.order.application.port.out.payment.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TO-BE 구현체: OpenFeign 으로 payments 모듈에 동기 결제를 요청한다.
 * payments 모듈 내에서 예치금 차감+결제 저장이 하나의 @Transactional 로 원자 처리된다.
 */
@Slf4j
@Component
@Qualifier("feignPaymentAdapter")
@RequiredArgsConstructor
public class FeignPaymentAdapter implements OrderPaymentPort {

    private final PaymentFeignClient paymentFeignClient;

    @Override
    public PaymentResult processPayment(String userCode, String orderCode, long totalAmount, List<String> productCodes) {
        try {
            PaymentFeignClient.PaymentFeignRequest request =
                    PaymentFeignClient.PaymentFeignRequest.of(orderCode, totalAmount, productCodes);

            BaseResponse<PaymentFeignClient.PaymentFeignResponse> response =
                    paymentFeignClient.processPayment(userCode, request);

            if (response.isSuccess() && response.data() != null) {
                log.info("[FeignPayment] 결제 성공: userCode={}, orderCode={}, paymentCode={}",
                        userCode, orderCode, response.data().paymentCode());
                return PaymentResult.success(orderCode, totalAmount);
            }

            log.warn("[FeignPayment] 결제 실패: userCode={}, orderCode={}, msg={}",
                    userCode, orderCode, response.msg());
            return PaymentResult.fail(orderCode, response.msg());

        } catch (Exception e) {
            log.error("[FeignPayment] 결제 호출 중 예외: userCode={}, orderCode={}", userCode, orderCode, e);
            return PaymentResult.fail(orderCode, "결제 서비스 호출 실패: " + e.getMessage());
        }
    }
}
