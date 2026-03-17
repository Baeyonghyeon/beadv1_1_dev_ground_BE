package io.devground.payments.settlement.batch.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import io.devground.core.model.web.PageDto;

import io.devground.payments.settlement.client.OrderFeignClient;
import io.devground.payments.settlement.model.dto.UnsettledOrderItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class UnsettledOrderItemReader implements ItemReader<UnsettledOrderItemResponse> {

	private final OrderFeignClient orderFeignClient;

	private List<UnsettledOrderItemResponse> unsettledItems;
	private int currentIndex = 0;
	private int currentPage = 0;
	private static final int PAGE_SIZE = 100;

	/**
	 * 배치 작업에서 한 번에 하나의 아이템을 읽어옴
	 * 페이징 방식으로 Order 도메인에서 데이터를 가져와서 처리
	 */
	@Override
	public UnsettledOrderItemResponse read() {
		// 첫 실행이거나 현재 페이지의 모든 아이템을 읽은 경우
		if (Objects.isNull(unsettledItems) || currentIndex >= unsettledItems.size()) {
			fetchNextPage();

			// 더 이상 읽을 데이터가 없으면 null 반환 (배치 종료)
			if (unsettledItems.isEmpty()) {
				log.info("정산 대상 OrderItem 읽기 완료. 총 {} 페이지 처리됨", currentPage);

				return null;
			}

			currentIndex = 0;
		}

		UnsettledOrderItemResponse item = unsettledItems.get(currentIndex++);
		log.info("OrderItem 읽음: orderItemCode={}, page={}, index={}",
			item.orderItemCode(), currentPage, currentIndex - 1);

		return item;
	}

	/**
	 * Order 도메인에서 다음 페이지의 정산 대상 OrderItem을 가져옴
	 * PageDto를 사용하여 페이징 정보 활용
	 */
	private void fetchNextPage() {
		try {
			log.info("Order 도메인에서 정산 대상 OrderItem 조회 중... page={}, size={}", currentPage, PAGE_SIZE);

			PageDto<UnsettledOrderItemResponse> pageDto =
				orderFeignClient.getUnsettledOrderItems(currentPage, PAGE_SIZE)
					.throwIfNotSuccess()
					.data();

			if (pageDto == null || pageDto.items().isEmpty()) {
				// 더 이상 조회할 데이터가 없음
				unsettledItems = new ArrayList<>();
				log.info("정산 대상 OrderItem이 더 이상 없습니다 (page={})", currentPage);
				return;
			}

			unsettledItems = pageDto.items();

			log.info("정산 대상 OrderItem {} 건 조회됨 (page={}/{}, totalItems={})",
				unsettledItems.size(), pageDto.currentPageNumber(), pageDto.totalPages(),
				pageDto.totalItems());

			// PageDto의 currentPageNumber를 사용하여 다음 페이지 설정
			// PageDto.currentPageNumber는 1부터 시작하므로, 다음 페이지는 currentPageNumber
			currentPage = pageDto.currentPageNumber();

		} catch (Exception e) {
			log.error("Order 도메인에서 정산 대상 OrderItem 조회 실패: page={}", currentPage, e);
			unsettledItems = new ArrayList<>();
		}
	}

}
