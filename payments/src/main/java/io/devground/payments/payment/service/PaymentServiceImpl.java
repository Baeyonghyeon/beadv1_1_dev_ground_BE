package io.devground.payments.payment.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.devground.core.commands.deposit.ChargeDeposit;
import io.devground.core.model.vo.DepositHistoryType;
import io.devground.core.model.vo.ErrorCode;
import io.devground.payments.deposit.application.port.out.DepositCommandPort;
import io.devground.payments.deposit.application.port.out.DepositPersistencePort;
import io.devground.payments.deposit.application.exception.ServiceException;
import io.devground.payments.deposit.application.exception.vo.ServiceErrorCode;
import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.payment.event.PaymentCompletedEvent;
import io.devground.payments.payment.mapper.PaymentMapper;
import io.devground.payments.payment.model.dto.request.PaymentRequest;
import io.devground.payments.payment.model.dto.request.RefundRequest;
import io.devground.payments.payment.model.dto.request.TossRefundRequest;
import io.devground.payments.payment.model.dto.response.GetPaymentsResponse;
import io.devground.payments.payment.model.entity.Payment;
import io.devground.payments.payment.model.vo.PaymentConfirmRequest;
import io.devground.payments.payment.model.vo.PaymentStatus;
import io.devground.payments.payment.model.vo.PaymentType;
import io.devground.payments.payment.model.vo.TossPaymentsRequest;
import io.devground.payments.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

	private final ObjectMapper objectMapper;
	private final PaymentRepository paymentRepository;
	private final HttpClient httpClient;

	// 예치금 직접 접근 (FeignClient 대신 Port 직접 주입)
	private final DepositPersistencePort depositPersistencePort;
	private final DepositCommandPort depositCommandPort;

	// 예치금 이력은 락 구간 밖에서 저장한다 (FK 락 자기충돌 방지) — DepositHistoryRecorder 참조
	private final DepositHistoryRecorder historyRecorder;
	private final ApplicationEventPublisher eventPublisher;

	/** join(기본, 커넥션 1개) | after-commit(커넥션 2개, 이력 실패가 결제를 막지 않음) */
	@Value("${payments.history.strategy:join}")
	private String historyStrategy;

	// Toss 충전 용도로만 Kafka 유지
	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${deposits.command.topic.name}")
	private String depositsCommandTopic;

	@Value("${custom.toss.secretKey}")
	private String tossPaySecretKey;

	@Value("${custom.toss.confirm-url}")
	private String tossPayConfirmUrl;

	public PaymentServiceImpl(ObjectMapper objectMapper,
	                          PaymentRepository paymentRepository,
	                          HttpClient httpClient,
	                          DepositPersistencePort depositPersistencePort,
	                          DepositCommandPort depositCommandPort,
	                          DepositHistoryRecorder historyRecorder,
	                          ApplicationEventPublisher eventPublisher,
	                          KafkaTemplate<String, Object> kafkaTemplate) {
		this.objectMapper = objectMapper;
		this.paymentRepository = paymentRepository;
		this.httpClient = httpClient;
		this.depositPersistencePort = depositPersistencePort;
		this.depositCommandPort = depositCommandPort;
		this.historyRecorder = historyRecorder;
		this.eventPublisher = eventPublisher;
		this.kafkaTemplate = kafkaTemplate;
	}

	/**
	 * 주문 결제 처리 (핵심 트랜잭션).
	 * 예치금 차감 + 결제 저장을 하나의 {@code @Transactional} 로 원자 처리한다.
	 *
	 * <p>예치금 이력 저장은 {@code AFTER_COMMIT} 리스너로 커밋 이후에 실행된다.
	 * 락 구간 안에서 자식 테이블을 INSERT 하면 FK 검사의 S 락이 이 트랜잭션의 X 락에 막혀
	 * lock wait timeout 이 발생하기 때문이다. 자세한 내용은 {@link DepositHistoryRecorder} 참조.
	 */
	@Override
	@Transactional
	public Payment process(String userCode, PaymentConfirmRequest request) {
		// 1. Deposit 직접 조회 (비관적 락 - 동시 결제 시 잔액 초과 인출 방지)
		Deposit deposit = depositPersistencePort.getDepositByUserCodeForUpdate(userCode)
			.orElseThrow(() -> new ServiceException(ServiceErrorCode.DEPOSIT_NOT_FOUND));

		// 2. 잔액 검증 (실패 시 예외 → 전체 롤백)
		//
		// ⚠️ IllegalStateException 을 쓰면 GlobalExceptionHandler 의 fallback 에 걸려 **500** 이 나간다.
		//    잔액 부족은 서버 오류가 아니라 사용자 사정이므로 400 이어야 한다.
		//    호출자(commerce)도 이 구분에 의존한다 — 400 이면 "결제 거절"(주문 취소 안전),
		//    5xx 면 "결제 서비스 이상"(취소해도 되는지 불확실)로 다르게 처리한다.
		if (deposit.getBalance() < request.amount()) {
			throw new ServiceException(ServiceErrorCode.INSUFFICIENT_BALANCE);
		}

		// 3. 예치금 차감
		deposit.withdraw(request.amount());

		// 4. 예치금 저장
		long balanceAfter = deposit.getBalance();
		depositCommandPort.saveDeposit(deposit);

		// 5. 결제 내역 저장
		Payment payment = Payment.builder()
			.userCode(userCode)
			.orderCode(request.orderCode())
			.amount(request.amount())
			.build();
		payment.setPaymentStatus(PaymentStatus.PAYMENT_COMPLETED);
		paymentRepository.save(payment);

		// 6. 예치금 이력은 커밋 이후에 저장한다 (AFTER_COMMIT 리스너).
		//    이 트랜잭션이 DepositEntity 행에 X 락을 쥐고 있는 동안 자식 테이블을 INSERT 하면
		//    FK 검사의 S 락이 자기 자신에게 막혀 lock wait timeout(50s) 이 발생한다.
		//    @see DepositHistoryRecorder
		if ("after-commit".equals(historyStrategy)) {
			eventPublisher.publishEvent(new PaymentCompletedEvent(
				userCode, deposit.getCode(), request.amount(), balanceAfter, request.orderCode()));
		} else {
			// join(기본): 같은 트랜잭션이라 FK 검사가 자기 X 락에 막히지 않고, 커넥션도 1개만 쓴다
			historyRecorder.recordPaymentHistoryInTx(userCode, deposit.getCode(), request.amount(), balanceAfter);
		}

		log.info("결제 완료 (원자 처리): userCode={}, orderCode={}, amount={}, balanceAfter={}",
			userCode, request.orderCode(), request.amount(), balanceAfter);

		return payment;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Payment> findByOrderCode(String orderCode) {
		return paymentRepository.findByOrderCode(orderCode);
	}

	@Override
	@Transactional
	public Payment pay(PaymentRequest request) {

		Payment payment;

		if (request.getPaymentType() == PaymentType.DEPOSIT) {
			payment = handleDepositPayment(request.getUserCode(), request.getOrderCode(), request.getAmount());
		} else if (request.getPaymentType() == PaymentType.TOSS_PAYMENT) {
			payment = handleTossPayment(request.getUserCode(), request.getOrderCode(), request.getPaymentKey(),
				request.getAmount());
		} else {
			throw new UnsupportedOperationException("결제 형식이 잘못되었습니다.");
		}

		return payment;

	}

	private Payment handleDepositPayment(String userCode, String orderCode, Long amount) {

		Payment payment = Payment.builder()
			.userCode(userCode)
			.orderCode(orderCode)
			.amount(amount)
			.build();

		payment.setPaymentStatus(PaymentStatus.PAYMENT_COMPLETED);

		return paymentRepository.save(payment);
	}

	private Payment handleTossPayment(String userCode, String orderCode, String paymentKey, Long amount) {

		// 토스페이먼츠 결제 시도
		boolean result = processTossPayment(new TossPaymentsRequest(paymentKey, orderCode, amount.toString()));

		if (!result)
			throw new IllegalStateException("토스페이먼츠 결제에 실패하였습니다.");

		// 결제 성공시 예치금 충전 처리 (카프카 전송 - 외부 결제이므로 비동기 유지)
		log.info("toss 충전 userCode: {}", userCode);
		ChargeDeposit command = new ChargeDeposit(
			userCode,
			paymentKey,
			amount,
			DepositHistoryType.CHARGE_TOSS
		);

		kafkaTemplate.send(depositsCommandTopic, command);
		log.info("예치금 충전 카프카 커맨드 전송");

		// 결제 내역 저장
		Payment payment = Payment.builder()
			.userCode(userCode)
			.orderCode(orderCode)
			.amount(amount)
			.paymentKey(paymentKey)
			.build();

		payment.setPaymentStatus(PaymentStatus.PAYMENT_PENDING);
		log.info("toss 충전 userCode: {}", userCode);

		return paymentRepository.save(payment);

	}

	private boolean processTossPayment(TossPaymentsRequest request) {

		try {
			// 1. Authorization Header 생성
			String target = tossPaySecretKey + ":";

			Base64.Encoder encoder = Base64.getEncoder();
			String encryptedSecretKey = "Basic " + encoder.encodeToString(target.getBytes(StandardCharsets.UTF_8));
			// 2. 요청 데이터 구성
			Map<String, Object> requestMap = objectMapper.convertValue(request, new TypeReference<>() {
			});

			HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(tossPayConfirmUrl))
				.header("Authorization", encryptedSecretKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(requestMap)))
				.build();

			// 4. HTTP 요청 수행
			HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

			// 5. 응답 처리
			if (response.statusCode() == HttpStatus.OK.value()) {
				log.info("결제 성공");
				return true;
			} else {
				log.error("토스페이먼츠 결제 수행 과정에서 오류가 발생하였습니다. 다시 시도하여 주시기 바랍니다. 응답코드 : {}", response.statusCode());
				log.info("response.body() = {}", response.body());
				return false;
			}
		} catch (Exception e) {
			log.error("토스페이먼츠 결제 수행 과정에서 오류가 발생하였습니다.");
			return false;
		}

	}

	@Override
	@Transactional
	public void refund(RefundRequest request) {
		PaymentStatus status = PaymentStatus.PAYMENT_REFUNDED;

		Payment payment = Payment.builder()
			.userCode(request.userCode())
			.orderCode(request.orderCode())
			.amount(request.amount())
			.build();

		payment.setPaymentStatus(status);

		paymentRepository.save(payment);
	}

	@Override
	@Transactional
	public void tossRefund(TossRefundRequest request){
		try {
			String target = tossPaySecretKey + ":";

			Base64.Encoder encoder = Base64.getEncoder();
			String encryptedSecretKey = "Basic " + encoder.encodeToString(target.getBytes(StandardCharsets.UTF_8));
			String url = String.format("https://api.tosspayments.com/v1/payments/%s/cancel", request.paymentKey());


			HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Authorization", encryptedSecretKey)
				.header("Content-Type", "application/json")
				.method("POST", HttpRequest.BodyPublishers.ofString("{\"cancelReason\":\"예치금 충전 불가\"}"))
				.build();

			HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
			System.out.println(response.body());

			Payment payment = paymentRepository.findByPaymentKey(request.paymentKey())
				.orElseThrow(ErrorCode.PAYMENT_NOT_FOUND::throwServiceException);

			payment.setPaymentStatus(PaymentStatus.PAYMENT_REFUNDED);

			if (response.statusCode() == HttpStatus.OK.value()) {
				log.info("환불 성공");
			} else {
				log.error("토스페이먼츠 환불 수행 과정에서 오류가 발생하였습니다. 다시 시도하여 주시기 바랍니다. 응답코드 : {}", response.statusCode());
				log.info("response.body() = {}", response.body());
			}
		} catch (Exception e) {
			log.error("토스페이먼츠 환불 수행 과정에서 오류가 발생하였습니다.");
		}
	}

	@Override
	@Transactional
	public Payment confirmPayment(PaymentRequest request) {
		throw new UnsupportedOperationException("");
	}

	@Override
	public Page<GetPaymentsResponse> getPayments(String userCode, Pageable pageable) {
		Page<Payment> paymentPage = pagePaymentsByUserCode(userCode, pageable);

		List<Payment> payments = paymentPage.getContent();

		if(payments.isEmpty()){
			return Page.empty(pageable);
		}

		List<GetPaymentsResponse> content = payments.stream()
			.map(p -> new GetPaymentsResponse(
				p.getCode(),
				p.getCreatedAt(),
				p.getAmount(),
				p.getPaymentType(),
				p.getPaymentStatus()
			))
			.toList();

		return new PageImpl<>(content, pageable, paymentPage.getTotalElements());
	}

	@Override
	public void applyDepositPayment(String orderCode) {
		Payment payment = getByOrderCode(orderCode);
		payment.setPaymentStatus(PaymentStatus.PAYMENT_COMPLETED);
	}

	@Override
	@Transactional
	public void cancelDepositPayment(String orderCode) {
		Payment payment = getByOrderCode(orderCode);
		payment.setPaymentStatus(PaymentStatus.PAYMENT_CANCELLED);
	}

	@Override
	@Transactional
	public void applyDepositCharge(String userCode){
		log.info("예치금 충전 완료 처리");
		Payment payment = getByUserCode(userCode);
		payment.setPaymentStatus(PaymentStatus.PAYMENT_COMPLETED);
	}

	private Payment getByOrderCode(String orderCode) {
		return paymentRepository.findByOrderCode(orderCode)
			.orElseThrow(ErrorCode.PAYMENT_NOT_FOUND::throwServiceException);
	}

	private Payment getByUserCode(String userCode) {
		return paymentRepository.findByUserCodeAndPaymentStatus(userCode, PaymentStatus.PAYMENT_PENDING)
			.orElseThrow(ErrorCode.PAYMENT_NOT_FOUND::throwServiceException);
	}

	private Page<Payment> pagePaymentsByUserCode(String userCode, Pageable pageable) {
		return paymentRepository.findByUserCodeOrderByPaidAtDesc(userCode, pageable);
	}

}
