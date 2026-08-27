package io.devground.dbay.order.infrastructure.adapter.in.scheduler;

import io.devground.dbay.order.application.service.OrderApplication;
import io.devground.dbay.order.domain.vo.Progress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderScheduler {

	private final OrderApplication orderApplication;

	// bench 프로파일에서 "-" 로 비활성화 (부하 테스트 중 주문 상태 자동 변경 방지)
	@Scheduled(cron = "${order.scheduler.auto-delivery-cron:0 0 3 * * *}")
	public void runAutoDeliveryUpdate() {
		Progress result = orderApplication.autoUpdateOrderStatus();
		log.info("배송 대상 주문: {}, 배송 변경된 주문: {}, 배송 완료 대상 주문: {}, 배송 완료 변경된 주문: {}",
				result.paidOrder(),
				result.paidToDelivery(),
				result.deliveryOrder(),
				result.deliveryToDelivered()
		);
	}
}
