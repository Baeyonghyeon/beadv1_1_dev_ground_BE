package io.devground.dbay.order.infrastructure.adapter.out.bench;

import io.devground.dbay.order.application.port.out.product.OrderProductPort;
import io.devground.dbay.order.application.vo.ProductInfoSnapShot;
import io.devground.dbay.order.application.vo.ProductSnapShot;
import io.devground.dbay.order.domain.vo.ProductCode;
import io.devground.dbay.order.domain.vo.ProductStatus;
import io.devground.dbay.order.domain.vo.UserCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * bench 프로파일 전용 상품 조회 스텁 (P0-3).
 *
 * <p>어떤 productCode 를 주더라도 항상 <b>판매중(ON_SALE)</b> 인 고정 가격 상품을 돌려준다.
 * 부하 테스트에서 "상품이 없어서" 또는 "이미 팔려서" 실패하는 경우를 없애,
 * 실패가 발생하면 그것이 <b>결제 경로의 문제</b> 임을 보장하기 위함이다.
 *
 * <p>가격을 고정하는 이유: 설계 문서 §3.2 의 변수 통제.
 * 금액이 매번 달라지면 잔액 소진 시점이 Arm 마다 달라져 비교가 성립하지 않는다.
 *
 * <p>판매자 코드는 productCode 로부터 결정론적으로 만든다.
 * 전부 같은 판매자면 정산 단계에서 한 행에 경합이 몰려 실제와 달라지기 때문이다.
 *
 * @see io.devground.dbay.order.infrastructure.adapter.out.product.OrderProductFeignAdapter
 */
@Primary
@Profile("bench")
@Component
public class BenchOrderProductAdapter implements OrderProductPort {

	/** 상품 단가 (설계 문서 §3.2: 5,000원 고정) */
	@Value("${bench.product.price:5000}")
	private long price;

	/** 판매자 풀 크기 — 판매자 행에 경합이 몰리지 않도록 분산한다 */
	@Value("${bench.product.seller-pool:100}")
	private int sellerPool;

	@Override
	public ProductSnapShot getProduct(UserCode userCode, ProductCode productCode) {
		String code = productCode.value();
		return new ProductSnapShot(
			code,
			sellerCodeOf(code),
			"bench-product-" + code,
			price,
			ProductStatus.ON_SALE
		);
	}

	@Override
	public List<ProductInfoSnapShot> getCartProducts(List<ProductCode> productCodes) {
		if (productCodes == null || productCodes.isEmpty()) {
			return List.of();
		}

		return productCodes.stream()
			.map(pc -> {
				String code = pc.value();
				return new ProductInfoSnapShot(
					pc,
					"sale-" + code,
					sellerCodeOf(code),
					"bench-product-" + code,
					"https://bench.local/thumb/" + code + ".jpg",
					price,
					"부하 테스트용 상품",
					"bench-category"
				);
			})
			.toList();
	}

	/** 같은 productCode 는 항상 같은 판매자 → 재실행 간 조건이 동일하게 유지된다 */
	private String sellerCodeOf(String productCode) {
		return "BENCH-SELLER-" + Math.floorMod(productCode.hashCode(), sellerPool);
	}
}
