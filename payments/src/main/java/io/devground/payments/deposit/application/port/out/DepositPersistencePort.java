package io.devground.payments.deposit.application.port.out;

import java.util.Optional;

import io.devground.payments.deposit.domain.deposit.Deposit;

public interface DepositPersistencePort {

	Optional<Deposit> getDeposit(String code);

	Optional<Deposit> getDepositByUserCode(String userCode);

	/**
	 * 충전/출금/환불 등 잔액 변경 전용 조회.
	 * SELECT ... FOR UPDATE 로 비관적 락을 획득한다.
	 */
	Optional<Deposit> getDepositByUserCodeForUpdate(String userCode);
}
