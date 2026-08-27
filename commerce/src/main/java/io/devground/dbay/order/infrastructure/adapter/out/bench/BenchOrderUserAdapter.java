package io.devground.dbay.order.infrastructure.adapter.out.bench;

import io.devground.dbay.order.application.port.out.user.OrderUserPort;
import io.devground.dbay.order.application.vo.UserInfo;
import io.devground.dbay.order.domain.vo.UserCode;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * bench 프로파일 전용 사용자 조회 스텁 (P0-3).
 *
 * <p>부하 테스트의 관심사는 <b>결제 경로</b> 하나다.
 * 실제 {@code OrderUserFeignAdapter} 는 {@code user-service:18080} 을 호출하는데,
 * 그 서비스를 같이 띄우면 (1) 네트워크 왕복이 측정값에 섞이고
 * (2) t3.large 재현 자원 예산(2 vCPU)을 결제와 무관한 프로세스가 나눠 갖는다.
 *
 * <p>그래서 bench 에서는 <b>I/O 없이 고정값</b>을 돌려준다.
 * Arm 간 차이가 오롯이 "결제를 어떻게 처리하는가" 에서만 발생하도록 통제하기 위함이다.
 *
 * <p>{@code @Primary} 로 Feign 어댑터보다 우선 주입된다.
 *
 * @see io.devground.dbay.order.infrastructure.adapter.out.user.OrderUserFeignAdapter
 */
@Primary
@Profile("bench")
@Component
public class BenchOrderUserAdapter implements OrderUserPort {

	@Override
	public UserInfo getUserInfo(UserCode userCode) {
		// OrderEntity 는 nickname/address 가 비어 있으면 예외를 던지므로 반드시 채운다.
		// userCode 를 섞어 유저마다 다른 값이 되게 한다 (컬럼 길이를 실제와 비슷하게).
		String code = userCode.value();
		return new UserInfo(
			"bench-" + code,
			"서울시 벤치구 부하동 " + Math.abs(code.hashCode() % 500) + "번지",
			code + "호"
		);
	}
}
