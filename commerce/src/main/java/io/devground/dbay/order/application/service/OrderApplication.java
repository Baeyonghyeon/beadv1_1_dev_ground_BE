package io.devground.dbay.order.application.service;

import io.devground.dbay.order.application.exception.ServiceError;
import io.devground.dbay.order.application.port.out.event.OrderPublishEventPort;
import io.devground.core.event.payment.PaymentFailReason;
import io.devground.dbay.order.application.port.out.payment.OrderDepositPort;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentAsyncPort;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentLookupPort;
import io.devground.dbay.order.application.port.out.payment.OrderPaymentPort;
import io.devground.dbay.order.application.port.out.payment.PaymentResult;
import io.devground.dbay.order.application.port.out.persistence.OrderPersistencePort;
import io.devground.dbay.order.application.port.out.postprocess.OrderPostProcessPort;
import io.devground.dbay.order.application.port.out.product.OrderProductPort;
import io.devground.dbay.order.application.port.out.user.OrderUserPort;
import io.devground.dbay.order.application.vo.ProductInfoSnapShot;
import io.devground.dbay.order.application.vo.ProductSnapShot;
import io.devground.dbay.order.domain.model.OrderItem;
import io.devground.dbay.order.domain.vo.Progress;
import io.devground.dbay.order.application.vo.OrderAcceptance;
import io.devground.dbay.order.application.vo.UserInfo;
import io.devground.dbay.order.domain.model.Order;
import io.devground.dbay.order.domain.port.in.OrderUseCase;
import io.devground.dbay.order.domain.vo.*;
import io.devground.dbay.order.domain.vo.pagination.PageDto;
import io.devground.dbay.order.domain.vo.pagination.PageQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderApplication implements OrderUseCase {

	private final OrderUserPort orderUserPort;
	private final OrderProductPort orderProductPort;
	private final OrderPersistencePort orderPersistencePort;
	private final OrderPublishEventPort orderPublishEventPort;
	/**
	 * 결제는 <b>항상 동기</b>다 (OpenFeign → payments).
	 * 예치금 차감은 정합성이 우선이라 하나의 트랜잭션으로 원자 처리한다.
	 * Kafka Saga 로 분산했던 예전 방식(AS-IS)은 제거했다 — 예치금은 결제와 같은 모듈이라
	 * 분리해서 얻을 결합도 이득이 없고, 응답≠완료와 보상 트랜잭션 복잡도만 남았다.
	 */
	private final OrderPaymentPort orderPaymentPort;

	/**
	 * 비동기 결제 어댑터 ({@code order.payment.strategy=kafka}).
	 *
	 * <p>동기 포트와 <b>같은 인터페이스로 묶지 않은 이유</b>는 {@link OrderPaymentAsyncPort} 주석 참조 —
	 * 반환할 결과가 없는 경로에 "성공" 처럼 읽히는 값을 돌려주게 하지 않기 위해서다.
	 */
	private final OrderPaymentAsyncPort orderPaymentAsyncPort;

	/** 회수 스케줄러가 "결제가 실제로 있었는가" 를 판정할 때만 쓴다. */
	private final OrderPaymentLookupPort orderPaymentLookupPort;

	/** 잔액 사전 확인용 — {@code pre-check-balance} 가 켜졌을 때만 호출된다. */
	private final OrderDepositPort orderDepositPort;

	private final OrderPostProcessPort syncOrderPostProcessAdapter;
	private final OrderPostProcessPort kafkaOrderPostProcessAdapter;

	/** 후속 처리 방식: sync(동기 DB 쓰기) | kafka(커맨드 발행 후 즉시 반환) */
	@Value("${order.postprocess.strategy:kafka}")
	private String postProcessStrategy;

	/**
	 * 결제 방식: feign(동기 호출, 응답 시점에 결제 확정) | kafka(커맨드 발행, 결과는 이벤트로).
	 *
	 * <p>후처리 전략과 동일하게 두 경로를 모두 남겨 같은 조건 A/B 측정이 가능하게 한다.
	 */
	@Value("${order.payment.strategy:feign}")
	private String paymentStrategy;

	/** 결제 확정을 기다리는 시한(초). 넘기면 회수 대상이 된다. */
	@Value("${order.payment.timeout-seconds:300}")
	private long paymentTimeoutSeconds;

	/** 한 번의 회수 주기에서 처리할 최대 건수 — 결제 경로와 커넥션을 다투지 않게 제한한다. */
	@Value("${order.payment.reconcile-batch-size:100}")
	private int reconcileBatchSize;

	/**
	 * 발행 전 잔액을 미리 확인할지 여부 (설계 §7).
	 *
	 * <p><b>기본값 false 다.</b> 켜면 접수 경로에 원격 호출이 하나 늘어난다 —
	 * 비동기화로 덜어낸 것을 일부 되돌리는 셈이라, 측정을 오염시키지 않도록 꺼둔다.
	 * "잔액 부족을 즉시 알려달라" 는 UX 요구가 분명할 때 켠다.
	 */
	@Value("${order.payment.pre-check-balance:false}")
	private boolean preCheckBalance;

	public OrderApplication(OrderUserPort orderUserPort,
	                        OrderProductPort orderProductPort,
	                        OrderPersistencePort orderPersistencePort,
	                        OrderPublishEventPort orderPublishEventPort,
	                        @Qualifier("feignPaymentAdapter") OrderPaymentPort orderPaymentPort,
	                        @Qualifier("kafkaPaymentAdapter") OrderPaymentAsyncPort orderPaymentAsyncPort,
	                        OrderPaymentLookupPort orderPaymentLookupPort,
	                        OrderDepositPort orderDepositPort,
	                        @Qualifier("syncOrderPostProcessAdapter") OrderPostProcessPort syncOrderPostProcessAdapter,
	                        @Qualifier("kafkaOrderPostProcessAdapter") OrderPostProcessPort kafkaOrderPostProcessAdapter) {
		this.orderUserPort = orderUserPort;
		this.orderProductPort = orderProductPort;
		this.orderPersistencePort = orderPersistencePort;
		this.orderPublishEventPort = orderPublishEventPort;
		this.orderPaymentPort = orderPaymentPort;
		this.orderPaymentAsyncPort = orderPaymentAsyncPort;
		this.orderPaymentLookupPort = orderPaymentLookupPort;
		this.orderDepositPort = orderDepositPort;
		this.syncOrderPostProcessAdapter = syncOrderPostProcessAdapter;
		this.kafkaOrderPostProcessAdapter = kafkaOrderPostProcessAdapter;
	}

	private boolean isAsyncPayment() {
		return "kafka".equals(paymentStrategy);
	}

	/**
	 * 잔액이 명백히 부족하면 즉시 거절한다 (설계 §7).
	 *
	 * <p><b>확정이 아니라 힌트다.</b> 락 없이 읽으므로 동시 결제 경합에서는 통과했다가
	 * 비동기로 실패할 수 있고, 그게 정상이다. 최종 판정은 {@code PaymentServiceImpl.process()} 의
	 * 비관적 락 구간이 한다 — 여기서 통과했다고 차감을 건너뛰거나 잔액을 예약하면 안 된다.
	 *
	 * <p>조회 자체가 실패하면 <b>주문을 막지 않는다.</b> 이건 어디까지나 UX 개선용 부가 확인이라,
	 * 여기서 예외를 올리면 payments 조회 장애가 주문 전체를 막는 결합이 새로 생긴다.
	 */
	private void rejectIfObviouslyInsufficient(String userCode, long amount) {
		if (!preCheckBalance) {
			return;
		}

		try {
			long balance = orderDepositPort.getBalance(userCode);

			if (balance < amount) {
				log.info("[payment:kafka] 잔액 사전 확인에서 거절: userCode={}, balance={}, amount={}",
					userCode, balance, amount);
				// ⚠️ 이 모듈의 ServiceError 가 아니라 core 의 ErrorCode 를 쓴다.
				//    GlobalExceptionHandler 가 core 의 ServiceException 만 처리하므로,
				//    order 응용 계층의 ServiceError 로 던지면 400 이 아니라 500 이 나간다.
				//    잔액 부족은 사용자 잘못이지 서버 장애가 아니므로 반드시 4xx 여야 한다.
				throw io.devground.core.model.vo.ErrorCode.INSUFFICIENT_BALANCE.throwServiceException();
			}
		} catch (io.devground.core.model.exception.ServiceException e) {
			throw e;
		} catch (Exception e) {
			log.warn("[payment:kafka] 잔액 사전 확인 실패 — 주문은 그대로 진행한다: userCode={}", userCode, e);
		}
	}

	private OrderPostProcessPort getPostProcessAdapter() {
		return "sync".equals(postProcessStrategy) ? syncOrderPostProcessAdapter : kafkaOrderPostProcessAdapter;
	}

	@Override
	@Transactional
	public OrderAcceptance createOrderByOne(UserCode userCode, ProductCode productCode) {
		if (productCode == null) {
			throw ServiceError.PRODUCT_NOT_FOUND.throwServiceException();
		}

		// 1. 먼저 유저 정보 가져오고
		UserInfo userInfo = getUserInfoOrThrow(userCode);

		// 2. 상품 정보 가져와
		ProductSnapShot product = orderProductPort.getProduct(userCode, productCode);

		if (product == null) {
			throw ServiceError.PRODUCT_NOT_FOUND.throwServiceException();
		}

		if (product.productStatus() == ProductStatus.SOLD) {
			throw ServiceError.SOLD_PRODUCT_CANNOT_PURCHASE.throwServiceException();
		}

		OrderProduct orderProduct = new OrderProduct(
				product.productCode(),
				product.sellerCode(),
				product.productName(),
				product.productPrice()
		);

		Order order = Order.createOne(userCode, orderProduct);

		// 3. 주문 생성 — 비동기 경로는 결제 미확정(PAYMENT_PENDING) 상태로 먼저 남긴다.
		//    이 순서가 정합성의 핵심이다. 동기 경로는 결제(별도 서비스 트랜잭션)가 커밋된 뒤
		//    commerce 만 롤백되면 "돈은 빠졌는데 주문이 없는" 복구 불가 상태가 된다.
		//    주문을 먼저 남기면 실패해도 PAYMENT_PENDING 이 남아 회수·보상이 가능하다.
		if (isAsyncPayment()) {
			// 잔액이 명백히 모자라면 여기서 끊는다 — 대기 화면까지 갔다가 실패하는 경험을 줄인다.
			rejectIfObviouslyInsufficient(userCode.value(), orderProduct.productPrice());
			order.awaitPayment();
		}

		orderPersistencePort.createSingleOrder(userInfo, order, orderProduct);

		// 4. 결제
		if (isAsyncPayment()) {
			// 커맨드만 예약하고 즉시 반환한다 (실제 발행은 커밋 후).
			// 결과는 PaymentResultConsumer 가 받아 후속 처리를 잇는다.
			orderPaymentAsyncPort.requestPayment(
				order.getUserCode().value(),
				order.getOrderCode().value(),
				orderProduct.productPrice(),
				List.of(productCode.value())
			);
			return OrderAcceptance.awaiting(order.getOrderCode().value());
		}

		PaymentResult paymentResult = orderPaymentPort.processPayment(
			order.getUserCode().value(),
			order.getOrderCode().value(),
			orderProduct.productPrice(),
			List.of(productCode.value())
		);

		// 5. 결제 결과에 따른 후속 처리
		return handlePaymentResult(paymentResult, order, List.of(productCode.value()));
	}

	@Override
	@Transactional
	public OrderAcceptance createOrderBySelected(UserCode userCode, List<ProductCode> productCodes) {

		UserInfo userInfo = getUserInfoOrThrow(userCode);

		if (productCodes == null || productCodes.isEmpty()) {
			throw ServiceError.PRODUCT_NOT_FOUND.throwServiceException();
		}

		if (productCodes.stream().anyMatch(Objects::isNull)) {
			throw ServiceError.PRODUCT_NOT_FOUND.throwServiceException();
		}

		List<ProductInfoSnapShot> selProducts = orderProductPort
				.getCartProducts(productCodes);

		if (selProducts == null || selProducts.isEmpty()) {
			throw ServiceError.PRODUCT_NOT_FOUND.throwServiceException();
		}

		List<OrderProduct> orderProducts = selProducts.stream()
				.map(sp -> new OrderProduct(
						sp.productCode().value(),
						sp.sellerCode(),
						sp.title(),
						sp.price()
				)).toList();

		Order order = Order.createSelected(userCode, orderProducts);

		long totalAmount = orderProducts.stream().mapToLong(OrderProduct::productPrice).sum();
		List<String> productCodeLists = orderProducts.stream().map(OrderProduct::productCode)
						.toList();

		// createOrderByOne 과 같은 이유로 비동기 경로는 PAYMENT_PENDING 으로 먼저 남긴다.
		if (isAsyncPayment()) {
			rejectIfObviouslyInsufficient(userCode.value(), totalAmount);
			order.awaitPayment();
		}

		orderPersistencePort.createSelectedOrder(userInfo, order, orderProducts);

		// 4. 결제
		if (isAsyncPayment()) {
			orderPaymentAsyncPort.requestPayment(
				order.getUserCode().value(),
				order.getOrderCode().value(),
				totalAmount,
				productCodeLists
			);
			return OrderAcceptance.awaiting(order.getOrderCode().value());
		}

		PaymentResult paymentResult = orderPaymentPort.processPayment(
			order.getUserCode().value(),
			order.getOrderCode().value(),
			totalAmount,
			productCodeLists
		);

		// 5. 결제 결과에 따른 후속 처리
		return handlePaymentResult(paymentResult, order, productCodeLists);
	}

	/**
	 * 결제 결과에 따라 후속 처리를 진행한다.
	 *
	 * <p>결제(예치금 차감 + 결제 저장)는 이 시점에 payments 모듈에서 이미 원자 처리가 끝났다.
	 * 여기서는 <b>다른 도메인</b>에 속한 후속 작업만 다룬다 — 주문 완료 / 장바구니 정리 / 상품 상태.
	 * 이 셋을 동기로 할지 Kafka 로 넘길지가 {@code order.postprocess.strategy} 다.
	 */
	private OrderAcceptance handlePaymentResult(PaymentResult result, Order order, List<String> productCodes) {
		String userCode = order.getUserCode().value();
		String orderCode = order.getOrderCode().value();

		switch (result.outcome()) {
			case SUCCESS -> {
				log.info("[postprocess:{}] 결제 성공: userCode={}, orderCode={}",
					postProcessStrategy, userCode, orderCode);

				getPostProcessAdapter().completeOrder(userCode, orderCode, productCodes);
				return OrderAcceptance.settled(orderCode);
			}

			// 잔액 부족 등 — 차감이 일어나지 않은 것이 확실하므로 취소해도 안전하다.
			case REJECTED -> {
				log.info("결제 거절, 주문 취소: userCode={}, orderCode={}, reason={}",
					userCode, orderCode, result.message());

				order.cancel();
				orderPersistencePort.cancel(new OrderCode(orderCode));
				return OrderAcceptance.rejected(orderCode, result.message());
			}

			// 결제 서비스에 닿지 못했다 — 요청이 가지 않았으므로 역시 취소해도 안전하다.
			case UNAVAILABLE -> {
				log.error("결제 서비스 연결 실패, 주문 취소: userCode={}, orderCode={}, reason={}",
					userCode, orderCode, result.message());

				order.cancel();
				orderPersistencePort.cancel(new OrderCode(orderCode));
				return OrderAcceptance.unavailable(orderCode, result.message());
			}

			// ⚠️ 결제 여부를 모른다 — **절대 취소하면 안 된다.**
			// payments 가 차감을 커밋한 뒤 응답만 유실됐을 수 있고, 그때 취소하면
			// "돈은 빠졌는데 주문은 CANCELLED" 가 된다(실측된 유령 주문 892건이 이 경로).
			// 회수 대상으로 남겨 스케줄러가 payments 에 물어본 뒤 판정하게 한다.
			case UNKNOWN -> {
				log.error("결제 결과 불명 — 회수 대상으로 남김: userCode={}, orderCode={}, reason={}",
					userCode, orderCode, result.message());

				orderPersistencePort.markAwaitingPayment(new OrderCode(orderCode));
				return OrderAcceptance.uncertain(orderCode, result.message());
			}
		}

		throw new IllegalStateException("처리되지 않은 결제 결과: " + result.outcome());
	}

	// ════════════════════════════════════════════════════════════════════════
	// 비동기 결제 경로 (order.payment.strategy=kafka)
	// ════════════════════════════════════════════════════════════════════════

	/**
	 * 결제 상태를 조회한다 — 클라이언트가 "결제 대기중 → 완료/실패" 를 폴링하는 지점.
	 *
	 * <p>본인 주문만 볼 수 있다. 주문 코드는 UUID 라 추측이 어렵지만, 그것만으로
	 * 접근 제어를 대신할 수는 없다.
	 *
	 * <p>조회는 인덱스 1행이고 조인을 하지 않는다. 폴링은 결제 1건당 여러 번 호출되므로
	 * 여기가 무거우면 비동기화로 덜어낸 부하를 폴링이 도로 만든다 (설계 §8).
	 */
	@Override
	@Transactional(readOnly = true)
	public OrderPaymentStatus getPaymentStatus(UserCode userCode, OrderCode orderCode) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		if (orderCode == null) {
			throw ServiceError.ORDER_NOT_FOUND.throwServiceException();
		}

		OrderPaymentStatus status = orderPersistencePort.getPaymentStatus(orderCode);

		if (!status.userCode().equals(userCode.value())) {
			// 남의 주문 존재 여부까지 알려주지 않으려고 권한 오류가 아니라 없음으로 응답한다.
			throw ServiceError.ORDER_NOT_FOUND.throwServiceException();
		}

		return status;
	}

	/**
	 * 결제 성공 이벤트를 반영한다 — {@code PaymentResultConsumer} 가 호출한다.
	 *
	 * <p><b>멱등하다.</b> 상태 전이가 {@code PAYMENT_PENDING → PAID} 조건부 UPDATE 라,
	 * 같은 이벤트를 두 번 받아도 두 번째는 0행을 갱신하고 후처리도 건너뛴다.
	 * Kafka 는 at-least-once 이므로 중복 소비는 예외가 아니라 정상 동작이다.
	 */
	@Override
	@Transactional
	public void applyPaymentSuccess(UserCode userCode, OrderCode orderCode, List<String> productCodes) {
		boolean transitioned = orderPersistencePort.markPaidIfAwaitingPayment(orderCode);

		if (!transitioned) {
			log.debug("[payment:kafka] 결제 성공 이벤트 무시(이미 전이됨): orderCode={}", orderCode.value());
			return;
		}

		log.info("[payment:kafka] 결제 성공 반영: userCode={}, orderCode={}", userCode.value(), orderCode.value());

		// 상태 전이가 실제로 일어났을 때만 후처리를 발행한다.
		// 그렇지 않으면 중복 이벤트마다 장바구니 정리·상품 상태 커맨드가 중복 발행된다.
		getPostProcessAdapter().completeOrder(userCode.value(), orderCode.value(), productCodes);
	}

	/**
	 * 결제 실패 이벤트를 반영한다 — {@code PaymentResultConsumer} 가 호출한다.
	 *
	 * <p>{@code INSUFFICIENT_BALANCE} / {@code DEPOSIT_NOT_FOUND} 는 차감이 일어나지 않았으므로
	 * 보상이 필요 없다. 주문만 {@code PAYMENT_FAILED} 로 접는다.
	 *
	 * <p>{@code INTERNAL_ERROR} 는 <b>차감됐을 수 있다.</b> 여기서 단정할 수 없으므로 상태만 접고,
	 * 실제 차감 여부 판정과 환불 보상은 회수 스케줄러에 맡긴다.
	 */
	@Override
	@Transactional
	public void applyPaymentFailure(UserCode userCode, OrderCode orderCode, PaymentFailReason reason, String message) {
		boolean transitioned = orderPersistencePort.markPaymentFailedIfAwaitingPayment(orderCode);

		if (!transitioned) {
			log.debug("[payment:kafka] 결제 실패 이벤트 무시(이미 전이됨): orderCode={}", orderCode.value());
			return;
		}

		log.warn("[payment:kafka] 결제 실패 반영: userCode={}, orderCode={}, reason={}, msg={}",
			userCode.value(), orderCode.value(), reason, message);
	}

	/**
	 * 결제 확정을 기다리다 시한을 넘긴 주문을 회수한다 — {@code PendingPaymentReconciler} 가 호출한다.
	 *
	 * <p>commerce 의 DB 만 봐서는 두 경우를 구분할 수 없다. 둘 다 {@code PAYMENT_PENDING} 이다.
	 * <ul>
	 *   <li><b>커맨드 유실</b> — 결제 자체가 시작되지 않음. 차감 없음 → 주문을 취소하면 끝난다.</li>
	 *   <li><b>결과 이벤트 유실</b> — 결제는 끝났는데 알림만 사라짐. <b>돈은 이미 나갔다</b>
	 *       → 취소하면 안 되고, 오히려 {@code PAID} 로 복구하고 후처리를 이어야 한다.</li>
	 * </ul>
	 * 그래서 payments 에 결제 기록이 있는지 물어본 뒤에 판정한다.
	 *
	 * <p><b>트랜잭션을 걸지 않는다.</b> 이 메서드는 원격 호출(Feign)을 건별로 수행하므로,
	 * 트랜잭션을 열면 커넥션을 쥔 채 네트워크를 기다리게 되어 결제 경로와 커넥션을 다툰다.
	 * 상태 전이는 각 포트 메서드가 자체 트랜잭션으로 처리한다 (설계 문서 §6.4).
	 *
	 * @return 회수한 건수 (복구 + 취소)
	 */
	@Override
	public int reconcileAwaitingPayments() {
		LocalDateTime cutoff = LocalDateTime.now().minusSeconds(paymentTimeoutSeconds);

		List<String> orderCodes = orderPersistencePort.findTimedOutAwaitingPayment(cutoff, reconcileBatchSize);

		if (orderCodes.isEmpty()) {
			return 0;
		}

		log.info("[payment:kafka] 결제 확정 지연 주문 {}건 회수 시작 (시한 {}초)", orderCodes.size(), paymentTimeoutSeconds);

		int handled = 0;

		for (String code : orderCodes) {
			try {
				handled += reconcileOne(new OrderCode(code)) ? 1 : 0;
			} catch (Exception e) {
				// 한 건의 실패가 배치 전체를 멈추게 하지 않는다. 다음 주기에 다시 잡힌다.
				log.error("[payment:kafka] 주문 회수 실패(다음 주기에 재시도): orderCode={}", code, e);
			}
		}

		return handled;
	}

	private boolean reconcileOne(OrderCode orderCode) {
		// 조회 실패는 예외로 올라온다 — "결제 없음" 으로 오판해 돈이 빠진 주문을 취소하면 안 되기 때문.
		var snapshot = orderPaymentLookupPort.findByOrderCode(orderCode.value());

		if (snapshot.isPresent() && snapshot.get().isCompleted()) {
			// 결과 이벤트 유실 — 돈은 나갔다. 취소가 아니라 복구해야 한다.
			boolean transitioned = orderPersistencePort.markPaidIfAwaitingPayment(orderCode);

			if (transitioned) {
				List<String> productCodes = orderPersistencePort.getProductCodes(orderCode);
				String userCode = orderPersistencePort.getOrder(orderCode).getUserCode().value();

				getPostProcessAdapter().completeOrder(userCode, orderCode.value(), productCodes);

				log.warn("[payment:kafka] 결과 이벤트 유실 복구 → PAID: orderCode={}", orderCode.value());
			}

			return transitioned;
		}

		// 결제 기록 없음 — 커맨드가 유실됐거나 아직 처리 전이다. 차감이 없으므로 취소해도 안전하다.
		boolean transitioned = orderPersistencePort.cancelIfAwaitingPayment(orderCode);

		if (transitioned) {
			log.warn("[payment:kafka] 결제 기록 없음 → CANCELLED: orderCode={}", orderCode.value());
		}

		return transitioned;
	}

	@Override
	@Transactional(readOnly = true)
	public PageDto<OrderDescription> getOrderLists(UserCode userCode, RoleType roleType, PageQuery pageQuery, OrderStatus orderStatus) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		PageDto<OrderDescription> orderPage = orderPersistencePort.getOrders(userCode, roleType, pageQuery, orderStatus);

		List<OrderDescription> orders = orderPage.items();

		if (orders.isEmpty()) {
			return new PageDto<>(
					pageQuery.page(),
					pageQuery.size(),
					0,
					0,
					List.of()
			);
		}

		List<String> orderCodes = orders.stream().map(OrderDescription::code).toList();

		List<OrderItemInfo> orderItems = orderPersistencePort.getOrderItems(orderCodes);

		Map<String, List<OrderItemInfo>> itemsByOrderId = orderItems.stream()
				.collect(Collectors.groupingBy(
						OrderItemInfo::code,
						Collectors.toList()
				));

		List<OrderDescription> orderDescriptions = orders.stream()
				.map(o -> new OrderDescription(
						o.code(),
						o.userCode(),
						o.createdAt(),
						o.updatedAt(),
						o.totalAmount(),
						o.orderStatus(),
						itemsByOrderId.getOrDefault(o.code(), Collections.emptyList())
				)).toList();

		return new PageDto<>(
				orderPage.currentPageNumber(),
				orderPage.pageSize(),
				orderPage.totalPages(),
				orderPage.totalItems(),
				orderDescriptions
		);
	}

	@Override
	@Transactional(readOnly = true)
	public OrderDetailDescription getOrderDetail(UserCode userCode, OrderCode orderCode) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		if (orderCode == null) {
			throw ServiceError.ORDER_NOT_FOUND.throwServiceException();
		}

		return orderPersistencePort.getOrderDetail(userCode, orderCode);
	}

	@Override
	@Transactional
	public void confirmOrder(UserCode userCode, OrderCode orderCode) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		if (orderCode == null) {
			throw ServiceError.ORDER_NOT_FOUND.throwServiceException();
		}

		Order order = orderPersistencePort.getOrder(orderCode);
		LocalDateTime updatedAt = orderPersistencePort.getUpdatedAtByOrder(orderCode);

		order.confirm(updatedAt);

		orderPersistencePort.confirm(orderCode);
	}

	@Override
	@Transactional
	public void cancelOrder(UserCode userCode, OrderCode orderCode) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		if (orderCode == null) {
			throw ServiceError.ORDER_NOT_FOUND.throwServiceException();
		}

		Order order = orderPersistencePort.getOrder(orderCode);

		order.cancel();

		orderPersistencePort.cancel(orderCode);

		long amount = order.getOrderItems().stream().mapToLong(OrderItem::getProductPrice).sum();

		orderPublishEventPort.publishRefundEvent(userCode, amount, orderCode);
	}

	@Override
	@Transactional
	public void paidOrder(UserCode userCode, OrderCode orderCode) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		if (orderCode == null) {
			throw ServiceError.ORDER_NOT_FOUND.throwServiceException();
		}

		Order order = orderPersistencePort.getOrder(orderCode);

		order.paid();

		orderPersistencePort.paid(orderCode);
	}

	@Override
	@Transactional(readOnly = true)
	public PageDto<UnsettledOrderItemResponse> getUnsettledOrderItems(PageQuery pageQuery) {
		if (pageQuery.page() < 0) {
			throw ServiceError.PAGE_MUST_BE_POSITIVE.throwServiceException();
		}

		if (pageQuery.size() < 0) {
			throw ServiceError.PAGE_SIZE_MUST_BE_POSITIVE.throwServiceException();
		}

		// 2주가 지난 모든 DELIVERED 주문을 정산 대상으로 가져온다
		LocalDateTime cutoff = LocalDateTime.now().minusWeeks(2);

		return orderPersistencePort.getUnsettledOrderItems(pageQuery, cutoff);
	}

	@Override
	@Transactional
	public Progress autoUpdateOrderStatus() {
		LocalDateTime now = LocalDateTime.now();

		LocalDateTime oneDayAgo = now.minusDays(1);
		LocalDateTime threeDaysAgo = now.minusDays(3);

		List<Long> toDelivery = orderPersistencePort.getPaidOrders(oneDayAgo);

		long paidOrder = toDelivery.size();
		long progress1 = 0;

		if (!toDelivery.isEmpty()) {
			progress1 = orderPersistencePort.changeStatusPaidToDelivery(toDelivery);
		}

		List<Long> toDelivered = orderPersistencePort.getDeliveryOrders(threeDaysAgo);

		long deliveryOrder = toDelivered.size();
		long progress2 = 0;

		if (!toDelivered.isEmpty()) {
			progress2 = orderPersistencePort.changeStatusDeliveryToDelivered(toDelivered);
		}

		return new Progress(
				paidOrder,
				progress1,
				deliveryOrder,
				progress2
		);
	}

	private UserInfo getUserInfoOrThrow(UserCode userCode) {
		if (userCode == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		UserInfo userInfo = orderUserPort.getUserInfo(userCode);

		if (userInfo == null) {
			throw ServiceError.USER_NOT_FOUNT.throwServiceException();
		}

		return userInfo;
	}
}
