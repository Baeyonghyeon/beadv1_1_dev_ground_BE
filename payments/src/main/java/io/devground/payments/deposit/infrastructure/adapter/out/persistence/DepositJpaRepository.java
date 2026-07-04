package io.devground.payments.deposit.infrastructure.adapter.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.devground.payments.deposit.infrastructure.model.persistence.DepositEntity;
import jakarta.persistence.LockModeType;

public interface DepositJpaRepository extends JpaRepository<DepositEntity, Integer> {

	Optional<DepositEntity> findByCode(String code);

	void deleteByCode(String code);

	Optional<DepositEntity> findByUserCode(String userCode);

	/**
	 * 예치금 충전/출금/환불 시 동시성 제어를 위한 비관적 락.
	 * SELECT ... FOR UPDATE 로 조회하여 트랜잭션 종료까지 해당 row 를 다른 트랜잭션이 읽지 못하게 막는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT d FROM DepositEntity d WHERE d.userCode = :userCode")
	Optional<DepositEntity> findByUserCodeForUpdate(@Param("userCode") String userCode);
}
