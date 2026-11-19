import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { fetchCategories, fetchProducts } from '../api/products';
import type { Product } from '../api/types';
import { ErrorMessage } from '../components/ErrorMessage';
import { categoryIcon, Icon } from '../components/Icon';
import { ProductCard } from '../components/ProductCard';
import { ProductRow, ProductRowSkeleton } from '../components/ProductRow';
import { useAsync } from '../hooks/useAsync';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { savings } from '../lib/product';

/** How many products to load before picking the best discounts among them. */
export const DEAL_POOL = 40;
/** How many cards a row shows. */
export const ROW_SIZE = 12;
/** Categories that get a row of their own. The others get a tile. */
export const ROW_CATEGORIES = 3;
const MAX_TILES = 4;

/** The discount in whole percent, worked out the same way the product card does. Zero when there is none. */
function discountOf(product: Product): number {
  return savings(product.price, product.mrp, product.discountPercent)?.percent ?? 0;
}

/** The products with the biggest discounts first. A product without a discount is left out. */
export function topDeals(products: readonly Product[], count = ROW_SIZE): Product[] {
  return products
    .map((product, position) => ({ product, position, percent: discountOf(product) }))
    .filter((entry) => entry.percent > 0)
    .sort((a, b) => b.percent - a.percent || a.position - b.position)
    .slice(0, count)
    .map((entry) => entry.product);
}

function DealsRow() {
  const title = 'Top deals';
  const state = useAsync((signal) => fetchProducts({ page: 0, size: DEAL_POOL }, { signal }), []);
  const deals = useMemo(() => topDeals(state.data?.content ?? []), [state.data]);

  if (state.error) {
    return (
      <section className="row-section">
        <h2 className="row-title">{title}</h2>
        <ErrorMessage what="Could not load the top deals" error={state.error} onRetry={state.reload} />
      </section>
    );
  }
  if (state.loading || !state.data) return <ProductRowSkeleton title={title} variant="deals" />;
  if (deals.length === 0) return null;
  return (
    <ProductRow title={title} to="/products" variant="deals">
      {deals.map((product) => (
        <ProductCard key={product.id} product={product} headingLevel={3} />
      ))}
    </ProductRow>
  );
}

function CategoryRow({ category }: { category: string }) {
  const state = useAsync((signal) => fetchProducts({ category, page: 0, size: ROW_SIZE }, { signal }), [category]);

  if (state.error) {
    return (
      <section className="row-section">
        <h2 className="row-title">{category}</h2>
        <ErrorMessage what={`Could not load ${category}`} error={state.error} onRetry={state.reload} />
      </section>
    );
  }
  if (state.loading || !state.data) return <ProductRowSkeleton title={category} />;
  if (state.data.content.length === 0) return null;
  return (
    <ProductRow title={category} to={`/products?category=${encodeURIComponent(category)}`}>
      {state.data.content.map((product) => (
        <ProductCard key={product.id} product={product} headingLevel={3} />
      ))}
    </ProductRow>
  );
}

function CategoryTiles({ categories }: { categories: string[] }) {
  if (categories.length === 0) return null;
  return (
    <section className="tiles-section" aria-labelledby="more-categories">
      <h2 id="more-categories" className="row-title">
        More to explore
      </h2>
      <ul className="tiles">
        {categories.map((name, position) => (
          <li key={name}>
            <Link className={`tile tile--${position % 4}`} to={`/products?category=${encodeURIComponent(name)}`}>
              <Icon name={categoryIcon(name)} size={64} className="tile-icon" />
              <span className="tile-name">{name}</span>
              <span className="tile-cta">Shop now</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function Home() {
  useDocumentTitle('Shop online');
  const categories = useAsync((signal) => fetchCategories({ signal }), []);
  const names = categories.data ?? [];

  return (
    <div className="home">
      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-text">
          <h1 id="hero-title">Find what you need. Order in a few clicks.</h1>
          <p>Shop by category or search for what you need.</p>
          <Link className="btn btn--light btn--large" to="/products">
            Shop now
          </Link>
        </div>
        <div className="hero-art" aria-hidden="true">
          <span className="hero-ring hero-ring--a" />
          <span className="hero-ring hero-ring--b" />
          <Icon name="bag" size={120} className="hero-bag" />
        </div>
      </section>

      <DealsRow />

      {categories.error && (
        <ErrorMessage what="Could not load the categories" error={categories.error} onRetry={categories.reload} />
      )}

      {names.slice(0, ROW_CATEGORIES).map((name) => (
        <CategoryRow key={name} category={name} />
      ))}

      <CategoryTiles categories={names.slice(ROW_CATEGORIES, ROW_CATEGORIES + MAX_TILES)} />
    </div>
  );
}
