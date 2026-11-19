package com.microservices.order.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.microservices.order.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Order Response DTO
 *
 * Properties that are null are left out of the JSON (application.yml: non_null).
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderResponse {

    private Long id;

    /**
     * Who ordered: the user id (the "sub" claim of the token at checkout)
     */
    private Long userId;

    /**
     * Who ordered: the email claim of the token at checkout (left out of the JSON when there was none)
     */
    private String userEmail;

    private OrderStatus status;

    private BigDecimal total;

    /**
     * Why the order was rejected (null for the other statuses)
     */
    private String rejectReason;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    private List<OrderItemResponse> items;

    /**
     * Only in the answer of POST /api/orders: true when the cart was emptied after the order was confirmed
     */
    private Boolean cartCleared;
}
