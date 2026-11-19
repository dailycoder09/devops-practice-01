import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { fetchStockBatch } from '../api/inventory';
import { fetchCategories, fetchProducts, isSortKey, type SortKey } from '../api/products';
import { ErrorMessage } from '../components/ErrorMessage';
import { Icon } from '../components/Icon';
import { Pager } from '../components/Pager';
import { ProductCard } from '../components/ProductCard';
import { useAsync } from '../hooks/useAsync';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { formatNumber } from '../lib/format';
import { stockStatus } from '../lib/product';

const PAGE_SIZE = 24;
const SEARCH_DEBOUNCE_MS = 300;

/** The sorts the product API really has (see ProductController): by price in both directions, and by id. */
const SORT_TABS: { key: SortKey | ''; label: string }[] = [
  { key: '', label: 'Default' },
  { key: 'price-asc', label: 'Price: Low to High' },
  { key: 'price-desc', label: 'Price: High to Low' },
  { key: 'newest', label: 'Newest First' },
];

interface Changes {
  q?: string;
  category?: string;
  minPrice?: string;
  maxPrice?: string;
  sort?: SortKey | '';
  /** One-based, as shown in the URL and the pager. */
  page?: number;
}

const PRICE_PATTERN = /^\d{1,9}(\.\d{1,2})?$/;

/** The search words from the address. "search" is what the header sends; "q" is the older name. */
function searchWords(params: URLSearchParams): string {
  return params.get('search') ?? params.get('q') ?? '';
}

export function Products() {
  useDocumentTitle('Products');
  const [params, setParams] = useSearchParams();
  const q = searchWords(params);
  const category = params.get('category') ?? '';
  const minPrice = params.get('minPrice') ?? '';
  const maxPrice = params.get('maxPrice') ?? '';
  const sortParam = params.get('sort');
  const sort: SortKey | '' = isSortKey(sortParam) ? sortParam : '';
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1);
  const hasPrice = minPrice !== '' || maxPrice !== '';
  const filtered = q !== '' || category !== '' || hasPrice;
  const filterCount = [q !== '', category !== '', hasPrice].filter(Boolean).length;

  const [text, setText] = useState(q);
  const [minDraft, setMinDraft] = useState(minPrice);
  const [maxDraft, setMaxDraft] = useState(maxPrice);
  const [priceProblem, setPriceProblem] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const debounce = useRef<number | undefined>(undefined);
  const panelId = useId();
  const searchId = useId();
  const categoryId = useId();
  const minId = useId();
  const maxId = useId();

  // Everything the page shows lives in the URL, so refresh and deep links work.
  function update(changes: Changes, replace: boolean) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        if ('q' in changes) {
          next.delete('q');
          if (changes.q) next.set('search', changes.q);
          else next.delete('search');
        }
        for (const key of ['category', 'minPrice', 'maxPrice', 'sort'] as const) {
          if (!(key in changes)) continue;
          const value = changes[key];
          if (value) next.set(key, value);
          else next.delete(key);
        }
        if (changes.page && changes.page > 1) next.set('page', String(changes.page));
        else next.delete('page');
        return next;
      },
      { replace },
    );
  }

  function onSearchChange(value: string) {
    setText(value);
    window.clearTimeout(debounce.current);
    // Keep the text as typed (a trailing space is part of "foo bar"); only blank means no search.
    debounce.current = window.setTimeout(
      () => update({ q: value.trim() === '' ? '' : value }, true),
      SEARCH_DEBOUNCE_MS,
    );
  }

  // Back and forward change the URL under us: follow it.
  useEffect(() => {
    window.clearTimeout(debounce.current);
    setText(q);
  }, [q]);
  useEffect(() => {
    setMinDraft(minPrice);
    setMaxDraft(maxPrice);
    setPriceProblem(null);
  }, [minPrice, maxPrice]);
  useEffect(() => () => window.clearTimeout(debounce.current), []);

  function clearFilters() {
    window.clearTimeout(debounce.current);
    setText('');
    update({ q: '', category: '', minPrice: '', maxPrice: '' }, false);
  }

  function applyPrice(event: FormEvent) {
    event.preventDefault();
    const low = minDraft.trim();
    const high = maxDraft.trim();
    if ((low !== '' && !PRICE_PATTERN.test(low)) || (high !== '' && !PRICE_PATTERN.test(high))) {
      setPriceProblem('Enter prices as numbers, for example 20 or 49.99.');
      return;
    }
    if (low !== '' && high !== '' && Number(low) > Number(high)) {
      setPriceProblem('The lowest price must not be higher than the highest price.');
      return;
    }
    setPriceProblem(null);
    update({ minPrice: low, maxPrice: high }, false);
    setFiltersOpen(false);
  }

  const categories = useAsync((signal) => fetchCategories({ signal }), []);
  const products = useAsync(
    (signal) =>
      fetchProducts(
        { q, category, minPrice, maxPrice, sort: sort || undefined, page: page - 1, size: PAGE_SIZE },
        { signal },
      ),
    [q, category, minPrice, maxPrice, sort, page],
  );

  // One stock call per page of results. A failure here never hides the list.
  const pageData = products.data;
  const skus = useMemo(() => (pageData?.content ?? []).map((product) => product.skuCode), [pageData]);
  const stock = useAsync(async (signal) => ({ skus, map: await fetchStockBatch(skus, { signal }) }), [skus]);
  const stockFailed = Boolean(stock.error);
  const stockFresh = stock.data?.skus === skus;

  const rows = pageData?.content ?? [];
  const categoryOptions = categories.data ?? [];
  const options = category && !categoryOptions.includes(category) ? [category, ...categoryOptions] : categoryOptions;

  let summary = '';
  if (pageData && pageData.numberOfElements > 0) {
    const from = pageData.number * pageData.size + 1;
    const to = pageData.number * pageData.size + pageData.numberOfElements;
    summary = `Showing ${formatNumber(from)} to ${formatNumber(to)} of ${formatNumber(pageData.totalElements)} ${q ? 'results' : 'products'}`;
    if (q) summary += ` for “${q}”`;
    if (category) summary += ` in ${category}`;
  }

  const title = category || (q ? 'Search results' : 'All products');

  return (
    <div className="listing">
      <aside id={panelId} className={filtersOpen ? 'filters-panel is-open' : 'filters-panel'} aria-label="Filters">
        <div className="filters-head">
          <p className="filters-title">Filters</p>
          <button type="button" className="btn btn--small filters-done" onClick={() => setFiltersOpen(false)}>
            Done
          </button>
        </div>

        <form className="filters" onSubmit={(event) => event.preventDefault()}>
          <div className="filter">
            <label htmlFor={searchId}>Search</label>
            <input
              id={searchId}
              className="field"
              type="search"
              placeholder="Name or description"
              value={text}
              onChange={(event) => onSearchChange(event.target.value)}
              autoComplete="off"
            />
          </div>
          <div className="filter">
            <label htmlFor={categoryId}>Category</label>
            <select
              id={categoryId}
              className="field"
              value={category}
              onChange={(event) => update({ category: event.target.value }, false)}
            >
              <option value="">All categories</option>
              {options.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          </div>
          {filtered && (
            <button type="button" className="btn" onClick={clearFilters}>
              Clear filters
            </button>
          )}
        </form>

        <form className="filters filters--price" onSubmit={applyPrice} noValidate>
          <fieldset className="filter-group">
            <legend>Price</legend>
            <div className="price-range">
              <div className="filter">
                <label htmlFor={minId}>Lowest</label>
                <input
                  id={minId}
                  className="field"
                  type="text"
                  inputMode="decimal"
                  placeholder="0"
                  value={minDraft}
                  aria-invalid={priceProblem !== null}
                  onChange={(event) => setMinDraft(event.target.value)}
                />
              </div>
              <div className="filter">
                <label htmlFor={maxId}>Highest</label>
                <input
                  id={maxId}
                  className="field"
                  type="text"
                  inputMode="decimal"
                  placeholder="Any"
                  value={maxDraft}
                  aria-invalid={priceProblem !== null}
                  onChange={(event) => setMaxDraft(event.target.value)}
                />
              </div>
            </div>
            {priceProblem && (
              <p className="field-error" role="alert">
                {priceProblem}
              </p>
            )}
            <button type="submit" className="btn btn--small">
              Apply price
            </button>
          </fieldset>
        </form>

        {/* Phones only: the filters apply as they are chosen, so this just closes the panel. */}
        <div className="filters-foot">
          <button type="button" className="btn btn--buy btn--block" onClick={() => setFiltersOpen(false)}>
            Show results
          </button>
        </div>
      </aside>

      <div className="listing-main">
        <div className="listing-top card">
          <nav className="crumbs" aria-label="Breadcrumb">
            <Link to="/">Home</Link>
            <span className="crumbs-sep" aria-hidden="true">
              /
            </span>
            {category ? (
              <>
                <Link to="/products">Products</Link>
                <span className="crumbs-sep" aria-hidden="true">
                  /
                </span>
                <span aria-current="page">{category}</span>
              </>
            ) : (
              <span aria-current="page">Products</span>
            )}
          </nav>

          <header className="listing-head">
            <div>
              <h1>{title}</h1>
              <p className="muted" aria-live="polite">
                {summary || (products.loading ? 'Loading products…' : ' ')}
                {summary && q && (
                  <>
                    {' '}
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => {
                        window.clearTimeout(debounce.current);
                        setText('');
                        update({ q: '' }, false);
                      }}
                    >
                      Clear search
                    </button>
                  </>
                )}
              </p>
            </div>
            <button
              type="button"
              className="btn filters-toggle"
              aria-expanded={filtersOpen}
              aria-controls={panelId}
              onClick={() => setFiltersOpen((value) => !value)}
            >
              <Icon name="filter" size={18} />
              Filters
              {filterCount > 0 && <span className="filters-count">{filterCount}</span>}
            </button>
          </header>

          <div className="sort-bar" role="group" aria-label="Sort products">
            <span className="sort-label">Sort by</span>
            {SORT_TABS.map((tab) => (
              <button
                key={tab.key || 'default'}
                type="button"
                className={tab.key === sort ? 'sort-tab is-current' : 'sort-tab'}
                aria-pressed={tab.key === sort}
                onClick={() => update({ sort: tab.key }, false)}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        {stockFailed && (
          <p className="notice" role="status">
            Stock levels are unavailable right now. The inventory service may be down.
          </p>
        )}

        {products.error && (
          <ErrorMessage
            what="Could not load products"
            error={products.error}
            hint="Check that the product service is running, then try again."
            onRetry={products.reload}
          />
        )}

        {!products.error && !pageData && products.loading && (
          <div className="product-grid product-grid--skeleton" aria-hidden="true">
            {Array.from({ length: 12 }, (_, position) => (
              <div key={position} className="product-card product-card--skeleton">
                <div className="skeleton skeleton--picture" />
                <div className="product-card-body">
                  <div className="skeleton skeleton--line skeleton--short" />
                  <div className="skeleton skeleton--line" />
                  <div className="skeleton skeleton--line skeleton--short" />
                </div>
              </div>
            ))}
          </div>
        )}

        {!products.error && pageData && rows.length === 0 && !products.loading && (
          <div className="empty">
            <Icon name="search" size={48} className="empty-icon" />
            {pageData.totalElements > 0 ? (
              <>
                <p>This page is past the end of the results.</p>
                <button type="button" className="btn" onClick={() => update({}, false)}>
                  Go to the first page
                </button>
              </>
            ) : filtered ? (
              <>
                <p>No products match. Clear the search or pick another category.</p>
                <button type="button" className="btn" onClick={clearFilters}>
                  Clear search and category
                </button>
              </>
            ) : (
              <p>The catalog is empty. Add products through the product service API.</p>
            )}
          </div>
        )}

        {!products.error && rows.length > 0 && (
          <ul className="product-grid" aria-busy={products.loading} data-loading={products.loading}>
            {rows.map((product) => {
              // Only what a shopper needs to know: a card with plenty in stock says nothing, and neither
              // does one whose number has not arrived (or cannot be read: the notice above says so).
              const quantity = stockFailed || !stockFresh ? undefined : stock.data?.map[product.skuCode];
              const status = stockStatus(quantity);
              return (
                <ProductCard
                  key={product.id}
                  product={product}
                  soldOut={status.level === 'out'}
                  footer={
                    status.level === 'low' ? (
                      <p className="product-note product-note--low">
                        <span className="stock-dot" aria-hidden="true" />
                        {status.text}
                      </p>
                    ) : status.level === 'out' ? (
                      <p className="product-note product-note--out">{status.text}</p>
                    ) : undefined
                  }
                />
              );
            })}
          </ul>
        )}

        {pageData && pageData.totalPages > 0 && !products.error && (
          <Pager
            page={pageData.number}
            totalPages={pageData.totalPages}
            first={pageData.first}
            last={pageData.last}
            disabled={products.loading}
            onPrevious={() => update({ page: page - 1 }, false)}
            onNext={() => update({ page: page + 1 }, false)}
          />
        )}
      </div>
    </div>
  );
}
