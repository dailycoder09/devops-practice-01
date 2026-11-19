package com.microservices.order.controller;

import com.microservices.order.config.OpenApiConfig;
import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.dto.response.OrderStatsResponse;
import com.microservices.order.model.OrderStatus;
import com.microservices.order.service.Caller;
import com.microservices.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order Controller
 *
 * REST API for placing and reading orders. Every request needs a token with the role USER or ADMIN
 * (the rule is in SecurityConfig). The person is always the one in the token: no user id, price or total
 * is ever read from the request.
 *
 * Two read endpoints are for the admin area only (the role ADMIN is required by the rules in SecurityConfig, which
 * match their literal paths before the generic ones): GET /api/orders/all and GET /api/orders/stats. Spring picks a
 * literal path before the template /{id}, so they never clash with an order id.
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "Place an order from the cart and read your orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class OrderController {

    /**
     * The largest page number. With the largest page size this keeps the row offset far below the int limit
     * of the database driver, so a huge page number is a clear 400 and not an error.
     */
    private static final int MAX_PAGE = 100_000;

    private final OrderService orderService;

    /**
     * Place an order from the caller's cart
     *
     * @param authentication the checked token of the request
     * @return the order with HTTP 201
     */
    @PostMapping
    @Operation(summary = "Place an order",
            description = "Orders everything in your cart (no request body). Answers 201 with the confirmed order, "
                    + "400 if the cart is empty, 422 if a product is gone, 409 if the stock is short and "
                    + "503 if another service did not answer.")
    public ResponseEntity<OrderResponse> placeOrder(Authentication authentication) {
        Caller caller = Caller.from(authentication);
        log.debug("REST request to place an order for user {}", caller.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.placeOrder(caller));
    }

    /**
     * List the caller's own orders, newest first
     *
     * @param authentication the checked token of the request
     * @param page           page number, from 0 to 100000
     * @param size           page size, 1 to 100
     * @return a page of orders with HTTP 200
     */
    @GetMapping
    @Operation(summary = "List my orders", description = "Your own orders, newest first")
    public ResponseEntity<Page<OrderResponse>> listOrders(
            Authentication authentication,
            @Parameter(description = "Page number, from 0 (at most 100000)") @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @Parameter(description = "Page size (1 to 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Caller caller = Caller.from(authentication);
        log.debug("REST request to list orders of user {} - Page: {}, Size: {}", caller.userId(), page, size);
        return ResponseEntity.ok(orderService.listOrders(caller, page, size));
    }

    /**
     * List the orders of ALL users, newest first (admin only)
     *
     * @param authentication the checked token of the request
     * @param page           page number, from 0 to 100000
     * @param size           page size, 1 to 100
     * @param status         only orders with this status (PENDING, CONFIRMED or REJECTED); empty means every status
     * @return a page of orders with HTTP 200
     */
    @GetMapping("/all")
    @Operation(summary = "List all orders (admin)", description = "The orders of every user, newest first. "
            + "Needs the role ADMIN. Optional filter: status=PENDING, CONFIRMED or REJECTED (anything else is a 400)")
    public ResponseEntity<Page<OrderResponse>> listAllOrders(
            Authentication authentication,
            @Parameter(description = "Page number, from 0 (at most 100000)") @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @Parameter(description = "Page size (1 to 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Only this status: PENDING, CONFIRMED or REJECTED") @RequestParam(required = false) OrderStatus status) {
        Caller caller = Caller.from(authentication);
        log.debug("REST request to list all orders for admin {} - Page: {}, Size: {}, Status: {}", caller.userId(), page,
                size, status);
        return ResponseEntity.ok(orderService.listAllOrders(status, page, size));
    }

    /**
     * Numbers for the admin overview (admin only)
     *
     * @param authentication the checked token of the request
     * @return the count per status, the total count and the revenue of the confirmed orders, with HTTP 200
     */
    @GetMapping("/stats")
    @Operation(summary = "Order statistics (admin)", description = "totalOrders, pending, confirmed, rejected and the "
            + "revenue (the sum of the totals of the CONFIRMED orders). Needs the role ADMIN")
    public ResponseEntity<OrderStatsResponse> stats(Authentication authentication) {
        Caller caller = Caller.from(authentication);
        log.debug("REST request for the order statistics by admin {}", caller.userId());
        return ResponseEntity.ok(orderService.stats());
    }

    /**
     * Get one order
     *
     * @param authentication the checked token of the request
     * @param id             the order id
     * @return the order with HTTP 200, or 404 if it does not exist or belongs to someone else (admins can read any)
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get an order", description = "One order, if it is yours (an admin can read any order)")
    public ResponseEntity<OrderResponse> getOrder(
            Authentication authentication,
            @Parameter(description = "Order id") @PathVariable long id) {
        Caller caller = Caller.from(authentication);
        log.debug("REST request to get order {} for user {}", id, caller.userId());
        return ResponseEntity.ok(orderService.getOrder(caller, id));
    }
}
