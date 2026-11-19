package com.microservices.cart.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * The cart of the caller.
 *
 * @param items     the lines, in the order they were added
 * @param itemCount the sum of all quantities
 * @param total     the sum of the line totals of the available items (two decimals)
 */
public record CartResponse(
        List<CartLineResponse> items,
        @Schema(description = "Sum of all quantities") int itemCount,
        @Schema(description = "Sum of the line totals of the available items") BigDecimal total) {
}
