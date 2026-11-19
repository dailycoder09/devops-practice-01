import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { clearCart, deleteCartItem, fetchCart, putCartItem } from '../api/cart';
import type { Cart, CartLine } from '../api/types';
import { useAuth, type AuthUser } from '../auth/AuthContext';

export interface CartValue {
  /** The lines, in the order they were added. Empty when nobody is logged in. */
  items: CartLine[];
  /** The sum of the quantities. This is the number in the header. */
  itemCount: number;
  /** The sum of the line totals of the items that can still be bought. */
  total: number;
  /** True while the cart is being loaded. */
  loading: boolean;
  /** Set when the cart could not be loaded. Cleared when a load or a change works. */
  error: Error | null;
  /** Loads the cart again. It never rejects: a failure goes into `error`. */
  refresh: () => Promise<void>;
  /** Sets the quantity of one product. Rejects with an ApiError when the server refuses. */
  setQuantity: (sku: string, quantity: number) => Promise<void>;
  /** Removes one product. Rejects with an ApiError when the server refuses. */
  remove: (sku: string) => Promise<void>;
  /** Empties the cart. Rejects with an ApiError when the server refuses. */
  clear: () => Promise<void>;
}

const EMPTY_CART: Cart = { items: [], itemCount: 0, total: 0 };

interface CartState {
  /** Whose cart this is. A different person (or nobody) means this state is not theirs. */
  owner: AuthUser | null;
  cart: Cart;
  loading: boolean;
  error: Error | null;
}

function startState(owner: AuthUser | null): CartState {
  return { owner, cart: EMPTY_CART, loading: owner !== null, error: null };
}

const cents = (value: number) => Math.round(value * 100) / 100;

/** The cart without one product, with the count and the total worked out again. */
function withoutItem(cart: Cart, sku: string): Cart {
  const items = cart.items.filter((line) => line.sku !== sku);
  return {
    items,
    itemCount: items.reduce((sum, line) => sum + line.quantity, 0),
    total: cents(items.filter((line) => line.available).reduce((sum, line) => sum + (line.lineTotal ?? 0), 0)),
  };
}

const CartContext = createContext<CartValue | null>(null);

/** Must sit inside the AuthProvider: the cart belongs to the person who is logged in. */
export function CartProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [state, setState] = useState<CartState>(() => startState(user));
  // The newest owner, so an answer that arrives after a logout (or a new login) can be told apart and dropped.
  const currentOwner = useRef(user);
  currentOwner.current = user;

  // What is shown. State kept for someone else is never shown, not even for one render.
  const shown = state.owner === user ? state : startState(user);

  /** Changes the state for `owner`, unless that person is no longer the one logged in. */
  const update = useCallback((owner: AuthUser | null, change: (current: CartState) => Partial<CartState>) => {
    if (owner === null || currentOwner.current !== owner) return;
    setState((previous) => {
      const base = previous.owner === owner ? previous : startState(owner);
      return { ...base, ...change(base) };
    });
  }, []);

  const refresh = useCallback(async () => {
    const owner = currentOwner.current;
    if (owner === null) return;
    update(owner, () => ({ loading: true, error: null }));
    try {
      const cart = await fetchCart();
      update(owner, () => ({ cart, loading: false }));
    } catch (failure) {
      update(owner, () => ({ loading: false, error: failure instanceof Error ? failure : new Error(String(failure)) }));
    }
  }, [update]);

  useEffect(() => {
    if (user) void refresh();
    else setState((previous) => (previous.owner === null ? previous : startState(null)));
  }, [user, refresh]);

  const setQuantity = useCallback(
    async (sku: string, quantity: number) => {
      const owner = currentOwner.current;
      const cart = await putCartItem(sku, quantity);
      update(owner, () => ({ cart, loading: false, error: null }));
    },
    [update],
  );

  const remove = useCallback(
    async (sku: string) => {
      const owner = currentOwner.current;
      await deleteCartItem(sku);
      update(owner, (current) => ({ cart: withoutItem(current.cart, sku) }));
    },
    [update],
  );

  const clear = useCallback(async () => {
    const owner = currentOwner.current;
    await clearCart();
    update(owner, () => ({ cart: EMPTY_CART, loading: false, error: null }));
  }, [update]);

  const value = useMemo<CartValue>(
    () => ({
      items: shown.cart.items,
      itemCount: shown.cart.itemCount,
      total: shown.cart.total,
      loading: shown.loading,
      error: shown.error,
      refresh,
      setQuantity,
      remove,
      clear,
    }),
    [shown, refresh, setQuantity, remove, clear],
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartValue {
  const value = useContext(CartContext);
  if (!value) throw new Error('useCart must be used inside a CartProvider');
  return value;
}
