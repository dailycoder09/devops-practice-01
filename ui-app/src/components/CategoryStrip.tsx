import { Link, useLocation } from 'react-router-dom';
import { fetchCategories } from '../api/products';
import { useAsync } from '../hooks/useAsync';
import { categoryIcon, Icon } from './Icon';

/**
 * One link per category, under the page header, each with a small icon above its name. It scrolls sideways
 * when it does not fit. If the categories cannot be loaded the strip is simply not shown: every page still
 * works without it.
 */
export function CategoryStrip() {
  const { data } = useAsync((signal) => fetchCategories({ signal }), []);
  const location = useLocation();
  const current =
    location.pathname === '/products' ? (new URLSearchParams(location.search).get('category') ?? '') : null;
  const hasSearch = location.pathname === '/products' && new URLSearchParams(location.search).has('search');
  if (!data || data.length === 0) return null;

  return (
    <nav className="category-strip" aria-label="Categories">
      <div className="shell category-strip-inner">
        <Link
          to="/products"
          className={current === '' && !hasSearch ? 'category-link is-current' : 'category-link'}
          aria-current={current === '' && !hasSearch ? 'page' : undefined}
        >
          <Icon name="grid" size={26} />
          <span>All products</span>
        </Link>
        {data.map((name) => (
          <Link
            key={name}
            to={`/products?category=${encodeURIComponent(name)}`}
            className={current === name ? 'category-link is-current' : 'category-link'}
            aria-current={current === name ? 'page' : undefined}
          >
            <Icon name={categoryIcon(name)} size={26} />
            <span>{name}</span>
          </Link>
        ))}
      </div>
    </nav>
  );
}
