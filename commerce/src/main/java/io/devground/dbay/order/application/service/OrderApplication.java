package io.devground.dbay.order.application.service;

import io.devground.dbay.order.application.exception.ServiceError;
import io.devground.dbay.order.application.port.out.event.OrderPublishEventPort;
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

	private final OrderPostProcessPort syncOrderPostProcessAdapter;
	private final OrderPostProcessPort kafkaOrderPostProcessAdapter;

	/** 후속 처리 방식: sync(동기 DB 쓰기) | kafka(커맨드 발행 후 즉시 반환) */
	@Value("${order.postprocess.strategy:kafka}")
	private String postProcessStrategy;

	public OrderApplication(OrderUserPort orderUserPort,
	                        OrderProductPort orderProductPort,
	                        OrderPersistencePort orderPersistencePort,
	                        OrderPublishEventPort orderPublishEventPort,
	                        @Qualifier("feignPaymentAdapter") OrderPaymentPort orderPaymentPort,
	                        @Qualifier("syncOrderPostProcessAdapter") OrderPostProcessPort syncOrderPostProcessAdapter,
	                        @Qualifier("kafkaOrderPostProcessAdapter") OrderPostProcessPort kafkaOrderPostProcessAdapter) {
		this.orderUserPort = orderUserPort;
		this.orderProductPort = orderProductPort;
		this.orderPersistencePort = orderPersistencePort;
		this.orderPublishEventPort = orderPublishEventPort;
		this.orderPaymentPort = orderPaymentPort;
		this.syncOrderPostProcessAdapter = syncOrderPostProcessAdapter;
		this.kafkaOrderPostProcessAdapter = kafkaOrderPostProcessAdapter;
	}

	private OrderPostProcessPort getPostProcessAdapter() {
		return "sync".equals(postProcessStrategy) ? syncOrderPostProcessAdapter : kafkaOrderPostProcessAdapter;
	}

	@Override
	@Transactional
	public void createOrderByOne(UserCode userCode, ProductCode productCode) {
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

		// 3. 주문 생성
		orderPersistencePort.createSingleOrder(userInfo, order, orderProduct);

		// 4. 결제 처리 (전략 패턴: feign or kafka)
		PaymentResult paymentResult = orderPaymentPort.processPayment(
			order.getUserCode().value(),
			order.getOrderCode().value(),
			orderProduct.productPrice(),
			List.of(productCode.value())
		);

		// 5. 결제 결과에 따른 후속 처리
		handlePaymentResult(paymentResult, order, List.of(productCode.value()));
	}

	@Override
	@Transactional
	public void createOrderBySelected(UserCode userCode, List<ProductCode> productCodes) {

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

		orderPersistencePort.createSelectedOrder(userInfo, order, orderProducts);

		// 4. 결제 처리 (전략 패턴: feign or kafka)
		PaymentResult paymentResult = orderPaymentPort.processPayment(
			order.getUserCode().value(),
			order.getOrderCode().value(),
			totalAmount,
			productCodeLists
		);

		// 5. 결제 결과에 따른 후속 처리
		handlePaymentResult(paymentResult, order, productCodeLists);
	}

	/**
	 * 결제 결과에 따라 후속 처리를 진행한다.
	 *
	 * <p>결제(예치금 차감 + 결제 저장)는 이 시점에 payments 모듈에서 이미 원자 처리가 끝났다.
	 * 여기서는 <b>다른 도메인</b>에 속한 후속 작업만 다룬다 — 주문 완료 / 장바구니 정리 / 상품 상태.
	 * 이 셋을 동기로 할지 Kafka 로 넘길지가 {@code order.postprocess.strategy} 다.
	 */
	private void handlePaymentResult(PaymentResult result, Order order, List<String> productCodes) {
		String userCode = order.getUserCode().value();
		String orderCode = order.getOrderCode().value();

		if (result.success()) {
			log.info("[postprocess:{}] 결제 성공: userCode={}, orderCode={}",
				postProcessStrategy, userCode, orderCode);

			getPostProcessAdapter().completeOrder(userCode, orderCode, productCodes);
			// TODO(향후): publishPaymentNotification(userCode, orderCode, totalAmount);
		} else {
			log.warn("결제 실패, 주문 취소: userCode={}, orderCode={}, reason={}",
				userCode, orderCode, result.message());

			order.cancel();
			orderPersistencePort.cancel(new OrderCode(orderCode));
			// TODO(향후): publishPaymentFailedNotification(userCode, orderCode);
		}
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
