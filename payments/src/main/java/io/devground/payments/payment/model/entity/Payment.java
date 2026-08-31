package io.devground.payments.payment.model.entity;

import java.time.LocalDateTime;

import io.devground.core.model.entity.BaseEntity;

import io.devground.payments.payment.model.vo.PaymentStatus;
import io.devground.payments.payment.model.vo.PaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@NoArgsConstructor
public class Payment extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * 주문 코드 — <b>멱등키를 겸한다.</b>
	 *
	 * <p>unique 제약이 중복 결제를 DB 레벨에서 막는다. 클라이언트 재시도나 Kafka 의
	 * at-least-once 재전송으로 같은 주문의 결제 커맨드가 두 번 들어와도 두 번째는
	 * {@code DataIntegrityViolationException} 으로 튕긴다.
	 * 실측된 "유령 주문"(클라 타임아웃 후 재시도로 인한 이중 결제 위험) 대응이다.
	 *
	 * <p>⚠️ 기존 데이터에 중복 {@code orderCode} 가 있으면 인덱스 생성이 실패한다.
	 * 배포 전 {@code SELECT orderCode FROM Payment GROUP BY orderCode HAVING COUNT(*) > 1} 로 확인할 것.
	 */
	@Setter
	@Column(unique = true)
	private String orderCode;

	@Setter
	@Column(nullable = false, columnDefinition = "VARCHAR(36)")
	private String userCode;

	private Long amount;

	@Setter
	private PaymentType paymentType;

	@Setter
	private PaymentStatus paymentStatus;

	@Setter
	private String paymentKey;

	private LocalDateTime paidAt;

	@Builder
	public Payment(String userCode, Long amount, String orderCode, String paymentKey, PaymentStatus paymentStatus) {
		this.userCode = userCode;
		this.amount = amount;
		this.orderCode = orderCode;
		this.paymentKey = paymentKey;
		this.paidAt = LocalDateTime.now();
		this.paymentType = PaymentType.DEPOSIT;
		this.paymentStatus = PaymentStatus.PAYMENT_PENDING;
	}
}