import { useId } from 'react';
import { Link } from 'react-router-dom';
import { fetchLowStock } from '../api/inventory';
import { ErrorMessage } from '../components/ErrorMessage';
import { StockBadge } from '../components/StockBadge';
import { useAsync } from '../hooks/useAsync';
import { formatNumber } from '../lib/format';
import { inventoryLevel } from '../lib/stock';

export const LOW_STOCK_ROWS = 5;

/** The five items that are lowest on stock, with a link to the whole list on the inventory page. */
export function LowStockPanel() {
  const titleId = useId();
  const { data, error, loading, reload } = useAsync((signal) => fetchLowStock(0, LOW_STOCK_ROWS, { signal }), []);

  return (
    <section className="card panel-card" aria-labelledby={titleId}>
      <header className="panel-card-head">
        <h2 id={titleId}>Low stock</h2>
        <Link className="panel-card-link" to="/admin/inventory?low=1">
          See all low stock
        </Link>
      </header>

      {error && (
        <ErrorMessage
          what="Could not load the low stock list"
          error={error}
          hint="Check that the inventory service is running, then try again."
          onRetry={reload}
        />
      )}

      {!error && loading && !data && <p className="muted">Loading low stock…</p>}

      {!error && data && data.content.length === 0 && <p className="muted">Nothing is low on stock.</p>}

      {!error && data && data.content.length > 0 && (
        <table className="mini-table">
          <thead>
            <tr>
              <th scope="col">SKU</th>
              <th scope="col" className="num">
                Quantity
              </th>
              <th scope="col" className="num">
                Reorder at
              </th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((item) => (
              <tr key={item.id}>
                <td className="mono">{item.skuCode}</td>
                <td className="num">
                  <StockBadge level={inventoryLevel(item)} quantity={item.quantityOnHand} />
                </td>
                <td className="num mono">{formatNumber(item.reorderThreshold)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
