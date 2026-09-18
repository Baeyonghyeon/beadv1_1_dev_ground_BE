package io.devground.dbay.cart.infrastructure.adapter.out.persistence;

import io.devground.dbay.cart.infrastructure.model.persistence.CartEntity;
import io.devground.dbay.cart.infrastructure.model.persistence.CartItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CartItemJpaRepository extends JpaRepository<CartItemEntity, Long> {

	boolean existsByCartEntityAndProductCode(CartEntity cartEntity, String productCode);

	long countByCartEntity(CartEntity cartEntity);

    @Modifying
    @Query("""
		DELETE FROM CartItemEntity ci
		WHERE ci.cartEntity = :cart
			AND ci.deleteStatus = 'N'
			AND ci.productCode IN :cartProductCodes
		""")
    void deleteCartItemEntityByProductCodes(@Param("cart") CartEntity cartEntity,
                                     @Param("cartProductCodes") List<String> cartProductCodes);

    @Modifying
    @Query("""
		DELETE FROM CartItemEntity ci
		WHERE ci.cartEntity = :cart
		""")
    void deleteCartItemEntityByCartCode(@Param("cart") CartEntity cartEntity);

//    List<CartItem> findByCart(Cart cart);
}
