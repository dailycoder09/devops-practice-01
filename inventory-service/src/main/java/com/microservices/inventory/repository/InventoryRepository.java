package com.microservices.inventory.repository;

import com.microservices.inventory.model.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Inventory Repository
 *
 * Provides data access operations for InventoryItem entity
 * Extends JpaRepository for CRUD operations
 *
 * @author Microservices Team
 * @version 1.0
 */
@Repository
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {

    /**
     * Find inventory record by SKU code
     *
     * @param skuCode Stock Keeping Unit code
     * @return Optional containing the inventory record if found
     */
    Optional<InventoryItem> findBySkuCode(String skuCode);

    /**
     * Check if an inventory record exists for the given SKU code
     *
     * @param skuCode Stock Keeping Unit code
     * @return true if an inventory record exists, false otherwise
     */
    boolean existsBySkuCode(String skuCode);

    /**
     * Find inventory records for a batch of SKU codes
     *
     * @param skuCodes SKU codes to look up
     * @return inventory records found for the given SKU codes (unknown SKUs are silently omitted)
     */
    List<InventoryItem> findBySkuCodeIn(List<String> skuCodes);

    /**
     * Find the records that are at or below their reorder threshold ("low stock", including out of stock)
     *
     * @param pageable page and sort (the sort is given by the caller)
     * @return one page of low-stock records
     */
    @Query(value = "SELECT i FROM InventoryItem i WHERE i.quantityOnHand <= i.reorderThreshold",
            countQuery = "SELECT COUNT(i) FROM InventoryItem i WHERE i.quantityOnHand <= i.reorderThreshold")
    Page<InventoryItem> findLowStock(Pageable pageable);

    /**
     * Take units from stock, but only if there are enough. One conditional UPDATE: the database checks
     * the quantity and changes it in the same statement, so two callers can never both take the last unit
     * (no read-then-write, no lock in the application).
     *
     * @param skuCode Stock Keeping Unit code
     * @param quantity units to take
     * @return number of rows changed: 1, or 0 when the SKU is unknown or has fewer than {@code quantity} units
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE inventory_items SET quantity_on_hand = quantity_on_hand - :quantity, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE sku_code = :skuCode AND quantity_on_hand >= :quantity",
            nativeQuery = true)
    int decreaseIfEnough(@Param("skuCode") String skuCode, @Param("quantity") int quantity);

    /**
     * Put units back into stock. One UPDATE that adds to the stored number, so it cannot lose a change
     * made at the same moment.
     *
     * @param skuCode Stock Keeping Unit code
     * @param quantity units to add back
     * @return number of rows changed: 1, or 0 when the SKU is unknown
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE inventory_items SET quantity_on_hand = quantity_on_hand + :quantity, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE sku_code = :skuCode",
            nativeQuery = true)
    int increase(@Param("skuCode") String skuCode, @Param("quantity") int quantity);
}
