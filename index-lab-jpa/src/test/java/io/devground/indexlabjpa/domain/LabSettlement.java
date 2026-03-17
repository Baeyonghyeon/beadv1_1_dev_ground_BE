package io.devground.indexlabjpa.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "lab_settlement")
public class LabSettlement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 32)
	private String sellerCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private LabSettlementStatus settlementStatus;

	@Column(nullable = false)
	private LocalDateTime settlementDate;

	@Column(nullable = false)
	private Long settlementBalance;

	public Long getId() {
		return id;
	}

	public String getSellerCode() {
		return sellerCode;
	}

	public LabSettlementStatus getSettlementStatus() {
		return settlementStatus;
	}

	public LocalDateTime getSettlementDate() {
		return settlementDate;
	}

	public Long getSettlementBalance() {
		return settlementBalance;
	}
}

