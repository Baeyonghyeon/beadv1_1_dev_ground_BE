package io.devground.dbay.order.payment;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.port.out.payment.PaymentResult;
import io.devground.dbay.order.infrastructure.adapter.out.payment.FeignPaymentAdapter;
import io.devground.dbay.order.infrastructure.adapter.out.payment.PaymentFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 결제 호출 실패의 <b>분류</b> 검증.
 *
 * <p>예전에는 모든 예외를 {@code catch (Exception)} 으로 삼켜 하나의 실패로 뭉갰다.
 * 그러면 호출부가 <b>결제가 실제로 일어났는지</b> 알 수 없고, 그 구분이 주문을 취소해도 되는지를 정한다.
 * <b>돈이 빠진 주문을 취소하면 복구가 불가능하므로</b>, 이 분류가 이 어댑터의 가장 중요한 책임이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeignPaymentAdapter - 실패 분류")
class FeignPaymentAdapterTest {

    @Mock private PaymentFeignClient paymentFeignClient;
    private FeignPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FeignPaymentAdapter(paymentFeignClient, new ObjectMapper());
    }

    private static final String USER = "USER-1";
    private static final String ORDER = "ORDER-1";

    private PaymentResult call() {
        return adapter.processPayment(USER, ORDER, 5_000L, List.of("PROD-1"));
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST, "/api/payments/process",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
    }

    @Test
    @DisplayName("정상 응답이면 SUCCESS")
    void 성공() {
        when(paymentFeignClient.processPayment(anyString(), any())).thenReturn(
                BaseResponse.success(200, new PaymentFeignClient.PaymentFeignResponse(ORDER, "PAY-1")));

        assertThat(call().outcome()).isEqualTo(PaymentResult.Outcome.SUCCESS);
    }

    @Test
    @DisplayName("4xx 응답은 REJECTED — 차감이 없으므로 주문을 취소해도 안전하다")
    void 사xx는_거절이다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new FeignException.BadRequest("잔액이 부족합니다.", dummyRequest(), null, null));

        PaymentResult result = call();

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.REJECTED);
        assertThat(result.safeToCancel()).isTrue();
    }

    @Test
    @DisplayName("연결 거부는 UNAVAILABLE — 요청이 가지 않았으므로 취소해도 안전하다")
    void 연결거부는_서비스불가다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new RetryableException(-1, "Connection refused", Request.HttpMethod.POST,
                        new ConnectException("Connection refused"), (Long) null, dummyRequest()));

        PaymentResult result = call();

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.UNAVAILABLE);
        assertThat(result.safeToCancel()).isTrue();
    }

    @Test
    @DisplayName("읽기 타임아웃은 UNKNOWN — 결제가 됐을 수 있어 취소하면 안 된다 ← 가장 중요")
    void 타임아웃은_결과불명이다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                        new SocketTimeoutException("Read timed out"), (Long) null, dummyRequest()));

        PaymentResult result = call();

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.UNKNOWN);
        // 이 단언이 깨지면 돈이 빠진 주문을 취소하게 된다 — 복구 불가능한 사고
        assertThat(result.safeToCancel()).isFalse();
    }

    @Test
    @DisplayName("연결 타임아웃은 UNAVAILABLE — 요청이 서버에 닿지 않았다")
    void 연결_타임아웃은_서비스불가다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new RetryableException(-1, "Connect timed out", Request.HttpMethod.POST,
                        new SocketTimeoutException("Connect timed out"), (Long) null, dummyRequest()));

        PaymentResult result = call();

        // 읽기 타임아웃과 달리 요청이 가지 않았으므로 취소해도 안전하다
        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.UNAVAILABLE);
        assertThat(result.safeToCancel()).isTrue();
    }

    @Test
    @DisplayName("거절 사유는 payments 가 내려준 문구를 그대로 쓴다 — 내부 URL이 새어나가지 않게")
    void 거절_사유를_본문에서_꺼낸다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new FeignException.BadRequest(
                        "[400] during [POST] to [http://payments:8085/...]",
                        dummyRequest(),
                        "{\"resultCode\":400,\"msg\":\"잔액이 부족합니다.\",\"data\":null}".getBytes(StandardCharsets.UTF_8),
                        null));

        PaymentResult result = call();

        assertThat(result.message()).isEqualTo("잔액이 부족합니다.");
        assertThat(result.message()).doesNotContain("http://");
    }

    @Test
    @DisplayName("5xx 응답도 UNKNOWN — payments 내부 오류는 차감 여부를 단정할 수 없다")
    void 오xx도_결과불명이다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new FeignException.InternalServerError("서버 오류", dummyRequest(), null, null));

        assertThat(call().safeToCancel()).isFalse();
    }

    @Test
    @DisplayName("분류되지 않은 예외는 UNKNOWN — 모를 때는 취소하지 않는 쪽이 안전하다")
    void 알수없는_예외는_보수적으로_판단한다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenThrow(new RuntimeException("무슨 일인지 모름"));

        PaymentResult result = call();

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.UNKNOWN);
        assertThat(result.safeToCancel()).isFalse();
    }

    @Test
    @DisplayName("2xx 인데 success=false 인 응답은 REJECTED")
    void 업무실패_응답은_거절이다() {
        when(paymentFeignClient.processPayment(anyString(), any()))
                .thenReturn(BaseResponse.fail(400, "잔액이 부족합니다."));

        assertThat(call().outcome()).isEqualTo(PaymentResult.Outcome.REJECTED);
    }
}
