package io.devground.payments.payment.controller;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.devground.core.model.web.BaseResponse;

import io.devground.payments.payment.mapper.PaymentMapper;
import io.devground.payments.payment.model.dto.request.PaymentRequest;
import io.devground.payments.payment.model.dto.request.TossRefundRequest;
import io.devground.payments.payment.model.dto.response.GetPaymentsResponse;
import io.devground.payments.payment.model.dto.response.TossCheckoutResponse;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.DepositPaymentRequest;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.model.vo.PaymentDescription;
import io.devground.payments.payment.model.vo.PaymentType;
import io.devground.payments.payment.model.vo.TossPaymentConfirmRequest;
import io.devground.payments.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {
	private final PaymentService paymentService;

	@Value("${custom.toss.clientKey}")
	String clientKey;

	@PostMapping("/process")
	public BaseResponse<PaymentDescription> processPayment(
		@RequestHeader("X-CODE") String userCode,
		@RequestBody @Valid PaymentConfirmRequest request
	) {
		log.info("payments Usercode: {}", userCode);
		Payment payment = paymentService.process(userCode, request);

		return BaseResponse.success(
			200,
			PaymentMapper.toDescription(payment),
			"결제를 성공적으로 처리하였습니다."
		);

	}

	@PostMapping("/toss")
	public BaseResponse<PaymentDescription> tossPayment(
		@RequestBody @Valid TossPaymentConfirmRequest request
	) {
		log.info(request.paymentKey());
		Payment payment = paymentService.pay(
			new PaymentRequest(request.userCode(), PaymentType.TOSS_PAYMENT, request.paymentKey(), request.orderCode(), request.amount())
		);

		return BaseResponse.success(
			200,
			PaymentMapper.toDescription(payment),
			"결제를 성공적으로 처리하였습니다."
		);
	}

	@PostMapping("/deposit")
	public BaseResponse<PaymentDescription> depositPayment(
		@RequestHeader("X-CODE") String userCode,
		@RequestBody @Valid DepositPaymentRequest request
	) {

		Payment payment = paymentService.pay(
			new PaymentRequest(userCode, PaymentType.DEPOSIT, null, request.orderCode(), request.amount())
		);

		return BaseResponse.success(
			200,
			PaymentMapper.toDescription(payment),
			"결제를 성공적으로 처리하였습니다."
		);
	}

	/**
	 * 주문 코드로 결제 기록을 조회한다 — commerce 의 결제 회수 스케줄러 전용.
	 *
	 * <p>결제가 없어도 <b>404 가 아니라 200 + {@code data: null}</b> 을 준다.
	 * "결제가 없다" 는 오류가 아니라 회수 판정에 필요한 정상적인 답이기 때문이다.
	 * 404 로 주면 Feign 이 예외를 던져 "조회 실패" 와 구분할 수 없게 되고,
	 * 그 혼동은 <b>이미 돈이 빠진 주문을 취소</b>하는 사고로 이어진다.
	 */
	@GetMapping("/order/{orderCode}")
	public BaseResponse<PaymentLookupResponse> findByOrderCode(@PathVariable String orderCode) {
		return paymentService.findByOrderCode(orderCode)
			.map(payment -> BaseResponse.success(
				200,
				new PaymentLookupResponse(
					payment.getOrderCode(),
					payment.getCode(),
					payment.getPaymentStatus().name(),
					payment.getAmount()
				),
				"결제 기록을 조회했습니다."
			))
			.orElseGet(() -> BaseResponse.success(200, null, "결제 기록이 없습니다."));
	}

	public record PaymentLookupResponse(
		String orderCode,
		String paymentCode,
		String paymentStatus,
		Long amount
	) {
	}

	@GetMapping("/")
	public BaseResponse<Page<GetPaymentsResponse>> getPayments(
		@RequestHeader("X-CODE") String userCode,
		@PageableDefault Pageable pageable
	){
		return BaseResponse.success(200, paymentService.getPayments(userCode, pageable), "결제 내역 조회 성공");
	}

	@PostMapping("/tossRefund/{paymentKey}")
	public BaseResponse<String> refundPayment(
		@RequestHeader("X-CODE") String userCode,
		@PathVariable String paymentKey
	) {
		TossRefundRequest request = new TossRefundRequest(userCode, paymentKey, 10000L);
		paymentService.tossRefund(request);

		return BaseResponse.success(200, "환불 성공");
	}

	@GetMapping("/checkout")
	public BaseResponse<TossCheckoutResponse> viewCheckoutPage(
		@RequestHeader("X-CODE") String userCode
	) {
		//ChargePaymentRequest description = new ChargePaymentRequest(userCode, 10000L);
		String orderCode = UUID.randomUUID().toString();


		TossCheckoutResponse response = new TossCheckoutResponse(
			userCode, orderCode, clientKey, 10000L
		);
		return BaseResponse.success(
			200,
			response,
			"결제 정보를 불러왔습니다."
		);
	}
}
