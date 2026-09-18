package io.devground.payments.payment.service;

import io.devground.payments.deposit.application.port.out.DepositHistoryCommandPort;
import io.devground.payments.deposit.application.port.out.DepositPersistencePort;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.deposit.domain.depositHistory.DepositHistory;
import io.devground.payments.deposit.domain.depositHistory.DepositHistoryType;
import io.devground.payments.payment.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 예치금 이력 저장 전용 컴포넌트.
 *
 * <p><b>왜 락 구간 밖으로 뺐는가 (2026-08-26 수정):</b>
 * 이전에는 {@code process()} 안에서 {@code REQUIRES_NEW} 로 직접 호출했다.
 * 그런데 {@code process()} 는 {@code SELECT ... FOR UPDATE} 로 {@code DepositEntity} 행에
 * X 락을 쥔 상태이고, {@code DepositHistoryEntity} 는 그 행을 FK 3개로 참조한다.
 * 별도 트랜잭션에서 자식 행을 INSERT 하면 InnoDB 가 FK 검사를 위해 부모 행에 S 락을 요구하는데,
 * 그 S 락은 자기 자신(바깥 트랜잭션)의 X 락에 막힌다.
 * 바깥 트랜잭션은 이 메서드가 리턴해야 커밋할 수 있으므로 순환 대기가 되고,
 * {@code innodb_lock_wait_timeout}(기본 50초)을 채운 뒤 결제 전체가 롤백됐다.
 *
 * <p><b>두 가지 해결책을 모두 구현해 두고 {@code payments.history.strategy} 로 전환한다.</b>
 *
 * <table border="1">
 *   <caption>전략 비교</caption>
 *   <tr><th></th><th>{@code join} (기본)</th><th>{@code after-commit}</th></tr>
 *   <tr><td>메서드</td><td>{@link #recordPaymentHistoryInTx}</td><td>{@link #onPaymentCompleted}</td></tr>
 *   <tr><td>트랜잭션</td><td>결제 트랜잭션에 합류</td><td>커밋 후 {@code REQUIRES_NEW}</td></tr>
 *   <tr><td>FK 충돌</td><td>없음 — 같은 트랜잭션은 자기 락에 막히지 않는다</td><td>없음 — 커밋 시점에 X 락 해제됨</td></tr>
 *   <tr><td>요청당 커넥션</td><td><b>1개</b></td><td><b>2개</b></td></tr>
 *   <tr><td>이력 저장 실패 시</td><td>결제도 롤백 (감사 기록 보장)</td><td>결제는 유지, 이력만 누락</td></tr>
 * </table>
 *
 * <p>커넥션 풀이 좁을 때 {@code after-commit} 은 요청당 커넥션을 2개 요구해 임계점을 앞당긴다.
 * 실측(2026-08-26, pool=10, 동시 20): 100건 중 41건이 커넥션 타임아웃으로 실패했고,
 * 성공한 결제 59건 중 18건은 리스너가 두 번째 커넥션을 못 받아 <b>이력이 누락</b>됐다.
 * 돈은 빠져나갔는데 감사 기록이 없는 상태라, 기본값은 {@code join} 으로 둔다.
 *
 * <p>주의: {@code after-commit} 리스너는 기본적으로 <b>같은 스레드에서 동기 실행</b>된다.
 * 사용자 응답 시간에 여전히 포함되며, 부하 테스트에 별도 스레드풀 변수를 끌어들이지 않기 위한
 * 의도적인 선택이다.
 *
 * @see io.devground.payments.payment.service.PaymentServiceImpl#process
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepositHistoryRecorder {

    private final DepositPersistencePort depositPersistencePort;
    private final DepositHistoryCommandPort depositHistoryCommandPort;

    /**
     * 결제 트랜잭션이 <b>커밋된 뒤</b> 예치금 이력을 저장한다.
     * 이 시점에는 예치금 행의 X 락이 해제되어 있어 FK 검사가 막히지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        boolean saved = doRecord(event.userCode(), event.depositCode(), event.amount(), event.balanceAfter());

        if (!saved) {
            // 결제는 이미 커밋되었으므로 롤백하지 않는다. 이력만 누락된 상태 → 보정 대상.
            log.error("예치금 이력 누락 (결제는 정상 완료됨): userCode={}, orderCode={}, amount={}",
                    event.userCode(), event.orderCode(), event.amount());
        }
    }

    /**
     * 예치금 결제 이력을 <b>호출자의 트랜잭션에 합류해서</b> 저장한다 ({@code join} 전략).
     *
     * <p>같은 트랜잭션이므로 FK 검사의 S 락 요청이 자기 자신의 X 락과 충돌하지 않는다
     * (InnoDB 는 다른 트랜잭션의 락하고만 호환성을 따진다).
     * 커넥션도 하나만 쓰므로 풀 압박이 {@code after-commit} 전략의 절반이다.
     *
     * <p>대신 이력 저장이 실패하면 결제도 함께 롤백된다.
     * 돈이 빠져나갔는데 감사 기록이 없는 상태보다는 낫다고 보고 예외를 삼키지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordPaymentHistoryInTx(String userCode, String depositCode, long amount, long balanceAfter) {
        Deposit deposit = depositPersistencePort.getDepositByUserCode(userCode)
                .orElseThrow(() -> new IllegalStateException(
                        "예치금 이력 저장 실패: Deposit not found for userCode=" + userCode));

        depositHistoryCommandPort.saveDepositHistory(
                buildHistory(deposit.getCode(), userCode, amount, balanceAfter));

        log.debug("예치금 이력 저장 완료(join): userCode={}, amount={}", userCode, amount);
    }

    /**
     * 예치금 결제 이력을 저장한다.
     * 별도 트랜잭션(REQUIRES_NEW)으로 실행되며, 실패 시 예외를 던지지 않고 로그만 남긴다.
     *
     * <p><b>호출 제약:</b> 예치금 행에 락을 쥔 트랜잭션 안에서 호출하면 안 된다 (클래스 주석 참조).
     * 결제 경로에서는 {@link #onPaymentCompleted} 를 통해 커밋 이후에 실행된다.
     *
     * @return true if saved successfully, false otherwise
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordPaymentHistory(String userCode, String depositCode, long amount, long balanceAfter) {
        return doRecord(userCode, depositCode, amount, balanceAfter);
    }

    private boolean doRecord(String userCode, String depositCode, long amount, long balanceAfter) {
        try {
            Deposit deposit = depositPersistencePort.getDepositByUserCode(userCode).orElse(null);
            if (deposit == null) {
                log.warn("예치금 이력 저장 실패: Deposit not found for userCode={}", userCode);
                return false;
            }

            depositHistoryCommandPort.saveDepositHistory(buildHistory(depositCode, userCode, amount, balanceAfter));
            log.debug("예치금 이력 저장 완료(after-commit): userCode={}, amount={}", userCode, amount);
            return true;
        } catch (Exception e) {
            log.error("예치금 이력 저장 실패 (결제는 정상 처리됨): userCode={}, amount={}", userCode, amount, e);
            return false;
        }
    }

    private DepositHistory buildHistory(String depositCode, String userCode, long amount, long balanceAfter) {
        return new DepositHistory(
                UUID.randomUUID().toString(),
                userCode,
                depositCode,
                depositCode,
                depositCode,
                amount,
                balanceAfter,
                DepositHistoryType.PAYMENT_INTERNAL,
                String.format("예치금 결제 %,d원", amount),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
