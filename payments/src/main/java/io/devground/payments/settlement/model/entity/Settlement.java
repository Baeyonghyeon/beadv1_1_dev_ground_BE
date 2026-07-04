package io.devground.payments.settlement.model.entity;

import java.time.LocalDateTime;

import io.devground.core.model.entity.BaseEntity;
import io.devground.payments.settlement.model.entity.vo.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String orderCode;

	@Column(nullable = false)
	private String orderItemCode;

	@Enumerated(EnumType.STRING)
	private SettlementStatus settlementStatus = SettlementStatus.SETTLEMENT_CREATED;

	private String depositHistoryCode;

	@Column(nullable = false)
	private String buyerCode;

	@Column(nullable = false)
	private String sellerCode;

	// 정산 일자
	@Column(nullable = false)
	private LocalDateTime settlementDate;

	// 정산율
	@Column(nullable = false)
	private Double settlementRate;

	// 물품 총액
	@Column(nullable = false)
	private Long totalAmount;

	// 정산 수수료
	private Long settlementAmount;

	// 정산 잔액
	private Long settlementBalance;

	@Builder
	public Settlement(String orderCode, String orderItemCode, String buyerCode, String sellerCode,
		LocalDateTime settlementDate, Double settlementRate, Long totalAmount) {
		this.orderCode = orderCode;
		this.orderItemCode = orderItemCode;
		this.buyerCode = buyerCode;
		this.sellerCode = sellerCode;
		this.settlementDate = settlementDate;
		this.settlementRate = settlementRate;
		this.totalAmount = totalAmount;
	}

	public void process() {
		this.settlementStatus = SettlementStatus.SETTLEMENT_PROCESSING;
	}

	public void done() {
		this.settlementStatus = SettlementStatus.SETTLEMENT_SUCCESS;
		this.settlementDate = LocalDateTime.now();
	}

	public void fail() {
		this.settlementStatus = SettlementStatus.SETTLEMENT_FAILED;
	}

	private long calcSettlementAmount() {
		return (long) this.totalAmount - calcSettlementBalance();
	}

	private long calcSettlementBalance() {
		return (long) (this.totalAmount * this.settlementRate);
	}

	public void setDepositHistoryCode(String depositHistoryCode) {
		this.depositHistoryCode = depositHistoryCode;
	}

	public void applySettlementPolicy() {
		this.settlementBalance = calcSettlementBalance();
		this.settlementAmount = calcSettlementAmount();
	}

}
