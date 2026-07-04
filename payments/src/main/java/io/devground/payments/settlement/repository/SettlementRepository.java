package io.devground.payments.settlement.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.devground.payments.settlement.model.entity.Settlement;
import io.devground.payments.settlement.model.entity.vo.SettlementStatus;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

	/**
	 * 판매자별 정산 조회
	 */
	Page<Settlement> findBySellerCode(String sellerCode, Pageable pageable);

	/**
	 * 정산 상태별 조회
	 */
	Page<Settlement> findBySettlementStatus(SettlementStatus settlementStatus, Pageable pageable);

	/**
	 * 주문 코드로 정산 조회 (Saga 보상 트랜잭션에서 사용)
	 */
	Optional<Settlement> findByOrderCode(String orderCode);
}
