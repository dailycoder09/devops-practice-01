package com.microservices.order.mapper;

import com.microservices.order.dto.response.OrderItemResponse;
import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.model.Order;
import com.microservices.order.model.OrderItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns an order into its JSON shape. Call it inside a transaction: the lines are loaded on demand.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .status(order.getStatus())
                .total(order.getTotal())
                .rejectReason(order.getRejectReason())
                .createdAt(order.getCreatedAt())
                .items(order.getItems().stream().map(this::toItemResponse).toList())
                .build();
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return OrderItemResponse.builder()
                .sku(item.getSkuCode())
                .name(item.getName())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .lineTotal(lineTotal(item.getUnitPrice(), item.getQuantity()))
                .build();
    }

    /**
     * unit price times quantity, two decimals
     */
    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
