import { getJson, postJson, withQuery, type RequestOptions } from './client';
import type { Order, OrderStats, OrderStatus, Page } from './types';

// order-service. Every call needs a login. The server reads the cart itself,
// so placing an order sends no items, no prices and no total.

/** Turns the caller's cart into an order. Rejects with a 409 or 503 and a reason when it cannot. */
export function placeOrder(options?: RequestOptions): Promise<Order> {
  return postJson<Order>('/api/orders', undefined, options);
}

/** The caller's own orders, newest first. */
export function fetchOrders(page: number, size: number, options?: RequestOptions): Promise<Page<Order>> {
  return getJson<Page<Order>>(withQuery('/api/orders', { page, size }), options);
}

/** All orders of all users, newest first (administrators only). `status` narrows the list to one status. */
export function fetchAllOrders(
  page: number,
  size: number,
  status?: OrderStatus,
  options?: RequestOptions,
): Promise<Page<Order>> {
  return getJson<Page<Order>>(withQuery('/api/orders/all', { page, size, status }), options);
}

/** The order numbers and the revenue for the admin overview (administrators only). */
export function fetchOrderStats(options?: RequestOptions): Promise<OrderStats> {
  return getJson<OrderStats>('/api/orders/stats', options);
}
