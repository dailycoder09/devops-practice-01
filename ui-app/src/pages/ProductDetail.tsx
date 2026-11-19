import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getProductDetails } from '../api/products';
import type { ProductDetail as ProductDetailData } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { CopyButton } from '../components/CopyButton';
import { ErrorMessage } from '../components/ErrorMessage';
import { NotFoundView } from '../components/NotFoundView';
import { ProductGallery } from '../components/ProductGallery';
import { useAsync } from '../hooks/useAsync';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { failureText, MAX_QUANTITY, parseQuantity, QUANTITY_HINT } from '../lib/cart';
import { formatPrice } from '../lib/format';
import { formatSavings, parseProductId, savings, stockStatus } from '../lib/product';

function ProductNotFound() {
  return (
    <NotFoundView
      title="Product not found"
      text="This product doesn't exist. It may have been removed."
      primary={{ to: '/products', label: 'Back to products' }}
      secondary={{ to: '/', label: 'Go to home page' }}
    />
  );
}

function DetailSkeleton() {
  return (
    <div className="detail" aria-busy="true">
      <p className="muted detail-loading" role="status">
        Loading product…
      </p>
      <div className="detail-top" aria-hidden="true">
        <div className="skeleton skeleton--picture" />
        <div className="detail-info">
          <div className="skeleton skeleton--line skeleton--short" />
          <div className="skeleton skeleton--title" />
          <div className="skeleton skeleton--line" />
          <div className="skeleton skeleton--price" />
          <div className="skeleton skeleton--line skeleton--short" />
        </div>
      </div>
    </div>
  );
}

function DetailSection({ title, children }: { title: string; children: ReactNode }) {
  const titleId = useId();
  return (
    <section className="detail-section" aria-labelledby={titleId}>
      <h2 id={titleId}>{title}</h2>
      {children}
    </section>
  );
}

interface OrderProblem {
  text: string;
  /** True when the quantity box is what needs fixing. */
  inBox: boolean;
}

interface BuyBoxProps {
  sku: string;
  /** The stock read says there is none. Both buttons stay off. */
  soldOut: boolean;
}

/**
 * A visitor is asked to log in. A logged-in person picks a quantity and either adds it to the cart
 * ("Add to cart": this many more than the cart already has) or buys it now ("Buy now": the cart gets exactly
 * this quantity of this product, and the cart page opens).
 */
function BuyBox({ sku, soldOut }: BuyBoxProps) {
  const { user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const cart = useCart();
  const quantityId = useId();
  const [draft, setDraft] = useState('1');
  const [busy, setBusy] = useState<'add' | 'buy' | null>(null);
  const [added, setAdded] = useState<number | null>(null);
  const [problem, setProblem] = useState<OrderProblem | null>(null);

  if (!user) {
    return (
      <div className="buybox">
        <Link
          className="btn btn--buy btn--large btn--block"
          to="/login"
          state={{ from: `${location.pathname}${location.search}` }}
        >
          Log in to order
        </Link>
      </div>
    );
  }

  // Adding means "this many more than the cart already has", so the cart must be known first.
  // Without it, the server would be told a smaller number and the cart would shrink.
  const cartKnown = !cart.loading && cart.error === null;
  const off = busy !== null || !cartKnown || soldOut;

  function quantityToUse(): number | null {
    const quantity = parseQuantity(draft);
    if (quantity === null) setProblem({ text: QUANTITY_HINT, inBox: true });
    return quantity;
  }

  async function add(event: FormEvent) {
    event.preventDefault();
    if (off) return;
    setAdded(null);
    const quantity = quantityToUse();
    if (quantity === null) return;
    const inCart = cart.items.find((line) => line.sku === sku)?.quantity ?? 0;
    if (inCart + quantity > MAX_QUANTITY) {
      setProblem({
        text: `You already have ${inCart} in your cart. The most you can have is ${MAX_QUANTITY}.`,
        inBox: true,
      });
      return;
    }
    setBusy('add');
    setProblem(null);
    try {
      await cart.setQuantity(sku, inCart + quantity);
      setAdded(quantity);
    } catch (failure) {
      setProblem({ text: failureText(failure), inBox: false });
    } finally {
      setBusy(null);
    }
  }

  async function buyNow() {
    if (off) return;
    setAdded(null);
    const quantity = quantityToUse();
    if (quantity === null) return;
    setBusy('buy');
    setProblem(null);
    try {
      await cart.setQuantity(sku, quantity);
      navigate('/cart');
    } catch (failure) {
      setProblem({ text: failureText(failure), inBox: false });
      setBusy(null);
    }
  }

  return (
    <div className="buybox">
      {cart.error && (
        <ErrorMessage what="Could not load your cart" error={cart.error} onRetry={() => void cart.refresh()} />
      )}
      <form className="buybox-form" onSubmit={add} noValidate>
        <div className="buybox-qty">
          <label htmlFor={quantityId}>Quantity</label>
          <input
            id={quantityId}
            className="field field--number"
            type="number"
            min={1}
            max={MAX_QUANTITY}
            step={1}
            value={draft}
            disabled={busy !== null || soldOut}
            aria-invalid={problem?.inBox === true}
            onChange={(event) => setDraft(event.target.value)}
          />
        </div>
        <button type="submit" className="btn btn--cart btn--large" disabled={off}>
          {busy === 'add' ? 'Adding…' : 'Add to cart'}
        </button>
        <button type="button" className="btn btn--buy btn--large" disabled={off} onClick={() => void buyNow()}>
          {busy === 'buy' ? 'Opening cart…' : 'Buy now'}
        </button>
      </form>
      {problem && (
        <p className="field-error" role="alert">
          {problem.text}
        </p>
      )}
      <div aria-live="polite">
        {added !== null && (
          <p className="add-to-cart-done">
            Added {added} to your cart. <Link to="/cart">View cart</Link>
          </p>
        )}
      </div>
    </div>
  );
}

function ProductView({ product }: { product: ProductDetailData }) {
  // The lists are required by the contract, but the API is editable: never crash on a missing one.
  const images = product.images ?? [];
  const highlights = (product.highlights ?? []).filter((line) => line.trim() !== '');
  const groups = (product.specifications ?? []).filter((group) => (group.items ?? []).length > 0);
  const saved = savings(product.price, product.mrp, product.discountPercent);
  const stock = stockStatus(product.stockQuantity);

  return (
    <div className="detail">
      <nav className="crumbs" aria-label="Breadcrumb">
        <ol>
          <li>
            <Link to="/">Home</Link>
          </li>
          <li>
            <Link to="/products">Products</Link>
          </li>
          {product.category && (
            <li>
              <Link to={`/products?category=${encodeURIComponent(product.category)}`}>{product.category}</Link>
            </li>
          )}
          <li aria-current="page">{product.name}</li>
        </ol>
      </nav>

      <div className="detail-top">
        <div className="detail-media">
          <ProductGallery images={images} name={product.name} />
          <BuyBox sku={product.skuCode} soldOut={stock.level === 'out'} />
        </div>

        <div className="detail-info">
          {product.brand && <p className="detail-brand">{product.brand}</p>}
          <h1 className="detail-title">{product.name}</h1>
          <p className="detail-ref">
            <span>
              <span className="muted">SKU </span>
              <CopyButton value={product.skuCode} what="SKU" className="mono">
                {product.skuCode}
              </CopyButton>
            </span>
          </p>

          <div className="price-block">
            <p className="price-now">
              <span className="sr-only">Price </span>
              {formatPrice(product.price)}
            </p>
            {saved && (
              <p className="price-was">
                <s className="mrp">
                  <span className="sr-only">MRP </span>
                  {formatPrice(product.mrp)}
                </s>
                <span className="discount">{formatSavings(saved, formatPrice)}</span>
              </p>
            )}
          </div>

          <p className={`stock-line stock-line--${stock.level}`}>
            <span className="stock-dot" aria-hidden="true" />
            {stock.text}
          </p>

          {(product.warranty || product.seller) && (
            <dl className="detail-facts">
              {product.warranty && (
                <div>
                  <dt>Warranty</dt>
                  <dd>{product.warranty}</dd>
                </div>
              )}
              {product.seller && (
                <div>
                  <dt>Seller</dt>
                  <dd>{product.seller}</dd>
                </div>
              )}
            </dl>
          )}

          {highlights.length > 0 && (
            <DetailSection title="Highlights">
              <ul className="highlights">
                {highlights.map((line, position) => (
                  <li key={`${position}-${line}`}>{line}</li>
                ))}
              </ul>
            </DetailSection>
          )}

          {product.description && product.description.trim() !== '' && (
            <DetailSection title="Description">
              {/* Plain text. CSS keeps the line breaks; it is never parsed as HTML. */}
              <p className="detail-description">{product.description}</p>
            </DetailSection>
          )}

          {groups.length > 0 && (
            <DetailSection title="Specifications">
              <div className="spec-groups">
                {groups.map((group, groupPosition) => (
                  <table className="spec-table" key={`${groupPosition}-${group.group}`}>
                    <caption>{group.group}</caption>
                    <tbody>
                      {group.items.map((item, itemPosition) => (
                        <tr key={`${itemPosition}-${item.key}`}>
                          <th scope="row">{item.key}</th>
                          <td>{item.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ))}
              </div>
            </DetailSection>
          )}
        </div>
      </div>
    </div>
  );
}

function LoadedProduct({ id }: { id: number }) {
  const state = useAsync((signal) => getProductDetails(id, { signal }), [id]);
  const { data, error, loading, reload } = state;
  const missing = error instanceof ApiError && error.status === 404;

  useDocumentTitle(data ? data.name : missing ? 'Product not found' : 'Product');

  if (missing) return <ProductNotFound />;
  if (error) {
    return (
      <div className="detail">
        <ErrorMessage
          what="Could not load the product"
          error={error}
          hint="The product service may be down."
          onRetry={reload}
        />
        <p>
          <Link to="/products">Back to products</Link>
        </p>
      </div>
    );
  }
  if (data === undefined || loading) return <DetailSkeleton />;
  return <ProductView product={data} />;
}

function MissingProduct() {
  useDocumentTitle('Product not found');
  return <ProductNotFound />;
}

export function ProductDetail() {
  const { id } = useParams();
  const productId = parseProductId(id);
  if (productId === undefined) return <MissingProduct />;
  // Keyed, so moving from one product to another starts clean (gallery position, loading state).
  return <LoadedProduct key={productId} id={productId} />;
}
