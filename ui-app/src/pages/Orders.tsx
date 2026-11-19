import { useEffect, useId, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { fetchOrders } from '../api/orders';
import type { Order, Product } from '../api/types';
import { ErrorMessage } from '../components/ErrorMessage';
import { Icon } from '../components/Icon';
import { Pager } from '../components/Pager';
import { ProductPicture } from '../components/ProductPicture';
import { RequireLogin } from '../components/RequireLogin';
import { useAsync } from '../hooks/useAsync';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useProductsBySku } from '../hooks/useProducts';
import { formatDateTime, formatNumber, formatPrice } from '../lib/format';
import { CART_NOT_CLEARED, readPlaced, STATUS_LABEL } from '../lib/order';

const PAGE_SIZE = 20;

function OrderCard({ order, products }: { order: Order; products: Record<string, Product | undefined> }) {
  const titleId = useId();
  return (
    <article className="order-card" aria-labelledby={titleId}>
      <header className="order-head">
        <div>
          <h2 id={titleId}>Order #{order.id}</h2>
          <p className="muted order-date">
            <time dateTime={order.createdAt}>{formatDateTime(order.createdAt)}</time>
          </p>
        </div>
        <span className={`order-status order-status--${order.status.toLowerCase()}`}>{STATUS_LABEL[order.status]}</span>
      </header>
      {order.status === 'REJECTED' && order.rejectReason && (
        // Plain text, never parsed as HTML.
        <p className="order-reason">
          <strong>Reason:</strong> {order.rejectReason}
        </p>
      )}
      <ul className="order-lines" aria-label={`Items in order ${order.id}`}>
        {order.items.map((line, position) => {
          const product = products[line.sku];
          return (
            <li key={`${position}-${line.sku}`} className="order-line">
              {/* The lookup can fail or not have arrived yet: the picture then shows the initials. */}
              <ProductPicture url={product?.imageUrl} alt="" name={line.name} className="order-line-pic" />
              <div className="order-line-info">
                {product ? (
                  <Link className="cart-name" to={`/products/${product.id}`}>
                    {line.name}
                  </Link>
                ) : (
                  <span className="cart-name">{line.name}</span>
                )}
                <span className="cart-sku">{line.sku}</span>
              </div>
              <div className="order-line-numbers">
                <span className="order-line-price">{formatPrice(line.unitPrice)}</span>
                <span className="order-line-qty">
                  <span className="muted">Quantity</span> <span className="mono">{formatNumber(line.quantity)}</span>
                </span>
              </div>
              <span className="order-line-total">{formatPrice(line.lineTotal)}</span>
            </li>
          );
        })}
      </ul>
      <dl className="order-total">
        <dt>Total</dt>
        <dd>{formatPrice(order.total)}</dd>
      </dl>
    </article>
  );
}

function OrdersPage() {
  useDocumentTitle('My orders');
  const [params, setParams] = useSearchParams();
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1);
  const location = useLocation();
  const navigate = useNavigate();

  // The cart page leaves a message in the history entry. Keep it for this visit, then take it out of the
  // entry, so reloading the page or coming back to it with Back does not show it again.
  const [placed] = useState(() => readPlaced(location.state));
  useEffect(() => {
    if (readPlaced(location.state)) {
      navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null });
    }
  }, [location, navigate]);

  const { data, error, loading, reload } = useAsync((signal) => fetchOrders(page - 1, PAGE_SIZE, { signal }), [page]);

  // The order lines only carry a SKU, name and price. The pictures come from the product service.
  const skus = Array.from(new Set((data?.content ?? []).flatMap((order) => order.items.map((line) => line.sku))));
  const products = useProductsBySku(skus);

  function goTo(next: number) {
    setParams(next > 1 ? { page: String(next) } : {});
  }

  let summary = '';
  if (data && data.numberOfElements > 0) {
    const from = data.number * data.size + 1;
    const to = data.number * data.size + data.numberOfElements;
    summary =
      data.totalElements === 1
        ? '1 order'
        : `Showing ${formatNumber(from)} to ${formatNumber(to)} of ${formatNumber(data.totalElements)} orders`;
  }

  return (
    <>
      <header className="page-head">
        <h1>My orders</h1>
        <p className="muted" aria-live="polite">
          {summary || (loading ? 'Loading your orders…' : ' ')}
        </p>
      </header>

      {placed && (
        <>
          <div className="notice notice--success" role="status">
            <Icon name="check" size={18} className="notice-icon" />
            Thank you. Order number {placed.orderId} has been placed.
          </div>
          {!placed.cartCleared && (
            <div className="notice" role="alert">
              {CART_NOT_CLEARED}
            </div>
          )}
        </>
      )}

      {error && (
        <ErrorMessage
          what="Could not load your orders"
          error={error}
          hint="Check that the order service is running, then try again."
          onRetry={reload}
        />
      )}

      {!error && data && data.content.length === 0 && !loading && (
        <div className="empty">
          <Icon name="bag" size={48} className="empty-icon" />
          {data.totalElements > 0 ? (
            <>
              <p>This page is past the end of your orders.</p>
              <button type="button" className="btn" onClick={() => goTo(1)}>
                Go to the first page
              </button>
            </>
          ) : (
            <>
              <p>You have not placed any orders yet.</p>
              <Link className="btn btn--buy" to="/products">
                Browse products
              </Link>
            </>
          )}
        </div>
      )}

      {!error && data && data.content.length > 0 && (
        <ul className="order-list" aria-busy={loading} data-loading={loading}>
          {data.content.map((order) => (
            <li key={order.id}>
              <OrderCard order={order} products={products} />
            </li>
          ))}
        </ul>
      )}

      {data && data.totalPages > 1 && !error && (
        <Pager
          page={data.number}
          totalPages={data.totalPages}
          first={data.first}
          last={data.last}
          disabled={loading}
          onPrevious={() => goTo(page - 1)}
          onNext={() => goTo(page + 1)}
        />
      )}
    </>
  );
}

/** The orders of the person who is logged in, newest first. A visitor is sent to the login page. */
export function Orders() {
  return (
    <RequireLogin>
      <OrdersPage />
    </RequireLogin>
  );
}
