import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { adjustStock, fetchInventory, fetchLowStock } from '../api/inventory';
import type { InventoryItem } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { Pager } from '../components/Pager';
import { SkuPanel } from '../components/SkuPanel';
import { StockBadge } from '../components/StockBadge';
import { useAsync } from '../hooks/useAsync';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { formatNumber } from '../lib/format';
import { withReference } from '../lib/reference';
import { inventoryLevel } from '../lib/stock';

const PAGE_SIZE = 20;

interface RowProps {
  initial: InventoryItem;
  onOpen: (sku: string) => void;
  /** Only administrators see the stock controls. The server checks this again. */
  canAdjust: boolean;
}

function InventoryRow({ initial, onOpen, canAdjust }: RowProps) {
  const [item, setItem] = useState(initial);
  const [amount, setAmount] = useState('1');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flashes, setFlashes] = useState(0);

  async function adjust(direction: 1 | -1) {
    const value = Number(amount);
    if (amount.trim() === '' || !Number.isInteger(value) || value < 1) {
      setError('Enter a whole number of 1 or more.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setItem(await adjustStock(item.skuCode, direction * value));
      setFlashes((count) => count + 1);
    } catch (failure) {
      setError(withReference(errorMessage(failure), failure));
    } finally {
      setBusy(false);
    }
  }

  // Two identical animations, swapped each time, so a repeat adjustment flashes again.
  const flashClass = flashes === 0 ? '' : flashes % 2 === 1 ? 'flash-a' : 'flash-b';

  return (
    <tr className={flashClass}>
      <td className="col-sku">
        <button type="button" className="link-button mono" onClick={() => onOpen(item.skuCode)}>
          {item.skuCode}
        </button>
      </td>
      <td className="num col-qty" aria-live="polite">
        <StockBadge level={inventoryLevel(item)} quantity={item.quantityOnHand} />
      </td>
      <td className="num mono col-reorder">{formatNumber(item.reorderThreshold)}</td>
      {canAdjust && (
        <td className="adjust-cell col-adjust">
          <div className="adjust">
            <input
              className="field field--number mono"
              type="number"
              min={1}
              step={1}
              value={amount}
              aria-label={`Amount to add or remove for ${item.skuCode}`}
              aria-invalid={error !== null}
              disabled={busy}
              onChange={(event) => setAmount(event.target.value)}
            />
            <button type="button" className="btn btn--small" disabled={busy} onClick={() => adjust(1)}>
              Add
            </button>
            <button type="button" className="btn btn--small" disabled={busy} onClick={() => adjust(-1)}>
              Remove
            </button>
          </div>
          {error && (
            <p className="row-error" role="alert">
              {error}
            </p>
          )}
        </td>
      )}
    </tr>
  );
}

export function Inventory() {
  useDocumentTitle('Inventory (admin)');
  const [params, setParams] = useSearchParams();
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1);
  // "Low stock only" lives in the address (?low=1), so a link can open the page already filtered.
  const lowOnly = params.get('low') === '1';
  const [openSku, setOpenSku] = useState<string | null>(null);
  const { isAdmin } = useAuth();

  const { data, error, loading, reload } = useAsync(
    (signal) =>
      lowOnly ? fetchLowStock(page - 1, PAGE_SIZE, { signal }) : fetchInventory(page - 1, PAGE_SIZE, { signal }),
    [page, lowOnly],
  );

  /** Every change of the filter or the page is written to the address in one go. */
  function goTo(nextPage: number, nextLowOnly = lowOnly) {
    const next = new URLSearchParams();
    if (nextLowOnly) next.set('low', '1');
    if (nextPage > 1) next.set('page', String(nextPage));
    setParams(next);
  }

  let summary = '';
  if (data && data.numberOfElements > 0) {
    const from = data.number * data.size + 1;
    const to = data.number * data.size + data.numberOfElements;
    summary = `Showing ${formatNumber(from)} to ${formatNumber(to)} of ${formatNumber(data.totalElements)} ${lowOnly ? 'low stock records' : 'records'}`;
  }

  return (
    <>
      <header className="page-head">
        <h1>Inventory</h1>
        <p className="muted" aria-live="polite">
          {summary || (loading ? 'Loading inventory…' : ' ')}
        </p>
      </header>

      <div className="toolbar">
        <label className="toggle">
          <input type="checkbox" checked={lowOnly} onChange={(event) => goTo(1, event.target.checked)} />
          <span>Low stock only</span>
        </label>
      </div>

      {error && (
        <ErrorMessage
          what="Could not load inventory"
          error={error}
          hint="Check that the inventory service is running, then try again."
          onRetry={reload}
        />
      )}

      {!error && data && data.content.length === 0 && !loading && (
        <div className="empty">
          <p>
            {lowOnly && page === 1 ? 'Nothing is low on stock.' : 'No inventory records on this page.'}
          </p>
          {page > 1 && (
            <button type="button" className="btn" onClick={() => goTo(1)}>
              Go to the first page
            </button>
          )}
        </div>
      )}

      {!error && data && data.content.length > 0 && (
        <>
          <div className="table-wrap">
            <table className="catalog admin-table inventory-table" aria-busy={loading} data-loading={loading}>
              <thead>
                <tr>
                  <th scope="col">SKU</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                  <th scope="col" className="num">
                    Reorder at
                  </th>
                  {isAdmin && <th scope="col">Adjust stock</th>}
                </tr>
              </thead>
              <tbody>
                {data.content.map((item) => (
                  // A new answer from the server (another page, or the filter changed) starts the row afresh.
                  <InventoryRow
                    key={`${item.id}:${item.quantityOnHand}:${item.updatedAt}`}
                    initial={item}
                    onOpen={setOpenSku}
                    canAdjust={isAdmin}
                  />
                ))}
              </tbody>
            </table>
          </div>
          <p className="legend">
            Quantity: red at 0, amber at or below the reorder threshold, green above it.
            {lowOnly && ' This list shows only the items at or below it, the lowest quantity first.'}
          </p>
          {!isAdmin && <p className="legend">Only an administrator can change stock.</p>}
        </>
      )}

      {data && data.totalPages > 0 && !error && (
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

      {openSku && <SkuPanel sku={openSku} onClose={() => setOpenSku(null)} />}
    </>
  );
}
