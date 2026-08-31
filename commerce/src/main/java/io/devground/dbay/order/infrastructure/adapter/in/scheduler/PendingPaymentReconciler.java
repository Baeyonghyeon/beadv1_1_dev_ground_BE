package io.devground.dbay.order.infrastructure.adapter.in.scheduler;

import io.devground.dbay.order.domain.port.in.OrderUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 확정을 기다리다 시한을 넘긴 주문을 회수하는 스케줄러.
 *
 * <p><b>왜 필요한가:</b> 비동기 결제는 두 지점에서 메시지가 유실될 수 있다 —
 * 커밋 후 결제 커맨드 발행 실패, 그리고 결과 이벤트 유실이다.
 * 어느 쪽이든 주문이 {@code PAYMENT_PENDING} 에 갇힌다.
 * 이 스케줄러가 없으면 그 주문은 <b>영원히 그 상태로 남는다</b> — 특히 결과 이벤트가 유실된 경우
 * 돈은 이미 빠져나간 상태라 방치하면 안 된다.
 *
 * <p>비활성화하려면 {@code order.payment.reconcile-cron: "-"} 로 둔다
 * (부하 테스트 중에는 회수가 측정을 오염시키므로 bench 프로파일에서 끈다).
 *
 * @see io.devground.dbay.order.application.service.OrderApplication#reconcileAwaitingPayments()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingPaymentReconciler {

	private final OrderUseCase orderUseCase;

	@Scheduled(cron = "${order.payment.reconcile-cron:0 */5 * * * *}")
	public void reconcile() {
		int handled = orderUseCase.reconcileAwaitingPayments();

		if (handled > 0) {
			log.info("[payment:kafka] 결제 지연 주문 회수 완료: {}건", handled);
		}
	}
}
