package com.microservices.inventory.service.impl;

import com.microservices.inventory.client.ProductClient;
import com.microservices.inventory.dto.request.InventoryRequest;
import com.microservices.inventory.dto.response.InventoryAuditLogResponse;
import com.microservices.inventory.dto.response.InventoryResponse;
import com.microservices.inventory.exception.DuplicateInventoryException;
import com.microservices.inventory.exception.InsufficientStockException;
import com.microservices.inventory.exception.InventoryNotFoundException;
import com.microservices.inventory.exception.ReservationConflictException;
import com.microservices.inventory.exception.SkuNotFoundInCatalogException;
import com.microservices.inventory.logging.AuditLog;
import com.microservices.inventory.logging.LogSanitizer;
import com.microservices.inventory.mapper.InventoryMapper;
import com.microservices.inventory.model.ChangeType;
import com.microservices.inventory.model.InventoryAuditLog;
import com.microservices.inventory.model.InventoryItem;
import com.microservices.inventory.model.ReservationStatus;
import com.microservices.inventory.model.StockReservation;
import com.microservices.inventory.repository.InventoryAuditLogRepository;
import com.microservices.inventory.repository.InventoryRepository;
import com.microservices.inventory.repository.StockReservationRepository;
import com.microservices.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;

/**
 * Inventory Service Implementation
 *
 * Implements business logic for Inventory management
 * Uses @Transactional for database transaction management
 * Logging: details at DEBUG, and one audit event (logger "audit") for every change of stock: stock_adjusted,
 * inventory_created, inventory_updated, inventory_deleted, stock_reserved, stock_reserve_refused (WARN),
 * stock_released and stock_release_noop. Failures are not logged here: the exception goes to
 * GlobalExceptionHandler, which writes the one line. Values from callers (SKU) are cleaned before they go into a message.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryAuditLogRepository inventoryAuditLogRepository;
    private final InventoryMapper inventoryMapper;
    private final ProductClient productClient;
    private final StockReservationRepository stockReservationRepository;
    private final TransactionTemplate transactionTemplate;

    /** Most units one reserve may take (the contract: 1 to 999) */
    private static final int MAX_RESERVE_QUANTITY = 999;

    /**
     * Create a new inventory record
     * Validates the SKU exists in product-service's catalog and is not already tracked
     */
    @Override
    @Transactional
    public InventoryResponse createInventory(InventoryRequest request) {
        log.debug("Creating new inventory record for SKU: {}", LogSanitizer.clean(request.getSkuCode()));

        if (productClient.findBySkuCode(request.getSkuCode()).isEmpty()) {
            throw new SkuNotFoundInCatalogException(request.getSkuCode());
        }

        if (inventoryRepository.existsBySkuCode(request.getSkuCode())) {
            throw new DuplicateInventoryException(request.getSkuCode());
        }

        InventoryItem inventoryItem = InventoryItem.builder()
                .skuCode(request.getSkuCode())
                .quantityOnHand(request.getQuantityOnHand())
                .reorderThreshold(request.getReorderThreshold() != null ? request.getReorderThreshold() : 10)
                .build();

        InventoryItem savedItem = inventoryRepository.save(inventoryItem);

        writeAuditLog(savedItem.getSkuCode(), ChangeType.CREATED, null, savedItem.getQuantityOnHand());

        AuditLog.event("inventory_created", "Inventory record created for SKU {} with {} on hand",
                v("sku", LogSanitizer.clean(savedItem.getSkuCode())), v("quantity", savedItem.getQuantityOnHand()));

        return inventoryMapper.toResponse(savedItem);
    }

    /**
     * Get inventory record by ID
     */
    @Override
    public InventoryResponse getById(Long id) {
        log.debug("Fetching inventory record with ID: {}", id);

        InventoryItem inventoryItem = inventoryRepository.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException(id));

        return inventoryMapper.toResponse(inventoryItem);
    }

    /**
     * Get inventory record by SKU code
     */
    @Override
    public InventoryResponse getBySkuCode(String skuCode) {
        log.debug("Fetching inventory record with SKU: {}", LogSanitizer.clean(skuCode));

        InventoryItem inventoryItem = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryNotFoundException(skuCode));

        return inventoryMapper.toResponse(inventoryItem);
    }

    /**
     * Get all inventory records with pagination
     */
    @Override
    public Page<InventoryResponse> getAll(Pageable pageable) {
        log.debug("Fetching all inventory records - Page: {}, Size: {}",
                  pageable.getPageNumber(), pageable.getPageSize());

        return inventoryRepository.findAll(pageable).map(inventoryMapper::toResponse);
    }

    /**
     * Update an existing inventory record's quantity/threshold
     */
    @Override
    @Transactional
    public InventoryResponse update(Long id, InventoryRequest request) {
        log.debug("Updating inventory record with ID: {}", id);

        InventoryItem existingItem = inventoryRepository.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException(id));

        Integer previousQuantity = existingItem.getQuantityOnHand();

        existingItem.setQuantityOnHand(request.getQuantityOnHand());
        if (request.getReorderThreshold() != null) {
            existingItem.setReorderThreshold(request.getReorderThreshold());
        }

        InventoryItem updatedItem = inventoryRepository.save(existingItem);

        writeAuditLog(updatedItem.getSkuCode(), ChangeType.UPDATED, previousQuantity, updatedItem.getQuantityOnHand());

        AuditLog.event("inventory_updated", "Inventory record of SKU {} updated: {} -> {}",
                v("sku", LogSanitizer.clean(updatedItem.getSkuCode())), v("before", previousQuantity),
                v("after", updatedItem.getQuantityOnHand()));

        return inventoryMapper.toResponse(updatedItem);
    }

    /**
     * Increment or decrement a SKU's quantity on hand
     */
    @Override
    @Transactional
    public InventoryResponse adjustStock(String skuCode, int delta) {
        log.debug("Adjusting stock for SKU: {} by delta: {}", LogSanitizer.clean(skuCode), delta);

        InventoryItem inventoryItem = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryNotFoundException(skuCode));

        int previousQuantity = inventoryItem.getQuantityOnHand();
        int newQuantity = previousQuantity + delta;

        if (newQuantity < 0) {
            throw new IllegalArgumentException("Resulting quantity cannot be negative");
        }

        inventoryItem.setQuantityOnHand(newQuantity);
        InventoryItem savedItem = inventoryRepository.save(inventoryItem);

        writeAuditLog(savedItem.getSkuCode(), ChangeType.ADJUSTED, previousQuantity, newQuantity);

        AuditLog.event("stock_adjusted", "Stock of SKU {} adjusted by {}: {} -> {}",
                v("sku", LogSanitizer.clean(skuCode)), v("delta", delta), v("before", previousQuantity),
                v("after", newQuantity));

        return inventoryMapper.toResponse(savedItem);
    }

    /**
     * Reserve stock for one line of an order
     *
     * Not wrapped in a method-level transaction on purpose: the work is done by {@link #reserveInTransaction}
     * through the TransactionTemplate, so that the one case that needs a second try (see below) can start a
     * fresh transaction.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InventoryResponse reserveStock(String skuCode, int quantity, long orderId) {
        if (quantity < 1 || quantity > MAX_RESERVE_QUANTITY) {
            throw new IllegalArgumentException("quantity must be between 1 and " + MAX_RESERVE_QUANTITY);
        }
        requirePositiveOrderId(orderId);

        log.debug("Reserving {} unit(s) of SKU '{}' for order {}", quantity, LogSanitizer.clean(skuCode), orderId);

        try {
            return transactionTemplate.execute(status -> reserveInTransaction(skuCode, quantity, orderId));
        } catch (DataIntegrityViolationException ex) {
            // The same order and SKU was sent twice at the same moment: the other call stored the reservation
            // first, the database refused our duplicate row and undid our stock change (whole transaction).
            // Run once more: now the stored row is there, so this answers "done" (or 409 if it does not fit).
            log.info("Reserve of SKU '{}' for order {} met the same call at the same moment, checking again",
                     LogSanitizer.clean(skuCode), orderId);
            return transactionTemplate.execute(status -> reserveInTransaction(skuCode, quantity, orderId));
        }
    }

    /**
     * The reserve itself, in one transaction (started by reserveStock)
     */
    private InventoryResponse reserveInTransaction(String skuCode, int quantity, long orderId) {
        InventoryItem item = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryNotFoundException(skuCode));

        StockReservation existing = stockReservationRepository.findByOrderIdAndSkuCode(orderId, skuCode).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == ReservationStatus.RESERVED && existing.getQuantity() == quantity) {
                log.info("Reserve of SKU '{}' for order {} was already done, nothing changed", LogSanitizer.clean(skuCode), orderId);
                return inventoryMapper.toResponse(item);
            }
            if (existing.getStatus() == ReservationStatus.RELEASED) {
                throw ReservationConflictException.alreadyReleased(orderId, skuCode);
            }
            throw ReservationConflictException.otherQuantity(orderId, skuCode, existing.getQuantity(), quantity);
        }

        // One statement checks the stock and takes the units, so two callers cannot both take the last unit.
        if (inventoryRepository.decreaseIfEnough(skuCode, quantity) == 0) {
            int available = inventoryRepository.findBySkuCode(skuCode)
                    .map(InventoryItem::getQuantityOnHand)
                    .orElseThrow(() -> new InventoryNotFoundException(skuCode));
            AuditLog.warn("stock_reserve_refused", "Not enough stock of SKU {} for order {}: wanted {}, available {}",
                    v("sku", LogSanitizer.clean(skuCode)), v("orderId", orderId), v("wanted", quantity),
                    v("available", available));
            throw new InsufficientStockException(skuCode, quantity, available);
        }

        stockReservationRepository.save(StockReservation.builder()
                .orderId(orderId)
                .skuCode(skuCode)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .build());

        // Our UPDATE keeps the row locked until this transaction ends, so this read shows our own result.
        InventoryItem updated = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryNotFoundException(skuCode));
        writeAuditLog(skuCode, ChangeType.RESERVED, updated.getQuantityOnHand() + quantity, updated.getQuantityOnHand());

        AuditLog.event("stock_reserved", "Reserved {} unit(s) of SKU {} for order {}, {} left",
                v("quantity", quantity), v("sku", LogSanitizer.clean(skuCode)), v("orderId", orderId),
                v("after", updated.getQuantityOnHand()));

        return inventoryMapper.toResponse(updated);
    }

    /**
     * Release the stock reserved for one line of an order
     */
    @Override
    @Transactional
    public InventoryResponse releaseStock(String skuCode, long orderId) {
        requirePositiveOrderId(orderId);

        log.debug("Releasing the stock of SKU '{}' for order {}", LogSanitizer.clean(skuCode), orderId);

        InventoryItem item = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryNotFoundException(skuCode));

        StockReservation reservation = stockReservationRepository.findByOrderIdAndSkuCode(orderId, skuCode).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.RESERVED) {
            AuditLog.event("stock_release_noop", "Nothing reserved for SKU {} and order {}, nothing changed",
                    v("sku", LogSanitizer.clean(skuCode)), v("orderId", orderId));
            return inventoryMapper.toResponse(item);
        }

        // The status check is part of the UPDATE: if the same release arrives at the same moment, only one
        // of the two changes the row, and only that one puts the units back.
        if (stockReservationRepository.markReleased(orderId, skuCode) == 0) {
            AuditLog.event("stock_release_noop", "The stock of SKU {} for order {} was released by another call, nothing changed",
                    v("sku", LogSanitizer.clean(skuCode)), v("orderId", orderId));
            return inventoryMapper.toResponse(inventoryRepository.findBySkuCode(skuCode)
                    .orElseThrow(() -> new InventoryNotFoundException(skuCode)));
        }

        int quantity = reservation.getQuantity();
        inventoryRepository.increase(skuCode, quantity);

        InventoryItem updated = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryNotFoundException(skuCode));
        writeAuditLog(skuCode, ChangeType.RELEASED, updated.getQuantityOnHand() - quantity, updated.getQuantityOnHand());

        AuditLog.event("stock_released", "Released {} unit(s) of SKU {} for order {}, {} now on hand",
                v("quantity", quantity), v("sku", LogSanitizer.clean(skuCode)), v("orderId", orderId),
                v("after", updated.getQuantityOnHand()));

        return inventoryMapper.toResponse(updated);
    }

    private static void requirePositiveOrderId(long orderId) {
        if (orderId < 1) {
            throw new IllegalArgumentException("orderId must be a positive number");
        }
    }

    /**
     * Delete an inventory record by ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        log.debug("Deleting inventory record with ID: {}", id);

        InventoryItem inventoryItem = inventoryRepository.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException(id));

        Integer currentQuantity = inventoryItem.getQuantityOnHand();
        String skuCode = inventoryItem.getSkuCode();

        inventoryRepository.delete(inventoryItem);

        writeAuditLog(skuCode, ChangeType.DELETED, currentQuantity, null);

        AuditLog.event("inventory_deleted", "Inventory record of SKU {} deleted ({} were on hand)",
                v("sku", LogSanitizer.clean(skuCode)), v("quantity", currentQuantity));
    }

    /**
     * Get the SKUs that are at or below their reorder threshold, lowest quantity first, then SKU
     */
    @Override
    public Page<InventoryResponse> getLowStock(Pageable pageable) {
        log.debug("Fetching low-stock inventory records - Page: {}, Size: {}",
                  pageable.getPageNumber(), pageable.getPageSize());

        return inventoryRepository.findLowStock(pageable).map(inventoryMapper::toResponse);
    }

    /**
     * Get paginated audit history for a SKU, most recent first
     */
    @Override
    public Page<InventoryAuditLogResponse> getAuditHistory(String skuCode, Pageable pageable) {
        log.debug("Fetching audit history for SKU: {} - Page: {}, Size: {}",
                  LogSanitizer.clean(skuCode), pageable.getPageNumber(), pageable.getPageSize());

        return inventoryAuditLogRepository.findBySkuCodeOrderByChangedAtDesc(skuCode, pageable)
                .map(inventoryMapper::toAuditResponse);
    }

    /**
     * Get quantity on hand for a batch of SKU codes in a single call
     */
    @Override
    public Map<String, Integer> getStockForSkus(List<String> skuCodes) {
        log.debug("Fetching batch stock levels for {} SKU(s)", skuCodes.size());

        if (skuCodes.isEmpty()) {
            return Map.of();
        }

        return inventoryRepository.findBySkuCodeIn(skuCodes).stream()
                .collect(Collectors.toMap(InventoryItem::getSkuCode, InventoryItem::getQuantityOnHand));
    }

    private void writeAuditLog(String skuCode, ChangeType changeType, Integer previousQuantity, Integer newQuantity) {
        InventoryAuditLog auditLog = InventoryAuditLog.builder()
                .skuCode(skuCode)
                .changeType(changeType)
                .previousQuantity(previousQuantity)
                .newQuantity(newQuantity)
                .build();

        inventoryAuditLogRepository.save(auditLog);
    }
}
