package io.devground.payments.settlement.batch.reader;

import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import io.devground.payments.settlement.model.entity.Settlement;
import io.devground.payments.settlement.model.entity.vo.SettlementStatus;
import io.devground.payments.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SETTLEMENT_CREATED 상태의 정산을 읽어서 입금 처리를 위한 Reader
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class SettlementDepositReader implements ItemReader<Settlement> {

	private final SettlementRepository settlementRepository;

	private List<Settlement> settlements;
	private int currentIndex = 0;

	/**
	 * SETTLEMENT_CREATED 상태의 정산을 하나씩 읽어옴
	 */
	@Override
	public Settlement read() {
		// 첫 실행이거나 모든 아이템을 읽은 경우
		if (settlements == null) {
			fetchSettlements();
			currentIndex = 0;
		}

		// 더 이상 읽을 데이터가 없으면 null 반환
		if (currentIndex >= settlements.size()) {
			log.info("입금 대상 Settlement 읽기 완료: 총 {} 건", settlements.size());
			return null;
		}

		Settlement settlement = settlements.get(currentIndex++);
		log.info("Settlement 읽음: settlementCode={}, sellerCode={}, settlementBalance={}",
			settlement.getCode(), settlement.getSellerCode(), settlement.getSettlementBalance());

		return settlement;
	}

	/**
	 * SETTLEMENT_CREATED 상태의 정산을 조회
	 */
	private void fetchSettlements() {
		log.info("입금 대상 Settlement 조회 중...");

		settlements = settlementRepository.findBySettlementStatus(
			SettlementStatus.SETTLEMENT_CREATED,
			PageRequest.of(0, 1000, Sort.by("createdAt").ascending())
		).getContent();

		log.info("입금 대상 Settlement {} 건 조회됨", settlements.size());
	}

}
