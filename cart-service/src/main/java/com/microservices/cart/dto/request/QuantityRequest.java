package com.microservices.cart.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Quantity Request DTO
 *
 * Body of PUT /api/cart/items/{sku}: how many of the product the cart should hold.
 * It must be a whole number from 1 to 99 (text, decimals and missing values are refused with 400).
 * There is deliberately no constructor with one argument: Jackson would use it to accept a bare number like 2 as the body.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
public class QuantityRequest {

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 99, message = "Quantity must be at most 99")
    @Schema(description = "How many of the product (the quantity is set, not added)", example = "2",
            minimum = "1", maximum = "99")
    private Integer quantity;
}
