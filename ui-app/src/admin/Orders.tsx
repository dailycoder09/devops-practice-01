import { useId, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchAllOrders } from '../api/orders';
import type { Order, OrderStatus } from '../api/types';
import { ErrorMessage } from '../components/ErrorMessage';
import { Icon } from '../components/Icon';
import { Pager } from '../components/Pager';
import { useAsync } from '../hooks/useAsync';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { formatDateTime, formatNumber, formatPrice } from '../lib/format';
import { STATUS_LABEL } from '../lib/order';
import { TableSkeleton } from './TableSkeleton';

const PAGE_SIZE = 20;
const COLUMNS = 6;

const FILTERS: readonly { value: OrderStatus | undefined; label: string }[] = [
  { value: undefined, label: 'All' },
  { value: 'CONFIRMED', label: STATUS_LABEL.CONFIRMED },
  { value: 'REJECTED', label: STATUS_LABEL.REJECTED },
  { value: 'PENDING', label: STATUS_LABEL.PENDING },
];

/** Only a status the server knows. Anything else in the address means "all". */
function readStatus(value: string | null): OrderStatus | undefined {
  return FILTERS.find((filter) => filter.value !== undefined && filter.value === value)?.value;
}

function customerOf(order: Order): string {
  if (order.userEmail) return order.userEmail;
  return order.userId === undefined ? '—' : `User ${order.userId}`;
}

function unitsIn(order: Order): number {
  return order.items.reduce((sum, line) => sum + line.quantity, 0);
}

function OrderRows({ order, open, onToggle }: { order: Order; open: boolean; onToggle: () => void }) {
  const detailId = useId();
  return (
    <>
      <tr className={`row-click${open ? ' is-open' : ''}`} onClick={onToggle}>
        <td className="col-order">
          <button
            type="button"
            className="link-button expander mono"
            aria-expanded={open}
            aria-controls={open ? detailId : undefined}
          >
            <Icon name={open ? 'chevron-down' : 'chevron-right'} size={16} />
            <span className="sr-only">Order </span>#{order.id}
          </button>
        </td>
        <td className="col-date">
          <time dateTime={order.createdAt}>{formatDateTime(order.createdAt)}</time>
        </td>
        <td className="cell-wrap col-customer">{customerOf(order)}</td>
        <td className="col-status">
          <span className={`order-status order-status--${order.status.toLowerCase()}`}>{STATUS_LABEL[order.status]}</span>
        </td>
        <td className="num mono col-total">{formatPrice(order.total)}</td>
        <td className="num mono col-items">{formatNumber(unitsIn(order))}</td>
      </tr>
      {open && (
        <tr className="detail-row">
          <td colSpan={COLUMNS} id={detailId}>
            <div className="order-detail">
              {order.rejectReason && (
                // Plain text, never parsed as HTML.
                <p className="order-detail-reason">
                  <strong>Reason:</strong> {order.rejectReason}
                </p>
              )}
              {order.userId !== undefined && (
                <p className="muted order-detail-user">Customer user ID: {order.userId}</p>
              )}
              <ul className="detail-lines" aria-label={`Items in order ${order.id}`}>
                {order.items.map((line, position) => (
                  <li key={`${position}-${line.sku}`}>
                    <span className="detail-line-name">
                      {line.name} <span className="mono muted">{line.sku}</span>
                    </span>
                    <span className="detail-line-calc mono">
                      {formatNumber(line.quantity)} × {formatPrice(line.unitPrice)}
                    </span>
                    <span className="detail-line-total mono">{formatPrice(line.lineTotal)}</span>
                  </li>
                ))}
              </ul>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/** Every order of every customer, newest first, with a filter on the status. A click on a row shows its items. */
export function AdminOrders() {
  useDocumentTitle('Orders (admin)');
  const [params, setParams] = useSearchParams();
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1);
  const status = readStatus(params.get('status'));
  const [openIds, setOpenIds] = useState<ReadonlySet<number>>(() => new Set());

  const { data, error, loading, reload } = useAsync(
    (signal) => fetchAllOrders(page - 1, PAGE_SIZE, status, { signal }),
    [page, status],
  );

  function goTo(nextPage: number, nextStatus: OrderStatus | undefined) {
    const next = new URLSearchParams();
    if (nextStatus) next.set('status', nextStatus);
    if (nextPage > 1) next.set('page', String(nextPage));
    setParams(next);
  }

  function toggle(id: number) {
    setOpenIds((before) => {
      const after = new Set(before);
      if (!after.delete(id)) after.add(id);
      return after;
    });
  }

  const what = status ? `${STATUS_LABEL[status].toLowerCase()} orders` : 'orders';
  let summary = '';
  if (data && data.numberOfElements > 0) {
    const from = data.number * data.size + 1;
    const to = data.number * data.size + data.numberOfElements;
    summary = `Showing ${formatNumber(from)} to ${formatNumber(to)} of ${formatNumber(data.totalElements)} ${what}`;
  }

  return (
    <>
      <header className="page-head">
        <h1>Orders</h1>
        <p className="muted" aria-live="polite">
          {summary || (loading ? 'Loading orders…' : ' ')}
        </p>
      </header>

      <div className="toolbar">
        <div className="seg" role="group" aria-label="Filter by status">
          {FILTERS.map((filter) => (
            <button
              key={filter.label}
              type="button"
              className={`seg-button${filter.value === status ? ' is-current' : ''}`}
              aria-pressed={filter.value === status}
              onClick={() => goTo(1, filter.value)}
            >
              {filter.label}
            </button>
          ))}
        </div>
      </div>

      {error && (
        <ErrorMessage
          what="Could not load orders"
          error={error}
          hint="Check that the order service is running, then try again."
          onRetry={reload}
        />
      )}

      {!error && loading && !data && <TableSkeleton columns={COLUMNS} label="Loading orders…" />}

      {!error && data && data.content.length === 0 && !loading && (
        <div className="empty">
          <p>
            {page > 1
              ? 'No orders on this page.'
              : status
                ? `There are no ${what} yet.`
                : 'There are no orders yet.'}
          </p>
          {(page > 1 || status) && (
            <button type="button" className="btn" onClick={() => goTo(1, page > 1 ? status : undefined)}>
              {page > 1 ? 'Go to the first page' : 'Show all orders'}
            </button>
          )}
        </div>
      )}

      {!error && data && data.content.length > 0 && (
        <>
          <div className="table-wrap">
            <table className="catalog admin-table orders-table" aria-busy={loading} data-loading={loading}>
              <thead>
                <tr>
                  <th scope="col">Order</th>
                  <th scope="col">Date</th>
                  <th scope="col">Customer</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="num">
                    Total
                  </th>
                  <th scope="col" className="num">
                    Items
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((order) => (
                  <OrderRows key={order.id} order={order} open={openIds.has(order.id)} onToggle={() => toggle(order.id)} />
                ))}
              </tbody>
            </table>
          </div>
          <p className="legend">Click an order to see what was in it.</p>
        </>
      )}

      {data && data.totalPages > 0 && !error && (
        <Pager
          page={data.number}
          totalPages={data.totalPages}
          first={data.first}
          last={data.last}
          disabled={loading}
          onPrevious={() => goTo(page - 1, status)}
          onNext={() => goTo(page + 1, status)}
        />
      )}
    </>
  );
}
