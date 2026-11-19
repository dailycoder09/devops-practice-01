package com.microservices.inventory.service;

import com.microservices.inventory.dto.request.InventoryRequest;
import com.microservices.inventory.dto.response.InventoryAuditLogResponse;
import com.microservices.inventory.dto.response.InventoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * Inventory Service Interface
 *
 * Defines business operations for Inventory management
 * Follows Interface Segregation Principle for clean architecture
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface InventoryService {

    /**
     * Create a new inventory record
     *
     * @param request Inventory creation request
     * @return Created inventory response
     * @throws com.microservices.inventory.exception.SkuNotFoundInCatalogException if the SKU does not exist in product-service's catalog
     * @throws com.microservices.inventory.exception.DuplicateInventoryException if an inventory record for the SKU already exists
     * @throws com.microservices.inventory.exception.ProductServiceUnavailableException if product-service cannot be reached
     */
    InventoryResponse createInventory(InventoryRequest request);

    /**
     * Get inventory record by ID
     *
     * @param id Inventory record ID
     * @return Inventory response
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the record is not found
     */
    InventoryResponse getById(Long id);

    /**
     * Get inventory record by SKU code
     *
     * @param skuCode Stock Keeping Unit code
     * @return Inventory response
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the record is not found
     */
    InventoryResponse getBySkuCode(String skuCode);

    /**
     * Get all inventory records with pagination
     *
     * @param pageable Pagination parameters
     * @return Page of inventory responses
     */
    Page<InventoryResponse> getAll(Pageable pageable);

    /**
     * Get the SKUs whose quantity on hand is at or below their reorder threshold
     *
     * @param pageable Pagination and sort parameters (the controller sorts by quantity, then SKU)
     * @return Page of inventory responses
     */
    Page<InventoryResponse> getLowStock(Pageable pageable);

    /**
     * Update an existing inventory record's quantity/threshold
     *
     * @param id Inventory record ID
     * @param request Inventory update request
     * @return Updated inventory response
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the record is not found
     */
    InventoryResponse update(Long id, InventoryRequest request);

    /**
     * Increment or decrement a SKU's quantity on hand
     *
     * @param skuCode Stock Keeping Unit code
     * @param delta Amount to add (or, if negative, subtract)
     * @return Updated inventory response
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the record is not found
     * @throws IllegalArgumentException if the resulting quantity would be negative
     */
    InventoryResponse adjustStock(String skuCode, int delta);

    /**
     * Delete an inventory record by ID
     *
     * @param id Inventory record ID
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the record is not found
     */
    void delete(Long id);

    /**
     * Get paginated audit history for a SKU, most recent first
     *
     * @param skuCode Stock Keeping Unit code
     * @param pageable Pagination parameters
     * @return Page of audit log responses
     */
    Page<InventoryAuditLogResponse> getAuditHistory(String skuCode, Pageable pageable);

    /**
     * Get quantity on hand for a batch of SKU codes in a single call
     *
     * @param skuCodes SKU codes to look up
     * @return map of skuCode to quantityOnHand, containing only the SKUs that were found
     */
    Map<String, Integer> getStockForSkus(List<String> skuCodes);

    /**
     * Reserve stock for one line of an order. Safe to repeat: the same order, SKU and quantity again
     * answers success and changes nothing.
     *
     * @param skuCode Stock Keeping Unit code
     * @param quantity units to take, 1 to 999
     * @param orderId order id from order-service, a positive number
     * @return the inventory record with the new quantity on hand
     * @throws IllegalArgumentException if the quantity or the order id is out of range
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the SKU has no inventory record
     * @throws com.microservices.inventory.exception.InsufficientStockException if fewer than {@code quantity} units are on hand
     * @throws com.microservices.inventory.exception.ReservationConflictException if this order and SKU were already released, or reserved with another quantity
     */
    InventoryResponse reserveStock(String skuCode, int quantity, long orderId);

    /**
     * Release the stock reserved for one line of an order. Safe to repeat: when nothing is reserved
     * (no row, or already released) nothing changes.
     *
     * @param skuCode Stock Keeping Unit code
     * @param orderId order id from order-service, a positive number
     * @return the inventory record with the current quantity on hand
     * @throws IllegalArgumentException if the order id is out of range
     * @throws com.microservices.inventory.exception.InventoryNotFoundException if the SKU has no inventory record
     */
    InventoryResponse releaseStock(String skuCode, long orderId);
}
