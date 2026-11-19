import { useEffect, useId, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { placeOrder } from '../api/orders';
import type { CartLine, Product } from '../api/types';
import { useCart } from '../cart/CartContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { Icon } from '../components/Icon';
import { ProductPicture } from '../components/ProductPicture';
import { RequireLogin } from '../components/RequireLogin';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useProductsBySku } from '../hooks/useProducts';
import { failureText, MAX_QUANTITY, parseQuantity, QUANTITY_HINT } from '../lib/cart';
import { formatNumber, formatPrice } from '../lib/format';
import { placedState } from '../lib/order';
import { formatDiscount, savings } from '../lib/product';

/** What is running, so every button can wait for it. A second click must never start a second change or order. */
type Working = 'change' | 'order' | null;

interface Problem {
  /** What we were trying to do: "We could not place your order." */
  what: string;
  /** The reason, as the server worded it. */
  reason: string;
}

interface RowProps {
  line: CartLine;
  /** What the product service knows about this SKU (picture, brand, list price). Missing when the lookup failed. */
  product?: Product;
  /** Runs a change to the cart. Resolves with the reason when it failed, or null when it worked. */
  perform: (job: () => Promise<void>) => Promise<string | null>;
  disabled: boolean;
}

function CartRow({ line, product, perform, disabled }: RowProps) {
  const { setQuantity, remove } = useCart();
  const [draft, setDraft] = useState(String(line.quantity));
  const [problem, setProblem] = useState<string | null>(null);
  const label = line.name ?? line.sku;

  // The server's number wins when it changes. A failed change leaves what the person typed.
  useEffect(() => setDraft(String(line.quantity)), [line.quantity]);

  async function run(job: () => Promise<void>) {
    setProblem(null);
    const reason = await perform(job);
    if (reason !== null) setProblem(reason);
  }

  function update(event: FormEvent) {
    event.preventDefault();
    if (disabled) return;
    const quantity = parseQuantity(draft);
    if (quantity === null) {
      setProblem(QUANTITY_HINT);
      return;
    }
    void run(() => setQuantity(line.sku, quantity));
  }

  /** Minus and plus change the number by one and save it straight away. */
  function step(direction: 1 | -1) {
    const next = (parseQuantity(draft) ?? line.quantity) + direction;
    if (next < 1 || next > MAX_QUANTITY) return;
    void run(() => setQuantity(line.sku, next));
  }

  const saved =
    line.available && product
      ? savings(line.unitPrice ?? product.price, product.mrp, product.discountPercent)
      : undefined;
  const discount = saved ? formatDiscount(saved.percent) : undefined;

  return (
    <li className={line.available ? 'cart-line' : 'cart-line cart-line--gone'}>
      <div className="cart-line-main">
        {line.available && product && (
          <Link className="cart-line-pic" to={`/products/${product.id}`} tabIndex={-1} aria-hidden="true">
            <ProductPicture url={product.imageUrl} alt="" name={label} />
          </Link>
        )}
        <div className="cart-line-info">
          {line.available ? (
            <>
              {product ? (
                <Link className="cart-name" to={`/products/${product.id}`}>
                  {line.name}
                </Link>
              ) : (
                <span className="cart-name">{line.name}</span>
              )}
              {product?.brand && <span className="cart-brand">{product.brand}</span>}
              <span className="cart-sku">{line.sku}</span>
              <span className="cart-price">
                <span className="price">{formatPrice(line.unitPrice)}</span>
                {saved && (
                  <>
                    <s className="mrp">
                      <span className="sr-only">MRP </span>
                      {formatPrice(product?.mrp)}
                    </s>
                    {discount && <span className="discount">{discount}</span>}
                  </>
                )}
              </span>
            </>
          ) : (
            <>
              <span className="cart-sku">{line.sku}</span>
              <span className="tag tag--out">No longer available</span>
            </>
          )}
        </div>
        {line.available && (
          <p className="cart-line-total">
            <span className="muted">Line total</span>
            <span>{formatPrice(line.lineTotal)}</span>
          </p>
        )}
      </div>

      <div className="cart-line-actions">
        {line.available ? (
          <form className="stepper" onSubmit={update} noValidate>
            <button
              type="button"
              className="stepper-button"
              aria-label={`Decrease quantity of ${label}`}
              disabled={disabled || (parseQuantity(draft) ?? line.quantity) <= 1}
              onClick={() => step(-1)}
            >
              <Icon name="minus" size={16} />
            </button>
            <input
              className="field field--number mono stepper-input"
              type="number"
              min={1}
              max={MAX_QUANTITY}
              step={1}
              value={draft}
              aria-label={`Quantity of ${label}`}
              aria-invalid={problem === QUANTITY_HINT}
              disabled={disabled}
              onChange={(event) => setDraft(event.target.value)}
            />
            <button
              type="button"
              className="stepper-button"
              aria-label={`Increase quantity of ${label}`}
              disabled={disabled || (parseQuantity(draft) ?? line.quantity) >= MAX_QUANTITY}
              onClick={() => step(1)}
            >
              <Icon name="plus" size={16} />
            </button>
            {/* Shown only when the typed number differs from the saved one. */}
            <button
              type="submit"
              className="btn btn--small stepper-update"
              aria-label={`Update quantity of ${label}`}
              disabled={disabled || draft.trim() === String(line.quantity)}
            >
              Update
            </button>
          </form>
        ) : (
          <span className="mono">{formatNumber(line.quantity)}</span>
        )}
        <button
          type="button"
          className="link-button cart-remove"
          aria-label={`Remove ${label}`}
          disabled={disabled}
          onClick={() => void run(() => remove(line.sku))}
        >
          Remove
        </button>
      </div>
      {problem && (
        <p className="row-error" role="alert">
          {problem}
        </p>
      )}
    </li>
  );
}

function CartPage() {
  useDocumentTitle('Your cart');
  const cart = useCart();
  const navigate = useNavigate();
  const [working, setWorking] = useState<Working>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const summaryId = useId();

  const hasItems = cart.items.length > 0;
  const hasUnavailable = cart.items.some((line) => !line.available);
  const busy = working !== null;

  async function perform(job: () => Promise<void>): Promise<string | null> {
    setWorking('change');
    setProblem(null);
    try {
      await job();
      return null;
    } catch (failure) {
      return failureText(failure);
    } finally {
      setWorking(null);
    }
  }

  async function emptyCart() {
    const reason = await perform(cart.clear);
    if (reason !== null) setProblem({ what: 'We could not empty your cart.', reason });
  }

  async function submitOrder() {
    setWorking('order');
    setProblem(null);
    try {
      const order = await placeOrder();
      // The server empties the cart itself. Read it again so the header and this page are right.
      void cart.refresh();
      // The buttons stay off until this page is gone, so a quick second click cannot place a second order.
      navigate('/orders', { state: placedState(order) });
    } catch (failure) {
      setWorking(null);
      // The login handler has already sent the person to the login page.
      if (failure instanceof ApiError && failure.status === 401) return;
      // The cart changed behind our back (emptied, or a product is gone): show it as it is now.
      if (failure instanceof ApiError && (failure.status === 400 || failure.status === 422)) void cart.refresh();
      setProblem({
        what: 'We could not place your order.',
        reason: failureText(
          failure,
          'Could not reach the server. Your order may not have been placed, so check My orders before you try again.',
        ),
      });
    }
  }

  const loadingFirst = cart.loading && !hasItems && cart.error === null;

  const products = useProductsBySku(cart.items.filter((line) => line.available).map((line) => line.sku));
  const price = priceDetails(cart.items, products, cart.total);

  return (
    <div className="cart-page">
      <header className="page-head cart-head">
        <h1>Your cart</h1>
        {hasItems && (
          // Same action as before, but quiet and away from Place order. It never asks twice.
          <button type="button" className="link-button cart-empty" disabled={busy} onClick={() => void emptyCart()}>
            Empty cart
          </button>
        )}
      </header>

      {cart.error && (
        <ErrorMessage
          what="Could not load your cart"
          error={cart.error}
          hint="Check that the cart service is running, then try again."
          onRetry={() => void cart.refresh()}
        />
      )}

      {problem && (
        <div className="problem" role="alert">
          <p>
            <strong>{problem.what}</strong>
          </p>
          <p>{problem.reason}</p>
        </div>
      )}

      {loadingFirst && (
        <p className="muted" role="status">
          Loading your cart…
        </p>
      )}

      {!cart.loading && cart.error === null && !hasItems && (
        <div className="empty empty--cart">
          <Icon name="cart" size={56} className="empty-icon" />
          <p>Your cart is empty.</p>
          <Link className="btn btn--buy" to="/products">
            Browse products
          </Link>
        </div>
      )}

      {hasItems && (
        <>
          {hasUnavailable && (
            <p className="notice" role="status">
              Some items are no longer available. Remove them to place your order.
            </p>
          )}

          <div className="cart-layout">
            <div className="cart-main">
              <ul className="cart-lines" aria-busy={cart.loading} data-loading={cart.loading}>
                {cart.items.map((line) => (
                  <CartRow key={line.sku} line={line} product={products[line.sku]} perform={perform} disabled={busy} />
                ))}
              </ul>

              <div className="cart-bar">
                <p className="cart-bar-total">
                  <span className="muted">Total</span>
                  <strong>{formatPrice(cart.total)}</strong>
                </p>
                <button
                  type="button"
                  className="btn btn--buy btn--large"
                  disabled={busy || cart.loading || hasUnavailable}
                  onClick={() => void submitOrder()}
                >
                  {working === 'order' ? 'Placing order…' : 'Place order'}
                </button>
              </div>
            </div>

            <section className="price-card" aria-labelledby={summaryId}>
              <h2 id={summaryId}>Price details</h2>
              <dl className="price-lines">
                <div>
                  <dt>{`Price (${formatNumber(cart.itemCount)} ${cart.itemCount === 1 ? 'item' : 'items'})`}</dt>
                  <dd>{formatPrice(price.price)}</dd>
                </div>
                {price.discount !== null && (
                  <div>
                    <dt>Discount</dt>
                    <dd className="discount">{`−${formatPrice(price.discount)}`}</dd>
                  </div>
                )}
                <div className="price-total">
                  <dt>Total amount</dt>
                  <dd>{formatPrice(cart.total)}</dd>
                </div>
              </dl>
              {price.discount !== null && (
                <p className="price-save">You will save {formatPrice(price.discount)} on this order</p>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * The numbers for the "Price details" card. The discount is the list prices (MRP) added up minus what the
 * cart costs, and it is only worked out when the list price is known for every product that can be bought.
 * Otherwise there is no discount line, and the first line is simply the price.
 */
export function priceDetails(
  items: readonly CartLine[],
  products: Record<string, Product | undefined>,
  total: number,
): { price: number; discount: number | null } {
  const available = items.filter((line) => line.available);
  const listPrices = available.map((line) => products[line.sku]?.mrp);
  if (available.length === 0 || listPrices.some((mrp) => typeof mrp !== 'number' || !Number.isFinite(mrp))) {
    return { price: total, discount: null };
  }
  const listTotal = available.reduce(
    (sum, line, position) => sum + (listPrices[position] as number) * line.quantity,
    0,
  );
  const discount = Math.round((listTotal - total) * 100) / 100;
  // A list price that is not above the price is no discount at all.
  return discount > 0 ? { price: Math.round(listTotal * 100) / 100, discount } : { price: total, discount: null };
}

/** The cart of the person who is logged in. A visitor is sent to the login page. */
export function Cart() {
  return (
    <RequireLogin>
      <CartPage />
    </RequireLogin>
  );
}
