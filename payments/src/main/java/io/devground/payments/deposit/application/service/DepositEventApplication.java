package io.devground.payments.deposit.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.devground.payments.deposit.application.exception.ServiceException;
import io.devground.payments.deposit.application.exception.vo.ServiceErrorCode;
import io.devground.payments.deposit.application.port.out.DepositCommandPort;
import io.devground.payments.deposit.application.port.out.DepositHistoryCommandPort;
import io.devground.payments.deposit.application.port.out.DepositPersistencePort;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.deposit.domain.depositHistory.DepositHistory;
import io.devground.payments.deposit.domain.depositHistory.DepositHistoryType;
import io.devground.payments.deposit.domain.port.in.DepositEventUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositEventApplication implements DepositEventUseCase {

	private final DepositPersistencePort depositPersistencePort;
	private final DepositCommandPort depositCommandPort;
	private final DepositHistoryCommandPort depositHistoryCommandPort;

	@Override
	@Transactional
	public Deposit createDeposit(String userCode) {
		log.info("deposit userCode: {}", userCode);
		Deposit deposit = new Deposit(userCode);
		return depositCommandPort.saveDeposit(deposit);
	}

	@Override
	@Transactional
	public DepositHistory charge(String userCode, DepositHistoryType type, Long amount) {
		log.info("Processing charge - userCode: {}, type: {}, amount: {}", userCode, type, amount);

		Deposit deposit = depositPersistencePort.getDepositByUserCodeForUpdate(userCode)
			.orElseThrow(() -> new ServiceException(ServiceErrorCode.DEPOSIT_NOT_FOUND));

		// 충전 처리
		deposit.charge(amount);
		depositCommandPort.saveDeposit(deposit);

		// 이력 저장
		DepositHistory history = DepositHistory.builder()
			.userCode(userCode)
			.deposit(deposit)
			.payerDeposit(deposit)
			.payeeDeposit(deposit)
			.amount(amount)
			.type(type)
			.description(generateDescription(type, amount))
			.build();

		return depositHistoryCommandPort.saveDepositHistory(history);
	}

	@Override
	@Transactional
	public DepositHistory withdraw(String userCode, DepositHistoryType type, Long amount) {
		log.info("Processing withdraw - userCode: {}, type: {}, amount: {}", userCode, type, amount);

		Deposit deposit = depositPersistencePort.getDepositByUserCodeForUpdate(userCode)
			.orElseThrow(() -> new ServiceException(ServiceErrorCode.DEPOSIT_NOT_FOUND));

		// 출금 처리
		deposit.withdraw(amount);
		depositCommandPort.saveDeposit(deposit);

		// 이력 저장
		DepositHistory history = DepositHistory.builder()
			.userCode(userCode)
			.deposit(deposit)
			.payerDeposit(deposit)
			.payeeDeposit(deposit)
			.amount(amount)
			.type(type)
			.description(generateDescription(type, amount))
			.build();

		return depositHistoryCommandPort.saveDepositHistory(history);
	}

	@Override
	@Transactional
	public DepositHistory refund(String userCode, DepositHistoryType type, Long amount) {
		log.info("Processing refund - userCode: {}, type: {}, amount: {}", userCode, type, amount);

		Deposit deposit = depositPersistencePort.getDepositByUserCodeForUpdate(userCode)
			.orElseThrow(() -> new ServiceException(ServiceErrorCode.DEPOSIT_NOT_FOUND));

		// 환불 처리 (충전과 동일)
		deposit.charge(amount);
		depositCommandPort.saveDeposit(deposit);

		// 이력 저장
		DepositHistory history = DepositHistory.builder()
			.userCode(userCode)
			.deposit(deposit)
			.payerDeposit(deposit)
			.payeeDeposit(deposit)
			.amount(amount)
			.type(type)
			.description(generateDescription(type, amount))
			.build();

		return depositHistoryCommandPort.saveDepositHistory(history);
	}

	@Override
	@Transactional
	public void deleteDeposit(String userCode) {
		Deposit deposit = depositPersistencePort.getDepositByUserCodeForUpdate(userCode)
			.orElseThrow(() -> new ServiceException(ServiceErrorCode.DEPOSIT_NOT_FOUND));

		depositCommandPort.removeDeposit(deposit.getCode());
	}

	private String generateDescription(DepositHistoryType type, Long amount) {
		return switch (type) {
			case CHARGE_TRANSFER -> String.format("계좌 이체 충전 %,d원", amount);
			case CHARGE_TOSS -> String.format("토스 결제 충전 %,d원", amount);
			case PAYMENT_TOSS -> String.format("토스 결제 %,d원", amount);
			case PAYMENT_INTERNAL -> String.format("예치금 결제 %,d원", amount);
			case REFUND_INTERNAL -> String.format("예치금 환불 %,d원", amount);
			case REFUND_TOSS -> String.format("토스 환불 %,d원", amount);
			case SETTLEMENT -> String.format("정산 입금 %,d원", amount);
		};
	}
}
