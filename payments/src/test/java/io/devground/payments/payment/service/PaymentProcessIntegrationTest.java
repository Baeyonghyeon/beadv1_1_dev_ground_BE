package io.devground.payments.payment.service;

import io.devground.payments.deposit.application.exception.ServiceException;
import io.devground.payments.deposit.application.port.out.DepositCommandPort;
import io.devground.payments.deposit.application.port.out.DepositHistoryCommandPort;
import io.devground.payments.deposit.application.port.out.DepositPersistencePort;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.deposit.domain.pagination.PageQuery;
import io.devground.payments.deposit.domain.pagination.SortSpec;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.model.vo.PaymentStatus;
import io.devground.payments.payment.repository.PaymentRepository;
import io.devground.payments.payment.service.DepositHistoryRecorder;
import io.devground.payments.support.PaymentIntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static org.assertj.core.api.Assertions.*;

/**
 * PaymentServiceImpl.process() 통합 테스트.
 *
 * 실제 MySQL + Kafka 컨테이너를 띄우고 실행한다.
 * 테스트 실행 전에 Docker 가 실행 중이어야 한다.
 *
 * 실행 시간: 약 30~60초 (컨테이너 최초 다운로드 시 더 오래 걸릴 수 있음)
 */
@Slf4j
@SpringBootTest
class PaymentProcessIntegrationTest extends PaymentIntegrationTestBase {

    /** A/B 테스트 동시 요청 건수 */
    private static final int AB_CONCURRENCY = 100;
    /** A/B 테스트 요청 1건당 결제 금액 */
    private static final long AB_UNIT_AMOUNT = 1_000L;
    /** A/B 테스트 초기 잔액 = 전 건이 성공해야 정확히 0 이 되도록 맞춘다 */
    private static final long AB_INITIAL_BALANCE = AB_CONCURRENCY * AB_UNIT_AMOUNT;

    @DynamicPropertySource
    static void poolProperties(DynamicPropertyRegistry registry) {
        // 커넥션 풀이 좁으면 DB 앞에서 요청이 줄을 서느라 동시성이 사라져 A/B 차이가 관측되지 않는다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private UnlockedPaymentProcessor unlockedProcessor;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DepositPersistencePort depositPersistencePort;

    @Autowired
    private DepositCommandPort depositCommandPort;

    @Autowired
    private DepositHistoryCommandPort depositHistoryCommandPort;

    @Autowired
    private DepositHistoryRecorder historyRecorder;

    @Test
    @DisplayName("잔액 충분 → 예치금 차감 + 결제 저장이 하나의 트랜잭션으로 처리된다")
    void process_sufficientBalance_deductsAndSavesAtomically() {
        // given: 예치금 계정 생성 + 충전
        Deposit deposit = new Deposit("user-it-1");
        deposit.charge(10000L);
        depositCommandPort.saveDeposit(deposit);

        PaymentConfirmRequest request = new PaymentConfirmRequest(
                "order-it-001", true, 5000L, null,
                List.of("prod-1", "prod-2")
        );

        // when
        Payment result = paymentService.process("user-it-1", request);

        // then: 결제 내역 검증
        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PAYMENT_COMPLETED);
        assertThat(result.getAmount()).isEqualTo(5000L);

        // 예치금 잔액 확인 (10000 - 5000 = 5000)
        Deposit updated = depositPersistencePort.getDepositByUserCode("user-it-1").orElseThrow();
        assertThat(updated.getBalance()).isEqualTo(5000L);

        // DB 에서 다시 조회해도 일관성 유지
        Payment saved = paymentRepository.findByOrderCode("order-it-001").orElseThrow();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("잔액 부족 → ServiceException(400) 발생, 예치금/결제 모두 롤백")
    void process_insufficientBalance_rollsBackEverything() {
        // given: 잔액이 1000원인 사용자
        Deposit deposit = new Deposit("user-it-2");
        deposit.charge(1000L);
        depositCommandPort.saveDeposit(deposit);

        PaymentConfirmRequest request = new PaymentConfirmRequest(
                "order-it-002", true, 5000L, null,
                List.of("prod-1")
        );

        // when & then
        // 400 으로 나가야 한다 — 잔액 부족은 서버 오류가 아니라 사용자 사정이다.
        // 호출자(commerce)가 4xx/5xx 로 "결제 거절"과 "결제 서비스 이상"을 구분하므로 상태 코드가 계약이다.
        assertThatThrownBy(() -> paymentService.process("user-it-2", request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("잔액이 부족")
                .extracting(e -> ((ServiceException) e).getErrorCode().getHttpStatus())
                .isEqualTo(400);

        // 예치금은 원래 금액 그대로 (롤백 확인)
        Deposit unchanged = depositPersistencePort.getDepositByUserCode("user-it-2").orElseThrow();
        assertThat(unchanged.getBalance()).isEqualTo(1000L);

        // 결제 내역도 저장되지 않음
        assertThat(paymentRepository.findByOrderCode("order-it-002")).isEmpty();
    }

    @Test
    @DisplayName("예치금 이력은 직접 호출(REQUIRES_NEW)과 process() 양쪽에서 저장된다")
    void process_historyRecorder_savesInSeparateTransaction() {
        // given
        Deposit deposit = new Deposit("user-it-3");
        deposit.charge(10000L);
        depositCommandPort.saveDeposit(deposit);

        // 1단계: DepositHistoryRecorder 단독 동작 확인
        boolean saved = historyRecorder.recordPaymentHistory(
                "user-it-3", deposit.getCode(), 3000L, 7000L);
        assertThat(saved).isTrue();

        // 저장 직후 바로 조회 가능 (REQUIRES_NEW 이므로 커밋 완료)
        // 주의: getDepositHistories 는 findByUserCode 로 조회하므로 userCode 를 넘겨야 함
        var histories = depositHistoryCommandPort.getDepositHistories(
                "user-it-3",
                new io.devground.payments.deposit.domain.pagination.PageQuery(1, 10,
                        new io.devground.payments.deposit.domain.pagination.SortSpec("createdAt", io.devground.payments.deposit.domain.pagination.SortSpec.Direction.DESC))
        );
        assertThat(histories.items()).isNotEmpty();

        // 2단계: process() 에서도 이력이 저장됨
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                "order-it-003", true, 2000L, null, List.of("prod-3")
        );
        Payment result = paymentService.process("user-it-3", request);
        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PAYMENT_COMPLETED);

        var afterProcess = depositHistoryCommandPort.getDepositHistories(
                "user-it-3",
                new io.devground.payments.deposit.domain.pagination.PageQuery(1, 10,
                        new io.devground.payments.deposit.domain.pagination.SortSpec("createdAt", io.devground.payments.deposit.domain.pagination.SortSpec.Direction.DESC))
        );
        assertThat(afterProcess.items()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("연속 결제 시 잔액이 누적 차감되고 잔액 부족 시 예외가 발생한다")
    void process_sequentialPayments_correctlyAccumulatesDeductions() {
        // given: 잔액 10000원
        Deposit deposit = new Deposit("user-it-4");
        deposit.charge(10000L);
        depositCommandPort.saveDeposit(deposit);

        // when: 순차적으로 3번 결제 (3000 + 3000 + 3000 = 9000)
        paymentService.process("user-it-4",
                new PaymentConfirmRequest("order-seq-1", true, 3000L, null, List.of("p1")));
        paymentService.process("user-it-4",
                new PaymentConfirmRequest("order-seq-2", true, 3000L, null, List.of("p2")));
        paymentService.process("user-it-4",
                new PaymentConfirmRequest("order-seq-3", true, 3000L, null, List.of("p3")));

        // then: 잔액 = 10000 - 9000 = 1000
        Deposit after3 = depositPersistencePort.getDepositByUserCode("user-it-4").orElseThrow();
        assertThat(after3.getBalance()).isEqualTo(1000L);

        // 4번째 결제(2000원)는 잔액 부족으로 실패
        assertThatThrownBy(() ->
                paymentService.process("user-it-4",
                        new PaymentConfirmRequest("order-seq-4", true, 2000L, null, List.of("p4")))
        ).isInstanceOf(ServiceException.class);

        // 잔액은 그대로 1000원
        Deposit afterFail = depositPersistencePort.getDepositByUserCode("user-it-4").orElseThrow();
        assertThat(afterFail.getBalance()).isEqualTo(1000L);
    }

    /**
     * 회귀 방지 테스트.
     *
     * <p>원래 이 테스트는 락이 <b>없던</b> 시절 "lost update 가 발생한다" 를 증명하려고 작성됐다.
     * {@code 6386ab0} 에서 {@code PESSIMISTIC_WRITE} 가 도입되면서 전제가 뒤집혔으므로,
     * 이제는 반대로 <b>정합성이 지켜지는지</b> 를 검증한다 (2026-08-26 전환).
     */
    @Test
    @DisplayName("동시 차감 시 비관적 락으로 잔액 정합성이 보장된다 (lost update 없음)")
    void process_concurrent_pessimisticLock_preventsLostUpdate() throws Exception {
        // given: 잔액 10000원
        Deposit deposit = new Deposit("user-it-5");
        deposit.charge(10000L);
        depositCommandPort.saveDeposit(deposit);

        int threadCount = 5;
        long amountPerRequest = 3000L;          // 5 * 3000 = 15000 (> 10000, 전부 성공하면 안 됨)
        long maxPossibleDeduction = 10000L;     // 잔액 이상은 차감 불가능

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        // 여러 스레드가 증가시키므로 원자적 카운터여야 한다 (int[] 증가는 경쟁 조건)
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when: 5개 스레드가 동시에 출발
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startLatch.await(); // 모든 스레드 동시 출발
                    PaymentConfirmRequest req = new PaymentConfirmRequest(
                            "order-lost-update-" + idx, true, amountPerRequest, null,
                            List.of("prod-" + idx)
                    );
                    paymentService.process("user-it-5", req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // then
        Deposit finalDeposit = depositPersistencePort.getDepositByUserCode("user-it-5").orElseThrow();
        long actualBalance = finalDeposit.getBalance();
        int succeeded = successCount.get();
        long expectedBalance = maxPossibleDeduction - ((long) succeeded * amountPerRequest);

        log.info("동시성 테스트 결과: success={}, fail={}, 잔액={}, 기대 잔액={}",
                succeeded, failCount.get(), actualBalance, expectedBalance);

        // 1) 차감액이 성공 건수와 정확히 일치해야 한다 (lost update 가 있으면 잔액이 더 크다)
        assertThat(actualBalance)
                .describedAs("Lost Update 발생! %d건 성공(%,d원씩 차감)했는데 잔액이 %d 다. 기대값 %d",
                        succeeded, amountPerRequest, actualBalance, expectedBalance)
                .isEqualTo(expectedBalance);

        // 2) 잔액을 초과해 인출되지 않아야 한다
        assertThat(actualBalance)
                .describedAs("잔액이 음수가 되었다 = 초과 인출")
                .isGreaterThanOrEqualTo(0L);

        // 3) 잔액 10,000 / 건당 3,000 이므로 최대 3건만 성공할 수 있다
        assertThat(succeeded)
                .describedAs("잔액 한도를 넘겨 성공한 요청이 있다")
                .isLessThanOrEqualTo(3);

        // 4) 결제 이력도 성공 건수만큼 남아야 한다 (P0-7 회귀 방지)
        var histories = depositHistoryCommandPort.getDepositHistories(
                "user-it-5",
                new io.devground.payments.deposit.domain.pagination.PageQuery(1, 50,
                        new io.devground.payments.deposit.domain.pagination.SortSpec("createdAt",
                                io.devground.payments.deposit.domain.pagination.SortSpec.Direction.DESC))
        );
        assertThat(histories.items())
                .describedAs("결제는 됐는데 이력이 누락되었다 (돈은 나갔는데 감사 기록이 없는 상태)")
                .hasSize(succeeded);
    }

    /**
     * 비관적 락의 효과를 <b>수치</b>로 남기기 위한 A/B 테스트.
     *
     * <p>동일한 시나리오(동시 요청 {@value #AB_CONCURRENCY} 건, 건당 {@value #AB_UNIT_AMOUNT} 원,
     * 초기 잔액 = 요청 수 x 단가)를 조회 방식만 바꿔 두 번 실행한다.
     *
     * <ul>
     *   <li><b>A. 락 없음</b> — {@code getDepositByUserCode()} 로 읽고 계산해서 쓰는 read-then-write.
     *       {@link UnlockedPaymentProcessor} 가 {@code PaymentServiceImpl.process()} 와
     *       <b>조회 한 줄만</b> 다르게, 같은 순서로 처리한다.</li>
     *   <li><b>B. 비관적 락</b> — 운영 코드 {@code PaymentServiceImpl.process()} 그대로.
     *       {@code getDepositByUserCodeForUpdate()} → {@code SELECT ... FOR UPDATE}.</li>
     * </ul>
     *
     * <p>lost update 건수 = (기대 차감액 - 실제 차감액) / 단가.
     * 덮어쓰기가 일어나면 실제로는 덜 빠져나가므로, 차이를 단가로 나누면 사라진 차감 건수가 된다.
     */
    @Test
    @DisplayName("A/B: 락 없이 동시 차감하면 lost update 가 발생하고, 비관적 락을 걸면 0건이 된다")
    void concurrentDeduction_withoutLock_vs_withPessimisticLock() throws Exception {
        RunResult withoutLock = runConcurrentPayments("A. 락 없음", "user-ab-nolock",
                (userCode, req) -> unlockedProcessor.process(userCode, req));

        RunResult withLock = runConcurrentPayments("B. 비관적 락", "user-ab-lock",
                (userCode, req) -> paymentService.process(userCode, req));

        log.info("{}{}{}", report(withoutLock), report(withLock),
                String.format("%n%n=> lost update: %d건 → %d건",
                        withoutLock.lostUpdates(), withLock.lostUpdates()));

        // A: 락이 없으면 덮어쓰기가 발생한다 (테스트의 전제 — 여기서 0 이면 부하가 부족한 것)
        assertThat(withoutLock.lostUpdates())
                .describedAs("락 없이도 lost update 가 0건이다 = 동시성이 충분히 걸리지 않았다")
                .isGreaterThan(0L);

        // B-1) 총 차감액 = 결제 건수 x 단가
        assertThat(withLock.actualDeducted())
                .describedAs("총 차감액 != 결제 건수 x 단가")
                .isEqualTo(withLock.success() * AB_UNIT_AMOUNT);
        assertThat(withLock.lostUpdates())
                .describedAs("비관적 락을 걸었는데도 lost update 가 발생했다")
                .isZero();

        // B-2) 잔액만큼은 전부 결제되고, 잔액은 정확히 소진된다
        assertThat(withLock.success())
                .describedAs("실패한 요청이 있다 (잔액은 전 건을 감당할 수 있는 금액이었다)")
                .isEqualTo(AB_CONCURRENCY);
        assertThat(withLock.finalBalance())
                .describedAs("잔액이 정확히 소진되지 않았다")
                .isZero();

        // B-3) 예치금 이력 건수 = 결제 건수 (이중 차감 0건, 이력 누락 0건)
        assertThat(withLock.historyCount())
                .describedAs("예치금 이력 건수 != 결제 성공 건수")
                .isEqualTo(withLock.success());
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface PaymentCall {
        Payment call(String userCode, PaymentConfirmRequest request);
    }

    private record RunResult(
            String label,
            int success,
            int fail,
            long expectedDeducted,
            long actualDeducted,
            long finalBalance,
            long lostUpdates,
            long historyCount
    ) {}

    private RunResult runConcurrentPayments(String label, String userCode, PaymentCall call) throws Exception {
        Deposit deposit = new Deposit(userCode);
        deposit.charge(AB_INITIAL_BALANCE);
        depositCommandPort.saveDeposit(deposit);

        ExecutorService pool = Executors.newFixedThreadPool(AB_CONCURRENCY);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(AB_CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        for (int i = 0; i < AB_CONCURRENCY; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGate.await(); // 모든 스레드 동시 출발
                    call.call(userCode, new PaymentConfirmRequest(
                            "order-" + userCode + "-" + idx, true, AB_UNIT_AMOUNT, null,
                            List.of("prod-" + idx)));
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    log.debug("[{}] 요청 실패: {}", label, e.toString());
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(finished).describedAs("[%s] 제한 시간 안에 끝나지 않았다", label).isTrue();

        long finalBalance = depositPersistencePort.getDepositByUserCode(userCode).orElseThrow().getBalance();
        long actualDeducted = AB_INITIAL_BALANCE - finalBalance;
        long expectedDeducted = (long) success.get() * AB_UNIT_AMOUNT;

        // 이력은 건수만 필요하므로 1건짜리 페이지의 totalItems 를 쓴다
        long historyCount = depositHistoryCommandPort.getDepositHistories(userCode,
                new PageQuery(1, 1, new SortSpec("createdAt", SortSpec.Direction.DESC))).totalItems();

        return new RunResult(label, success.get(), fail.get(), expectedDeducted, actualDeducted,
                finalBalance, (expectedDeducted - actualDeducted) / AB_UNIT_AMOUNT, historyCount);
    }

    private String report(RunResult r) {
        return String.format(
                "%n[%s] 동시요청=%d건, 단가=%,d원, 초기잔액=%,d원%n"
                        + "  성공=%d건 / 실패=%d건%n"
                        + "  기대 차감액=%,d원, 실제 차감액=%,d원, 최종 잔액=%,d원%n"
                        + "  lost update=%d건, 예치금 이력=%d건%n",
                r.label(), AB_CONCURRENCY, AB_UNIT_AMOUNT, AB_INITIAL_BALANCE,
                r.success(), r.fail(),
                r.expectedDeducted(), r.actualDeducted(), r.finalBalance(),
                r.lostUpdates(), r.historyCount());
    }

    // ------------------------------------------------- 락 없는 결제 경로 (비교군 A)

    @TestConfiguration
    static class UnlockedProcessorConfig {
        @Bean
        UnlockedPaymentProcessor unlockedPaymentProcessor(DepositPersistencePort depositPersistencePort,
                                                          DepositCommandPort depositCommandPort,
                                                          PaymentRepository paymentRepository,
                                                          DepositHistoryRecorder historyRecorder) {
            return new UnlockedPaymentProcessor(depositPersistencePort, depositCommandPort,
                    paymentRepository, historyRecorder);
        }
    }

    /**
     * {@code PaymentServiceImpl.process()} 의 락 없는 버전 (비교군 A).
     * 조회를 {@code getDepositByUserCode()} 로 하는 것 외에는 처리 순서가 같다.
     */
    @RequiredArgsConstructor
    static class UnlockedPaymentProcessor {

        private final DepositPersistencePort depositPersistencePort;
        private final DepositCommandPort depositCommandPort;
        private final PaymentRepository paymentRepository;
        private final DepositHistoryRecorder historyRecorder;

        @PersistenceContext
        private EntityManager entityManager;

        @Transactional
        public Payment process(String userCode, PaymentConfirmRequest request) {
            // 락 없는 조회 — 여기가 운영 코드와 다른 유일한 지점이다
            Deposit deposit = depositPersistencePort.getDepositByUserCode(userCode)
                    .orElseThrow(() -> new IllegalStateException("Deposit not found: " + userCode));

            if (deposit.getBalance() < request.amount()) {
                throw new IllegalStateException("예치금이 부족하여 결제를 진행할 수 없습니다.");
            }

            deposit.withdraw(request.amount());
            long balanceAfter = deposit.getBalance();
            depositCommandPort.saveDeposit(deposit);

            // 예치금 UPDATE 를 이력 INSERT 보다 먼저 확정시킨다.
            // 순서가 뒤집히면 FK 검사의 S 락끼리 물려 deadlock 으로 실패하는데,
            // 그건 lost update 와 다른 현상이라 측정에서 걷어낸다.
            entityManager.flush();

            Payment payment = Payment.builder()
                    .userCode(userCode)
                    .orderCode(request.orderCode())
                    .amount(request.amount())
                    .build();
            payment.setPaymentStatus(PaymentStatus.PAYMENT_COMPLETED);
            paymentRepository.save(payment);

            historyRecorder.recordPaymentHistoryInTx(userCode, deposit.getCode(),
                    request.amount(), balanceAfter);

            return payment;
        }
    }
}
