package com.microservices.cart.service.impl;

import com.microservices.cart.client.ProductClient;
import com.microservices.cart.client.ProductInfo;
import com.microservices.cart.dto.response.CartLineResponse;
import com.microservices.cart.dto.response.CartResponse;
import com.microservices.cart.exception.UnknownProductException;
import com.microservices.cart.logging.AuditLog;
import com.microservices.cart.logging.LogSanitizer;
import com.microservices.cart.model.CartItem;
import com.microservices.cart.repository.CartItemRepository;
import com.microservices.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Cart Service Implementation
 *
 * The database is only used for short transactions. The calls to product-service are made outside of them,
 * so a slow product-service never holds a database connection.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CartServiceImpl implements CartService {

    /** The length of the sku_code column. A longer SKU cannot exist. */
    private static final int MAX_SKU_LENGTH = 50;

    private static final int MONEY_SCALE = 2;

    private final CartItemRepository repository;
    private final ProductClient productClient;
    private final TransactionTemplate transactionTemplate;

    @Override
    public CartResponse getCart(long userId) {
        List<CartItem> items = repository.findByUserIdOrderByIdAsc(userId);
        return toResponse(items, lookUp(items, Map.of()));
    }

    @Override
    public CartResponse setQuantity(long userId, String sku, int quantity) {
        // 1. The product must exist. 2. Everything product-service has to say is collected BEFORE anything is
        // saved, so a "did not answer" (503) never leaves a half-done change behind.
        ProductInfo product = productClient.findBySku(checkedSku(sku))
                .orElseThrow(() -> new UnknownProductException(sku));
        Map<String, Optional<ProductInfo>> known = new HashMap<>();
        known.put(sku, Optional.of(product));
        List<CartItem> before = repository.findByUserIdOrderByIdAsc(userId);
        known = lookUp(before, known);

        upsert(userId, sku, quantity);
        AuditLog.event("cart_item_set", "Cart item set: {} {}", kv("sku", LogSanitizer.clean(sku)),
                kv("quantity", quantity));

        List<CartItem> after = repository.findByUserIdOrderByIdAsc(userId);
        return toResponse(after, lookUp(after, known));
    }

    @Override
    public void removeItem(long userId, String sku) {
        int removed = repository.deleteByUserIdAndSkuCode(userId, sku);
        log.debug("Removed {} item(s) from the cart of user {}", removed, userId);
        AuditLog.event("cart_item_removed", "Cart item removed: {}", kv("sku", LogSanitizer.clean(sku)));
    }

    @Override
    public void clear(long userId) {
        int removed = repository.deleteByUserId(userId);
        log.debug("Removed {} item(s) from the cart of user {}", removed, userId);
        AuditLog.event("cart_cleared", "Cart cleared");
    }

    /**
     * A SKU that is empty or longer than the column cannot exist in product-service, so it is refused at once.
     */
    private static String checkedSku(String sku) {
        if (sku == null || sku.isBlank() || sku.length() > MAX_SKU_LENGTH) {
            throw new UnknownProductException(sku);
        }
        return sku;
    }

    /**
     * Adds the item or replaces its quantity, in one short transaction.
     * If two requests add the same new item at the same moment, the database refuses the second insert
     * (unique key). Then the item exists, so the second request is simply run again as an update.
     */
    private void upsert(long userId, String sku, int quantity) {
        try {
            transactionTemplate.executeWithoutResult(status -> saveQuantity(userId, sku, quantity));
        } catch (DataIntegrityViolationException ex) {
            log.debug("A parallel request added the same item first, updating it instead");
            transactionTemplate.executeWithoutResult(status -> saveQuantity(userId, sku, quantity));
        }
    }

    private void saveQuantity(long userId, String sku, int quantity) {
        CartItem item = repository.findByUserIdAndSkuCode(userId, sku)
                .orElseGet(() -> CartItem.builder().userId(userId).skuCode(sku).build());
        item.setQuantity(quantity);
        // flush inside the transaction, so a unique-key clash shows up here and not later
        repository.saveAndFlush(item);
    }

    /**
     * Looks up every item of the cart in product-service, one call per SKU (a SKU in {@code known} is not asked again).
     *
     * @return for each SKU the product, or empty if product-service says it does not exist
     * @throws com.microservices.cart.exception.ProductServiceUnavailableException if one lookup gets no usable answer
     */
    private Map<String, Optional<ProductInfo>> lookUp(List<CartItem> items, Map<String, Optional<ProductInfo>> known) {
        Map<String, Optional<ProductInfo>> products = new HashMap<>(known);
        for (CartItem item : items) {
            products.computeIfAbsent(item.getSkuCode(), productClient::findBySku);
        }
        return products;
    }

    private CartResponse toResponse(List<CartItem> items, Map<String, Optional<ProductInfo>> products) {
        List<CartLineResponse> lines = new ArrayList<>();
        int itemCount = 0;
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE);

        for (CartItem item : items) {
            int quantity = item.getQuantity();
            itemCount += quantity;
            Optional<ProductInfo> product = products.getOrDefault(item.getSkuCode(), Optional.empty());

            if (product.isPresent()) {
                BigDecimal unitPrice = product.get().price().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
                total = total.add(lineTotal);
                lines.add(new CartLineResponse(item.getSkuCode(), quantity, true,
                        product.get().name(), unitPrice, lineTotal));
            } else {
                lines.add(new CartLineResponse(item.getSkuCode(), quantity, false, null, null, null));
            }
        }
        return new CartResponse(lines, itemCount, total);
    }
}
