import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { fetchInventoryCount, fetchLowStockCount } from '../api/inventory';
import { fetchOrderStats } from '../api/orders';
import { fetchCategories, fetchProductCount } from '../api/products';
import { fetchUserCount } from '../api/users';
import { useAsync } from '../hooks/useAsync';
import { formatNumber, formatPrice } from '../lib/format';

interface CardProps {
  label: string;
  /** The text of the number. Missing while it is still on its way. */
  value?: string;
  failed: boolean;
  loading: boolean;
  /** The colour of the number. Only the words carry the meaning. */
  tone?: 'good' | 'bad' | 'warn';
  /** A small line under the number, only when the number is there. */
  note?: ReactNode;
}

function Card({ label, value, failed, loading, tone, note }: CardProps) {
  const text = failed ? '—' : value === undefined ? (loading ? '…' : '—') : value;
  return (
    <div className="kpi">
      <dt>{label}</dt>
      <dd
        className={`kpi-value${tone && !failed && value !== undefined ? ` kpi-value--${tone}` : ''}`}
        title={failed ? 'Could not load this number' : undefined}
      >
        {text}
        {failed && <span className="sr-only"> (unavailable)</span>}
      </dd>
      {note && !failed && value !== undefined && <dd className="kpi-note">{note}</dd>}
    </div>
  );
}

/**
 * The main numbers of the shop. Each one is loaded on its own, and the four order numbers share one call,
 * so a service that is down shows dashes for its numbers and never hides the others.
 */
export function KpiCards() {
  const products = useAsync((signal) => fetchProductCount({ signal }), []);
  const categories = useAsync(async (signal) => (await fetchCategories({ signal })).length, []);
  const inventory = useAsync((signal) => fetchInventoryCount({ signal }), []);
  const lowStock = useAsync((signal) => fetchLowStockCount({ signal }), []);
  const users = useAsync((signal) => fetchUserCount({ signal }), []);
  const orders = useAsync((signal) => fetchOrderStats({ signal }), []);

  const stats = orders.data;
  const ordersFailed = Boolean(orders.error);

  return (
    <section className="kpi-groups" aria-label="Totals">
      <div className="kpi-group">
        <h2 className="kpi-heading">Catalog and stock</h2>
        <dl className="kpis">
          <Card label="Products" value={products.data === undefined ? undefined : formatNumber(products.data)} failed={Boolean(products.error)} loading={products.loading} />
          <Card label="Categories" value={categories.data === undefined ? undefined : formatNumber(categories.data)} failed={Boolean(categories.error)} loading={categories.loading} />
          <Card label="Inventory records" value={inventory.data === undefined ? undefined : formatNumber(inventory.data)} failed={Boolean(inventory.error)} loading={inventory.loading} />
          <Card
            label="Low stock items"
            value={lowStock.data === undefined ? undefined : formatNumber(lowStock.data)}
            failed={Boolean(lowStock.error)}
            loading={lowStock.loading}
            tone={lowStock.data ? 'warn' : undefined}
            note={<Link to="/admin/inventory?low=1">See the list</Link>}
          />
        </dl>
      </div>
      <div className="kpi-group">
        <h2 className="kpi-heading">Orders and users</h2>
        <dl className="kpis">
          <Card
            label="Orders"
            value={stats ? formatNumber(stats.totalOrders) : undefined}
            failed={ordersFailed}
            loading={orders.loading}
            note={
              stats && (
                <>
                  {formatNumber(stats.pending)} pending · <Link to="/admin/orders">See all</Link>
                </>
              )
            }
          />
          <Card label="Confirmed orders" value={stats ? formatNumber(stats.confirmed) : undefined} failed={ordersFailed} loading={orders.loading} tone="good" />
          <Card label="Rejected orders" value={stats ? formatNumber(stats.rejected) : undefined} failed={ordersFailed} loading={orders.loading} tone={stats && stats.rejected > 0 ? 'bad' : undefined} />
          <Card label="Revenue" value={stats ? formatPrice(stats.revenue) : undefined} failed={ordersFailed} loading={orders.loading} note="From confirmed orders" />
          <Card
            label="Users"
            value={users.data === undefined ? undefined : formatNumber(users.data)}
            failed={Boolean(users.error)}
            loading={users.loading}
            note={<Link to="/admin/users">See all</Link>}
          />
        </dl>
      </div>
    </section>
  );
}
