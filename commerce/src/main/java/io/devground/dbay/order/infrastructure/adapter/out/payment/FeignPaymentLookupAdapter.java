package io.devground.dbay.order.infrastructure.adapter.out.payment;

import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentLookupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 결제 존재 여부 조회 어댑터 (OpenFeign).
 *
 * <p>조회 실패(네트워크 오류 등)와 "결제 없음" 을 반드시 구분한다 —
 * 조회에 실패했는데 "없음" 으로 판정하면 <b>이미 돈이 빠진 주문을 취소</b>해 버린다.
 * 그래서 예외는 삼키지 않고 밖으로 던지고, 회수는 다음 주기로 미룬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeignPaymentLookupAdapter implements OrderPaymentLookupPort {

    private final PaymentFeignClient paymentFeignClient;

    @Override
    public Optional<PaymentSnapshot> findByOrderCode(String orderCode) {
        BaseResponse<PaymentFeignClient.PaymentLookupResponse> response =
                paymentFeignClient.findByOrderCode(orderCode);

        if (!response.isSuccess()) {
            throw new IllegalStateException(
                    "결제 조회 실패: orderCode=" + orderCode + ", msg=" + response.msg());
        }

        PaymentFeignClient.PaymentLookupResponse data = response.data();

        if (data == null) {
            return Optional.empty();   // 결제 기록 없음 — 커맨드가 유실된 것
        }

        return Optional.of(new PaymentSnapshot(
                data.orderCode(), data.paymentCode(), data.paymentStatus(), data.amount()));
    }
}
