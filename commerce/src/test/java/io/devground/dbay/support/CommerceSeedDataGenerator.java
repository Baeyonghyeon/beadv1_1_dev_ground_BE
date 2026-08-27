package io.devground.dbay.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;

import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.infrastructure.adapter.out.persistence.OrderItemJpaRepository;
import io.devground.dbay.order.infrastructure.adapter.out.persistence.OrderJpaRepository;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import io.devground.dbay.order.infrastructure.model.persistence.OrderItemEntity;
import net.datafaker.Faker;

@DataJpaTest
@ActiveProfiles("seed")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "io.devground.dbay")
@EnableJpaRepositories(basePackages = "io.devground.dbay")
@Commit
class CommerceSeedDataGenerator {

	Logger log = Logger.getLogger("commerce-seed-data-generator");

	@Autowired
	private OrderJpaRepository orderJpaRepository;

	@Autowired
	private OrderItemJpaRepository orderItemJpaRepository;

	private static final Faker faker = new Faker(new Locale("ko"));

	@Test
	void generateSettlementTestData() {

		log.info("정산 배치 시드 데이터 생성 시작");
		log.info("totalOrders=10000, sellersCount=1000");

		int totalOrders = 10_000;
		int sellersCount = 1_000;
		int chunkSize = 500;
		LocalDateTime twoWeeksAgo = LocalDateTime.now().minusWeeks(2);

		List<String> sellerCodes = new ArrayList<>();
		for (int i = 0; i < sellersCount; i++) {
			sellerCodes.add("SELLER-" + i);
		}

		log.info("sellerCodes: " + sellerCodes.get(0) + " ~ " + sellerCodes.get(sellerCodes.size() - 1));

		List<OrderEntity> orderBatch = new ArrayList<>();

		for (int i = 0; i < totalOrders; i++) {
			String sellerCode = sellerCodes.get(i % sellersCount);
			String buyerCode = "BUYER-" + i;
			long productPrice = faker.number().numberBetween(1000L, 100000L);

			OrderEntity order = OrderEntity.builder()
				.orderCode("ORDER-" + UUID.randomUUID().toString().substring(0, 8))
				.userCode(buyerCode)
				.nickName(faker.name().fullName())
				.address(faker.address().fullAddress())
				.addressDetail(faker.address().secondaryAddress())
				.totalAmount(productPrice)
				.build();

			order.setOrderStatus(OrderStatus.DELIVERED);

			try {
				var updatedAtField = order.getClass().getSuperclass().getDeclaredField("updatedAt");
				updatedAtField.setAccessible(true);
				updatedAtField.set(order, twoWeeksAgo);
			} catch (Exception e) {
				throw new RuntimeException("updatedAt 필드 설정 실패", e);
			}

			OrderItemEntity item = OrderItemEntity.builder()
				.orderEntity(order)
				.productCode("PRODUCT-" + UUID.randomUUID().toString().substring(0, 8))
				.sellerCode(sellerCode)
				.productName(faker.commerce().productName())
				.productPrice(productPrice)
				.build();

			order.getOrderItems().add(item);

			orderJpaRepository.save(order);
			orderItemJpaRepository.save(item);

			orderBatch.add(order);

			if (orderBatch.size() >= chunkSize) {
				orderJpaRepository.flush();
				orderItemJpaRepository.flush();
				orderBatch.clear();
				log.info(String.format("%d / %d (%.1f%%)", i + 1, totalOrders,
					(i + 1) * 100.0 / totalOrders));
			}
		}

		orderJpaRepository.flush();
		orderItemJpaRepository.flush();

		log.info("완료! totalOrders=" + totalOrders + ", sellersCount=" + sellersCount);
		log.info("이제 payments 모듈에서 PaymentsSeedDataGenerator 를 실행하세요.");
	}
}
