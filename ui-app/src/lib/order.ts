import type { Order, OrderStatus } from '../api/types';

/** Shown when an order is confirmed but the cart could not be emptied afterwards. */
export const CART_NOT_CLEARED =
  'Your order is confirmed. We could not empty your cart, please remove the items yourself before ordering again.';

export const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Pending',
  CONFIRMED: 'Confirmed',
  REJECTED: 'Rejected',
};

/** What the cart page hands to the orders page, in the navigation state. */
export interface PlacedOrder {
  orderId: number;
  /** False when the cart could not be emptied after the order was confirmed. */
  cartCleared: boolean;
}

export function placedState(order: Order): { placed: PlacedOrder } {
  // Only an explicit false is a warning. If the server says nothing, the cart is read again anyway.
  return { placed: { orderId: order.id, cartCleared: order.cartCleared !== false } };
}

/** The navigation state may be anything (a person can reload or type the address), so check it. */
export function readPlaced(state: unknown): PlacedOrder | null {
  if (typeof state !== 'object' || state === null) return null;
  const placed = (state as { placed?: unknown }).placed;
  if (typeof placed !== 'object' || placed === null) return null;
  const { orderId, cartCleared } = placed as Record<string, unknown>;
  if (typeof orderId !== 'number' || !Number.isFinite(orderId)) return null;
  return { orderId, cartCleared: cartCleared !== false };
}
