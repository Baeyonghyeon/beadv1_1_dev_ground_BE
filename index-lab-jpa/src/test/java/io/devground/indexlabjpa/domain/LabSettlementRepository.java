package io.devground.indexlabjpa.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LabSettlementRepository extends JpaRepository<LabSettlement, Long> {

	List<LabSettlement> findBySellerCodeAndSettlementStatusOrderBySettlementDateDesc(
		String sellerCode,
		LabSettlementStatus settlementStatus,
		Pageable pageable
	);
}

