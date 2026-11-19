package com.microservices.cart.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One line of the cart, as shown to the client.
 *
 * name, unitPrice and lineTotal are always in the JSON. They are null when the product no longer exists
 * (available is false). Money has two decimals.
 *
 * @param sku        SKU code
 * @param quantity   how many
 * @param available  false if product-service no longer knows the SKU
 * @param name       current product name, or null
 * @param unitPrice  current price of one piece, or null
 * @param lineTotal  unitPrice times quantity, or null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CartLineResponse(
        @Schema(example = "APPLE-IP15P-128") String sku,
        @Schema(example = "2") int quantity,
        boolean available,
        @Schema(nullable = true) String name,
        @Schema(nullable = true) BigDecimal unitPrice,
        @Schema(nullable = true) BigDecimal lineTotal) {
}
