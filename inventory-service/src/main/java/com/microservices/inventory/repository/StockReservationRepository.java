package com.microservices.inventory.repository;

import com.microservices.inventory.model.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Stock Reservation Repository
 *
 * Provides data access operations for StockReservation entity
 *
 * @author Microservices Team
 * @version 1.0
 */
@Repository
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /**
     * Find the reservation of one line of one order
     *
     * @param orderId order id (from order-service)
     * @param skuCode Stock Keeping Unit code
     * @return Optional containing the reservation if there is one
     */
    Optional<StockReservation> findByOrderIdAndSkuCode(Long orderId, String skuCode);

    /**
     * Mark a RESERVED reservation as RELEASED, in one statement.
     *
     * The status check is part of the UPDATE, so when two releases of the same line arrive at the same
     * moment, only one of them changes the row. The caller puts the stock back only when this returns 1.
     *
     * @param orderId order id (from order-service)
     * @param skuCode Stock Keeping Unit code
     * @return number of rows changed: 1, or 0 when the line was not RESERVED (no row, or already RELEASED)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE stock_reservations SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP "
            + "WHERE order_id = :orderId AND sku_code = :skuCode AND status = 'RESERVED'",
            nativeQuery = true)
    int markReleased(@Param("orderId") Long orderId, @Param("skuCode") String skuCode);
}
