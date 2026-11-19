import { screen, within } from '@testing-library/react';
import { makeProduct, renderAt } from '../test/helpers';
import { ProductCard } from './ProductCard';

function renderCard(
  overrides = {},
  extra: Parameters<typeof ProductCard>[0] extends infer P ? Partial<P> : never = {},
) {
  const product = makeProduct(7, 'Alpha Phone', 'ALPHA-1', { price: 800, brand: 'Alpha', ...overrides });
  return renderAt(
    <ul>
      <ProductCard product={product} {...extra} />
    </ul>,
  );
}

describe('ProductCard', () => {
  it('links the whole card to the product page, through the name', () => {
    renderCard();
    expect(screen.getByRole('link', { name: 'Alpha Phone' })).toHaveAttribute('href', '/products/7');
    expect(screen.getAllByRole('link')).toHaveLength(1);
  });

  it('shows the brand, the name as a heading, and the price', () => {
    renderCard();
    expect(screen.getByText('Alpha')).toHaveClass('product-brand');
    expect(screen.getByRole('heading', { level: 2, name: 'Alpha Phone' })).toBeInTheDocument();
    expect(screen.getByText('$800.00')).toHaveClass('price');
  });

  it('can use a smaller heading, for a row that has a heading of its own', () => {
    renderCard({}, { headingLevel: 3 });
    expect(screen.getByRole('heading', { level: 3, name: 'Alpha Phone' })).toBeInTheDocument();
  });

  it('leaves the brand line out when there is no brand', () => {
    renderCard({ brand: undefined });
    expect(document.querySelector('.product-brand')).toBeNull();
  });

  it('shows the MRP struck through and the discount in green when the MRP is above the price', () => {
    renderCard({ mrp: 1000, discountPercent: 20 });

    const card = screen.getByRole('listitem');
    expect(within(card).getByText('$1,000.00').tagName).toBe('S');
    expect(within(card).getByText('$1,000.00')).toHaveClass('mrp');
    expect(within(card).getByText('20% off')).toHaveClass('discount');
  });

  it('works out the percentage itself when the server sent none', () => {
    renderCard({ mrp: 1000 });
    expect(screen.getByText('20% off')).toBeInTheDocument();
  });

  it.each([
    ['no MRP', { mrp: undefined, discountPercent: undefined }],
    ['an MRP equal to the price', { mrp: 800, discountPercent: undefined }],
    ['an MRP below the price', { mrp: 700, discountPercent: undefined }],
    ['a discount percent but no MRP', { mrp: undefined, discountPercent: 15 }],
    ['an MRP equal to the price even though a percent came with it', { mrp: 800, discountPercent: 10 }],
  ])('shows no MRP and no discount with %s', (_label, overrides) => {
    renderCard(overrides);

    expect(document.querySelector('s')).toBeNull();
    expect(screen.queryByText(/% off/)).not.toBeInTheDocument();
  });

  it('shows the extra line the page puts at the bottom', () => {
    renderCard({}, { footer: <p>Stock 12</p> });
    expect(screen.getByText('Stock 12')).toBeInTheDocument();
  });

  it('is a plain card unless the product is sold out, and then it says so in its class and keeps the link and price', () => {
    renderCard();
    expect(screen.getByRole('link', { name: 'Alpha Phone' }).closest('li')).toHaveClass('product-card');
    expect(screen.getByRole('link', { name: 'Alpha Phone' }).closest('li')).not.toHaveClass('product-card--out');
  });

  it('marks a sold-out card with product-card--out, and still links to the product with its price', () => {
    renderCard({}, { soldOut: true, footer: <p>Out of stock</p> });
    const card = screen.getByRole('link', { name: 'Alpha Phone' }).closest('li')!;
    expect(card).toHaveClass('product-card', 'product-card--out');
    expect(within(card).getByText('Out of stock')).toBeInTheDocument();
    expect(within(card).getByText('$800.00')).toHaveClass('price');
    expect(within(card).getByRole('link', { name: 'Alpha Phone' })).toHaveAttribute('href', '/products/7');
  });

  it('shows the initials when there is no picture, and never a stock text of its own', () => {
    renderCard();
    expect(screen.getByText('AP')).toBeInTheDocument();
    expect(screen.queryByText(/stock/i)).not.toBeInTheDocument();
  });
});
