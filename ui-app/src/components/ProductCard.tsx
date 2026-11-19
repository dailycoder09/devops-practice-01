import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { Product } from '../api/types';
import { formatPrice } from '../lib/format';
import { formatDiscount, savings } from '../lib/product';
import { ProductPicture } from './ProductPicture';

interface Props {
  product: Product;
  /** The heading level of the name: 2 on a list page, 3 inside a row that has its own heading. */
  headingLevel?: 2 | 3;
  /**
   * A short note about the product (the list page says "Only 14 left" or "Out of stock"). CSS puts it on the
   * corner of the picture, so it takes no room in the card.
   */
  footer?: ReactNode;
  /** The product cannot be bought right now: the picture is shown dimmed and in grey. */
  soldOut?: boolean;
}

/**
 * One product in a grid or a row: a square picture, the brand, the name (two lines at most), the price,
 * and, only when the list price (MRP) really is above the price, the MRP struck through and the discount in
 * green. The whole card is one link to the product page.
 */
export function ProductCard({ product, headingLevel = 2, footer, soldOut = false }: Props) {
  const saved = savings(product.price, product.mrp, product.discountPercent);
  const discount = saved ? formatDiscount(saved.percent) : undefined;
  const Heading = headingLevel === 2 ? 'h2' : 'h3';

  return (
    <li className={soldOut ? 'product-card product-card--out' : 'product-card'}>
      {/* The name beside it already says what this is, so the picture is decorative. */}
      <ProductPicture url={product.imageUrl} alt="" name={product.name} />
      <div className="product-card-body">
        {product.brand && <p className="product-brand">{product.brand}</p>}
        <Heading className="product-name">
          {/* The link's ::after covers the whole card, so the card is one click target. */}
          <Link className="product-link" to={`/products/${product.id}`}>
            {product.name}
          </Link>
        </Heading>
        <p className="product-price">
          <span className="price">
            <span className="sr-only">Price </span>
            {formatPrice(product.price)}
          </span>
          {saved && (
            <>
              <s className="mrp">
                <span className="sr-only">MRP </span>
                {formatPrice(product.mrp)}
              </s>
              {discount && <span className="discount">{discount}</span>}
            </>
          )}
        </p>
        {footer}
      </div>
    </li>
  );
}
