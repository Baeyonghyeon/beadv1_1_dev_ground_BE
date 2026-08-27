package io.devground.payments.settlement.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.devground.core.model.web.BaseResponse;
import io.devground.core.model.web.PageDto;
import io.devground.payments.settlement.model.dto.UnsettledOrderItemResponse;

@FeignClient(
	name = "SettlementToOrder",
	url = "${external.openfeign-url}",
	path = "/api/commerce/order"
)
public interface OrderFeignClient {

	/**
	 * 정산되지 않은 확정 주문 항목 조회 (페이징)
	 * @param page 페이지 번호 (0부터 시작)
	 * @param size 페이지 크기
	 * @return 정산 대상 OrderItem 페이징 목록
	 */
	@GetMapping("/unsettled-items")
	BaseResponse<PageDto<UnsettledOrderItemResponse>> getUnsettledOrderItems(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "1000") int size
	);
}
