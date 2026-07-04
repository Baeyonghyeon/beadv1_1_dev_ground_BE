package io.devground.payments.deposit.infrastructure.adapter.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.devground.payments.deposit.application.port.out.DepositPersistencePort;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.deposit.infrastructure.mapper.DepositMapper;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DepositPersistenceAdapter implements DepositPersistencePort {

	private final DepositJpaRepository depositJpaRepository;

	@Override
	public Optional<Deposit> getDeposit(String code) {
		return depositJpaRepository.findByCode(code)
			.map(DepositMapper::toDomain);
	}

	@Override
	public Optional<Deposit> getDepositByUserCode(String userCode) {
		return depositJpaRepository.findByUserCode(userCode)
			.map(DepositMapper::toDomain);
	}

	@Override
	public Optional<Deposit> getDepositByUserCodeForUpdate(String userCode) {
		return depositJpaRepository.findByUserCodeForUpdate(userCode)
			.map(DepositMapper::toDomain);
	}

}
