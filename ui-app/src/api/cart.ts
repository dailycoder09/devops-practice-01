import { deleteNoContent, getJson, putJson, type RequestOptions } from './client';
import type { Cart } from './types';

// cart-service. Every call needs a login. The cart belongs to the person in
// the token, so no user id is ever sent.

export function fetchCart(options?: RequestOptions): Promise<Cart> {
  return getJson<Cart>('/api/cart', options);
}

/** Sets the quantity of one product (adds it, or replaces what was there). Answers with the whole cart. */
export function putCartItem(sku: string, quantity: number, options?: RequestOptions): Promise<Cart> {
  return putJson<Cart>(`/api/cart/items/${encodeURIComponent(sku)}`, { quantity }, options);
}

/** Works even when the product was not in the cart. */
export function deleteCartItem(sku: string, options?: RequestOptions): Promise<void> {
  return deleteNoContent(`/api/cart/items/${encodeURIComponent(sku)}`, options);
}

export function clearCart(options?: RequestOptions): Promise<void> {
  return deleteNoContent('/api/cart', options);
}
