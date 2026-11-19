package com.microservices.cart.controller;

import com.microservices.cart.config.CartUserIdFilter;
import com.microservices.cart.config.OpenApiConfig;
import com.microservices.cart.dto.request.QuantityRequest;
import com.microservices.cart.dto.response.CartResponse;
import com.microservices.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart REST Controller
 *
 * The cart of the logged-in person. The user id always comes from the token (the "sub" claim, checked by
 * CartUserIdFilter), never from the URL or the body, so nobody can reach another person's cart.
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Cart", description = "APIs for the shopping cart of the logged-in person")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class CartController {

    private final CartService cartService;

    /**
     * Show the cart
     *
     * @param userId the user id from the token
     * @return the cart with HTTP 200
     */
    @GetMapping
    @Operation(summary = "Show my cart",
            description = "Items in the order they were added, with the current name and price from product-service")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "The cart",
                    content = @Content(schema = @Schema(implementation = CartResponse.class))),
        @ApiResponse(responseCode = "401", description = "No valid token"),
        @ApiResponse(responseCode = "403", description = "The role is not USER or ADMIN"),
        @ApiResponse(responseCode = "503", description = "The product service did not answer")
    })
    public ResponseEntity<CartResponse> getCart(
            @RequestAttribute(CartUserIdFilter.USER_ID_ATTRIBUTE) long userId) {
        log.debug("REST request to show the cart of user {}", userId);

        return ResponseEntity.ok(cartService.getCart(userId));
    }

    /**
     * Set the quantity of a product
     *
     * @param userId  the user id from the token
     * @param sku     SKU code of the product
     * @param request the new quantity (1 to 99)
     * @return the cart after the change with HTTP 200
     */
    @PutMapping("/items/{sku}")
    @Operation(summary = "Add a product or change its quantity",
            description = "Sets the quantity (it does not add to the old one). The SKU must exist in product-service")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "The cart after the change",
                    content = @Content(schema = @Schema(implementation = CartResponse.class))),
        @ApiResponse(responseCode = "400", description = "The quantity is missing, not a whole number, or not 1 to 99"),
        @ApiResponse(responseCode = "401", description = "No valid token"),
        @ApiResponse(responseCode = "403", description = "The role is not USER or ADMIN"),
        @ApiResponse(responseCode = "422", description = "Unknown product"),
        @ApiResponse(responseCode = "503", description = "The product service did not answer")
    })
    public ResponseEntity<CartResponse> setQuantity(
            @RequestAttribute(CartUserIdFilter.USER_ID_ATTRIBUTE) long userId,
            @Parameter(description = "SKU Code") @PathVariable String sku,
            @Valid @RequestBody QuantityRequest request) {
        log.debug("REST request to set a cart quantity for user {}: {}", userId, request.getQuantity());

        return ResponseEntity.ok(cartService.setQuantity(userId, sku, request.getQuantity()));
    }

    /**
     * Remove one product
     *
     * @param userId the user id from the token
     * @param sku    SKU code of the product
     * @return HTTP 204 No Content (also when the product was not in the cart)
     */
    @DeleteMapping("/items/{sku}")
    @Operation(summary = "Remove a product from my cart", description = "Answers 204 also when it was not in the cart")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "The product is not in the cart (any more)"),
        @ApiResponse(responseCode = "401", description = "No valid token"),
        @ApiResponse(responseCode = "403", description = "The role is not USER or ADMIN")
    })
    public ResponseEntity<Void> removeItem(
            @RequestAttribute(CartUserIdFilter.USER_ID_ATTRIBUTE) long userId,
            @Parameter(description = "SKU Code") @PathVariable String sku) {
        log.debug("REST request to remove a product from the cart of user {}", userId);

        cartService.removeItem(userId, sku);

        return ResponseEntity.noContent().build();
    }

    /**
     * Empty the cart
     *
     * @param userId the user id from the token
     * @return HTTP 204 No Content
     */
    @DeleteMapping
    @Operation(summary = "Empty my cart", description = "Removes all products. Answers 204 also when the cart was empty")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "The cart is empty"),
        @ApiResponse(responseCode = "401", description = "No valid token"),
        @ApiResponse(responseCode = "403", description = "The role is not USER or ADMIN")
    })
    public ResponseEntity<Void> clear(@RequestAttribute(CartUserIdFilter.USER_ID_ATTRIBUTE) long userId) {
        log.debug("REST request to empty the cart of user {}", userId);

        cartService.clear(userId);

        return ResponseEntity.noContent().build();
    }
}
