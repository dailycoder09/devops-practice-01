package com.microservices.order.dto.response;

import java.math.BigDecimal;

/**
 * Order Stats Response DTO - numbers for the admin overview (GET /api/orders/stats)
 *
 * @param totalOrders all orders of all users
 * @param pending     orders that are still being processed
 * @param confirmed   orders whose stock was reserved
 * @param rejected    orders that were refused or failed
 * @param revenue     the sum of the totals of the CONFIRMED orders, two decimals
 *
 * @author Microservices Team
 * @version 1.0
 */
public record OrderStatsResponse(long totalOrders, long pending, long confirmed, long rejected, BigDecimal revenue) {
}
