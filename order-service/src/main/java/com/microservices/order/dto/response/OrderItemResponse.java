package com.microservices.order.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Order Item Response DTO - one line of an order
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemResponse {

    private String sku;

    private String name;

    /**
     * Unit price at checkout
     */
    private BigDecimal unitPrice;

    private Integer quantity;

    /**
     * unitPrice times quantity
     */
    private BigDecimal lineTotal;
}
