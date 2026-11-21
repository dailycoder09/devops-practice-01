package com.microservices.order.service.impl;

import com.microservices.order.client.CartClient;
import com.microservices.order.client.CartLine;
import com.microservices.order.client.InventoryClient;
import com.microservices.order.client.ProductClient;
import com.microservices.order.client.ProductInfo;
import com.microservices.order.dto.response.OrderItemResponse;
import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.dto.response.OrderStatsResponse;
import com.microservices.order.exception.DependencyUnavailableException;
import com.microservices.order.exception.EmptyCartException;
import com.microservices.order.exception.OrderRejectedException;
import com.microservices.order.exception.ProductNotAvailableException;
import com.microservices.order.exception.StockRefusedException;
import com.microservices.order.exception.StockServiceException;
import com.microservices.order.events.AfterCommit;
import com.microservices.order.events.EventPublisher;
import com.microservices.order.logging.AuditLog;
import com.microservices.order.logging.LogSanitizer;
import com.microservices.order.mapper.OrderMapper;
import com.microservices.order.model.OrderStatus;
import com.microservices.order.service.Caller;
import com.microservices.order.service.OrderService;
import com.microservices.order.service.OrderStore;
import com.microservices.order.service.PricedLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Order Service Implementation
 *
 * The checkout. There is no transaction across services, so every step is its own small database transaction
 * ({@link OrderStore}) and this method is deliberately NOT @Transactional:
 *
 * <ol>
 *   <li>Read the caller's cart from cart-service with the caller's own token. Empty: 400. Not answering: 503.</li>
 *   <li>Look up name and price of every line in product-service. Gone: 422. Not answering: 503.
 *       The price comes from here, never from the cart. Nothing is saved up to this point.</li>
 *   <li>Save the order as PENDING with its lines and total (committed before the next step).</li>
 *   <li>Reserve the stock of every line in inventory-service (with an internal SERVICE token, with the order id).</li>
 *   <li>All reserved: CONFIRMED, then empty the cart (best effort). Answer 201.</li>
 *   <li>Stock refused (409 or 404) for a line: release every line, REJECTED, answer 409.</li>
 *   <li>Inventory not answering, too slow or failing for a line: release every line (including the one that
 *       failed), REJECTED, answer 503.</li>
 *   <li>A release that fails is logged and ignored.</li>
 *   <li>Once CONFIRMED, a failed cart clean-up never changes the order.</li>
 * </ol>
 *
 * Why every line is released, even those never reserved: a reserve that timed out may still have been applied
 * by inventory-service. A release does nothing when there is no reservation for (order id, SKU), so it is always safe.
 *
 * Audit events (logger "audit", each once per action; the user and the trace id come from the MDC of the request):
 * order_checkout_started (itemCount = number of lines), order_confirmed (INFO), order_rejected (WARN),
 * release_failed (ERROR, per line), cart_clear_failed (WARN). Logging never changes the outcome of a checkout.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    static final String STOCK_DID_NOT_ANSWER = "The stock service did not answer. Please try again.";
    static final String UNEXPECTED_FAILURE = "Something went wrong while placing your order. Please try again.";

    private static final int MAX_QUANTITY_PER_LINE = 999;

    /**
     * The largest total that fits the column DECIMAL(12,2)
     */
    private static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");

    private final CartClient cartClient;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final OrderStore orderStore;

    public OrderServiceImpl(CartClient cartClient, ProductClient productClient, InventoryClient inventoryClient,
                            OrderStore orderStore) {
        this.cartClient = cartClient;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.orderStore = orderStore;
    }

    /** Where events go (a Lambda function). Nothing is sent when the service is built without Spring or EVENTS_URL is empty. */
    private EventPublisher events = EventPublisher.NONE;

    @Autowired(required = false)
    public void setEventPublisher(EventPublisher events) {
        this.events = events == null ? EventPublisher.NONE : events;
    }

    @Override
    public OrderResponse placeOrder(Caller caller) {
        // Steps 1 and 2: read the cart and the prices. Nothing is saved yet.
        List<CartLine> cart = mergeSameSku(cartClient.readCart(caller.authorization()));
        if (cart.isEmpty()) {
            throw new EmptyCartException();
        }
        AuditLog.event("order_checkout_started", "Checkout started", kv("itemCount", cart.size()));
        List<PricedLine> lines = new ArrayList<>();
        for (CartLine line : cart) {
            ProductInfo product = productClient.findBySku(line.sku())
                    .orElseThrow(() -> new ProductNotAvailableException(line.sku()));
            lines.add(new PricedLine(line.sku(), product.name(), product.price(), line.quantity()));
        }
        BigDecimal total = lines.stream()
                .map(line -> OrderMapper.lineTotal(line.unitPrice(), line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(MAX_TOTAL) > 0) {
            // Prices like this are not real: do not guess, and do not fail on the database column.
            log.warn("The total of the cart is too large to store; the prices from product-service look wrong");
            throw DependencyUnavailableException.product(null);
        }

        // Step 3: save the order as PENDING.
        OrderResponse pending = orderStore.createPending(caller.userId(), caller.email(), lines);
        long orderId = pending.getId();
        log.debug("Order {} saved as PENDING with {} line(s)", orderId, lines.size());

        // Steps 4 to 7: reserve every line, then confirm. A failure ends in release and REJECTED.
        OrderResponse confirmed;
        try {
            for (OrderItemResponse item : pending.getItems()) {
                inventoryClient.reserve(item.getSku(), item.getQuantity(), orderId);
            }
            confirmed = orderStore.markConfirmed(orderId);
        } catch (StockRefusedException e) {
            throw reject(pending, HttpStatus.CONFLICT, e.getMessage());
        } catch (StockServiceException e) {
            throw reject(pending, HttpStatus.SERVICE_UNAVAILABLE, STOCK_DID_NOT_ANSWER);
        } catch (RuntimeException e) {
            // Not expected (a bug, a database error). Stock may already be reserved, so it is given back
            // and the order does not stay PENDING.
            log.error("Unexpected error while checking out order {}", orderId, e);
            throw reject(pending, HttpStatus.SERVICE_UNAVAILABLE, UNEXPECTED_FAILURE);
        }

        // Step 5 and 9: the order is CONFIRMED and stays so. Emptying the cart is only a clean-up.
        boolean cartCleared = clearCartQuietly(caller, orderId);
        AuditLog.event("order_confirmed", "Order confirmed",
                kv("orderId", orderId), kv("total", confirmed.getTotal()), kv("itemCount", lines.size()),
                kv("cartCleared", cartCleared));

        // Tell the outside (a Lambda) that the order was placed. It is saved and CONFIRMED; nothing here can change that.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("orderId", orderId);
        event.put("userId", caller.userId());
        event.put("email", caller.email());
        event.put("total", confirmed.getTotal());
        event.put("itemCount", lines.size());
        event.put("status", OrderStatus.CONFIRMED.name());
        AfterCommit.run(() -> events.publish("ORDER_PLACED", event));
        return confirmed.toBuilder().cartCleared(cartCleared).build();
    }

    @Override
    public Page<OrderResponse> listOrders(Caller caller, int page, int size) {
        return orderStore.findOwn(caller.userId(), page, size);
    }

    @Override
    public OrderResponse getOrder(Caller caller, long id) {
        return orderStore.findVisible(id, caller);
    }

    @Override
    public Page<OrderResponse> listAllOrders(OrderStatus status, int page, int size) {
        return orderStore.findAll(status, page, size);
    }

    @Override
    public OrderStatsResponse stats() {
        return orderStore.stats();
    }

    /**
     * The cart should hold one line per SKU. If it ever sends the same SKU twice, the quantities are added,
     * because inventory-service keeps one reservation per (order id, SKU).
     */
    private static List<CartLine> mergeSameSku(List<CartLine> lines) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (CartLine line : lines) {
            quantities.merge(line.sku(), line.quantity(), Integer::sum);
        }
        List<CartLine> merged = new ArrayList<>();
        quantities.forEach((sku, quantity) -> {
            if (quantity > MAX_QUANTITY_PER_LINE) {
                log.warn("The cart sent SKU '{}' so often that the quantity is too large", LogSanitizer.clean(sku));
                throw DependencyUnavailableException.cart(null);
            }
            merged.add(new CartLine(sku, quantity));
        });
        return merged;
    }

    /**
     * Steps 6 and 7: give back the stock of every line, then mark the order REJECTED. The returned exception
     * is what the caller gets (409 or 503, with the order id).
     */
    private OrderRejectedException reject(OrderResponse order, HttpStatus status, String reason) {
        releaseAll(order);
        try {
            orderStore.markRejected(order.getId(), reason);
        } catch (RuntimeException e) {
            // The person still gets the real answer. The order may stay PENDING; the stock is already released.
            log.error("Order {} could not be marked REJECTED", order.getId(), e);
        }
        AuditLog.warn("order_rejected", "Order rejected",
                kv("orderId", order.getId()), kv("reason", LogSanitizer.clean(reason)));
        return new OrderRejectedException(status, reason, order.getId());
    }

    /**
     * Step 8: a release that fails is logged (event release_failed, ERROR, one per line) and ignored, and the other
     * lines are still released.
     */
    private void releaseAll(OrderResponse order) {
        for (OrderItemResponse item : order.getItems()) {
            boolean released;
            try {
                released = inventoryClient.release(item.getSku(), order.getId());
            } catch (RuntimeException e) {
                released = false;
            }
            if (!released) {
                AuditLog.error("release_failed", "Stock could not be released; it can be released later by order id",
                        kv("orderId", order.getId()), kv("sku", LogSanitizer.clean(item.getSku())));
            }
        }
    }

    private boolean clearCartQuietly(Caller caller, long orderId) {
        boolean cleared;
        try {
            cleared = cartClient.clear(caller.authorization());
        } catch (RuntimeException e) {
            cleared = false;
        }
        if (!cleared) {
            AuditLog.warn("cart_clear_failed", "The order is confirmed, but the cart could not be emptied",
                    kv("orderId", orderId));
        }
        return cleared;
    }
}
