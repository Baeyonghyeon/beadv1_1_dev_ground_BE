package io.devground.payments.support;

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

import io.devground.payments.deposit.infrastructure.adapter.out.persistence.DepositJpaRepository;
import io.devground.payments.deposit.infrastructure.model.persistence.DepositEntity;

/**
 * 정산 배치 성능 테스트용 판매자 Deposit 계좌 생성기 (payments 모듈).
 *
 * 실행 전제:
 * 1. Docker MySQL 실행 중
 * 2. commerce 모듈의 CommerceSeedDataGenerator 먼저 실행 완료
 *
 * sellerCode 를 CommerceSeedDataGenerator 와 동일하게 맞춰야 Step 2 가 정상 동작한다.
 */
@DataJpaTest
@ActiveProfiles("seed")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "io.devground.payments")
@EnableJpaRepositories(basePackages = "io.devground.payments")
@Commit
class PaymentsSeedDataGenerator {

	Logger log = Logger.getLogger("payments-seed-data-generator");

	@Autowired
	private DepositJpaRepository depositJpaRepository;

	// ⚠️ CommerceSeedDataGenerator 와 동일한 값으로 맞출 것
	private static final int SELLERS_COUNT = 1_000;

	@Test
	void generateSellerDeposits() {

		log.info("판매자 Deposit 계좌 생성 시작");
		log.info("sellersCount=" + SELLERS_COUNT);

		int created = 0;
		int skipped = 0;

		for (int i = 0; i < SELLERS_COUNT; i++) {
			String sellerCode = "SELLER-" + i;

			if (depositJpaRepository.findByUserCode(sellerCode).isPresent()) {
				skipped++;
				continue;
			}

			DepositEntity deposit = DepositEntity.of(
				"DEPOSIT-" + UUID.randomUUID().toString().substring(0, 8),
				sellerCode
			);
			depositJpaRepository.save(deposit);
			created++;

			if (created % 100 == 0) {
				depositJpaRepository.flush();
				log.info(String.format("%d / %d deposits created", created, SELLERS_COUNT));
			}
		}

		depositJpaRepository.flush();

		log.info("완료! created=" + created + ", skipped=" + skipped);
	}
}
