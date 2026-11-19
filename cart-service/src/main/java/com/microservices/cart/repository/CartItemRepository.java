package com.microservices.cart.repository;

import com.microservices.cart.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Cart Item Repository
 *
 * Every method works on the items of ONE user, so a user can never reach the cart of another user.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /**
     * All items of a user, in the order they were added
     */
    List<CartItem> findByUserIdOrderByIdAsc(long userId);

    /**
     * One item of a user
     */
    Optional<CartItem> findByUserIdAndSkuCode(long userId, String skuCode);

    /**
     * Remove one item of a user
     *
     * @return how many rows were removed (0 or 1)
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CartItem c WHERE c.userId = :userId AND c.skuCode = :skuCode")
    int deleteByUserIdAndSkuCode(@Param("userId") long userId, @Param("skuCode") String skuCode);

    /**
     * Remove all items of a user
     *
     * @return how many rows were removed
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CartItem c WHERE c.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
