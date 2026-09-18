package io.devground.dbay.order.infrastructure.adapter.out.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.RetryableException;
import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentPort;
import io.devground.dbay.order.application.port.out.payment.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.List;

/**
 * OpenFeign 으로 payments 모듈에 동기 결제를 요청한다.
 * payments 모듈 내에서 예치금 차감 + 결제 저장이 하나의 {@code @Transactional} 로 원자 처리된다.
 *
 * <p><b>이 어댑터의 책임은 "실패를 분류하는 것" 이다.</b> 예전에는 모든 예외를
 * {@code catch (Exception)} 으로 삼켜 하나의 실패로 뭉갰는데, 그러면 호출부가
 * <b>결제가 실제로 일어났는지</b> 알 수 없다. 그 구분이 주문을 취소해도 되는지를 정하므로
 * (돈이 빠진 주문을 취소하면 복구가 불가능하다) 여기서 반드시 나눠야 한다.
 *
 * @see PaymentResult
 */
@Slf4j
@Component
@Qualifier("feignPaymentAdapter")
@RequiredArgsConstructor
public class FeignPaymentAdapter implements OrderPaymentPort {

    private final PaymentFeignClient paymentFeignClient;
    private final ObjectMapper objectMapper;

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

            // 2xx 인데 성공이 아닌 응답 — 업무 거절로 본다 (차감 없음)
            log.warn("[FeignPayment] 결제 거절: userCode={}, orderCode={}, msg={}",
                    userCode, orderCode, response.msg());
            return PaymentResult.rejected(orderCode, response.msg());

        } catch (RetryableException e) {
            // Feign 은 연결 실패와 읽기 타임아웃을 모두 RetryableException 으로 감싼다.
            // 둘은 의미가 정반대라 원인을 열어봐야 한다.
            if (e.getCause() instanceof SocketTimeoutException && !isConnectPhase(e)) {
                // 연결은 됐는데 응답이 안 왔다 — payments 가 차감을 끝냈을 수도 있다.
                log.error("[FeignPayment] 결제 응답 타임아웃 (결제 여부 불명): userCode={}, orderCode={}",
                        userCode, orderCode, e);
                return PaymentResult.unknown(orderCode, "결제 응답 시간이 초과되었습니다.");
            }

            // 연결 자체가 안 됐다(거부 또는 연결 타임아웃) — 요청이 가지 않았으므로 차감도 없다.
            log.error("[FeignPayment] 결제 서비스 연결 실패: userCode={}, orderCode={}", userCode, orderCode, e);
            return PaymentResult.unavailable(orderCode, "결제 서비스에 연결할 수 없습니다.");

        } catch (FeignException e) {
            // payments 가 상태 코드로 답한 경우. 4xx 는 업무 거절(차감 없음),
            // 5xx 는 payments 내부 오류로 차감 여부를 단정할 수 없다.
            if (e.status() >= 400 && e.status() < 500) {
                String reason = extractMessage(e);
                log.warn("[FeignPayment] 결제 거절({}): userCode={}, orderCode={}, reason={}",
                        e.status(), userCode, orderCode, reason);
                return PaymentResult.rejected(orderCode, reason);
            }

            log.error("[FeignPayment] 결제 서비스 오류({}) (결제 여부 불명): userCode={}, orderCode={}",
                    e.status(), userCode, orderCode, e);
            return PaymentResult.unknown(orderCode, "결제 처리 중 오류가 발생했습니다.");

        } catch (Exception e) {
            // 분류하지 못한 예외는 **가장 안전한 쪽**으로 본다 — 결제됐을 수 있다고 가정한다.
            // 여기서 "실패했다" 고 단정해 주문을 취소하면 돈이 빠진 주문을 지우게 된다.
            log.error("[FeignPayment] 분류되지 않은 예외 (결제 여부 불명): userCode={}, orderCode={}",
                    userCode, orderCode, e);
            return PaymentResult.unknown(orderCode, "결제 처리 중 오류가 발생했습니다.");
        }
    }

    /**
     * 연결 단계에서 끊겼는가 — 그렇다면 요청이 서버에 도달하지 못했으므로 차감도 없다.
     *
     * <p>Feign 은 연결 타임아웃과 읽기 타임아웃을 <b>둘 다</b> {@code SocketTimeoutException} 으로 감싸
     * 구분할 방법을 주지 않는다. JDK 가 붙이는 메시지("Connect timed out" / "Read timed out")로 가른다.
     * 문자열 판별이라 취약하므로, <b>확신이 없으면 연결 실패가 아닌 쪽(결과 불명)으로 본다</b> —
     * 잘못 판단해 주문을 취소하면 돈이 빠진 주문을 지우게 되기 때문이다.
     */
    private boolean isConnectPhase(Throwable e) {
        String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        return message != null && message.toLowerCase().contains("connect timed out");
    }

    /** payments 가 내려준 사용자용 메시지를 꺼낸다. 실패하면 일반 문구로 대체한다. */
    private String extractMessage(FeignException e) {
        try {
            BaseResponse<?> body = objectMapper.readValue(e.contentUTF8(), BaseResponse.class);
            if (body.msg() != null && !body.msg().isBlank()) {
                return body.msg();
            }
        } catch (Exception ignored) {
            // 본문이 없거나 형식이 다르면 아래 기본 문구를 쓴다
        }
        return "결제가 거절되었습니다.";
    }
}
