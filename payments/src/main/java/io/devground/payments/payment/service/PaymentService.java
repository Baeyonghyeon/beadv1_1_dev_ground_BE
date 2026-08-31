package io.devground.payments.payment.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import io.devground.payments.payment.model.dto.request.PaymentRequest;
import io.devground.payments.payment.model.dto.request.RefundRequest;
import io.devground.payments.payment.model.dto.request.TossRefundRequest;
import io.devground.payments.payment.model.dto.response.GetPaymentsResponse;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;

import java.util.Optional;

public interface PaymentService {
	Payment process(String userCode, PaymentConfirmRequest request);

	/**
	 * 주문 코드로 결제 기록을 조회한다.
	 *
	 * <p>두 곳에서 쓴다 — 중복 커맨드가 unique 제약에 걸렸을 때 기존 결제를 찾는 용도,
	 * 그리고 commerce 의 회수 스케줄러가 "결제가 실제로 있었는가" 를 묻는 용도다.
	 */
	Optional<Payment> findByOrderCode(String orderCode);

	Payment pay(PaymentRequest request);

	void refund(RefundRequest request);

	void tossRefund(TossRefundRequest request);

	Payment confirmPayment(PaymentRequest request) throws Exception;

	Page<GetPaymentsResponse> getPayments(String userCode, Pageable pageable);

	void applyDepositPayment(String orderCode);

	void applyDepositCharge(String userCode);

	void cancelDepositPayment(String orderCode);
}
