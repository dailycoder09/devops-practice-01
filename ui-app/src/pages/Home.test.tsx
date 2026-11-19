import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Product } from '../api/types';
import { makeProduct, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';
import { Home, topDeals } from './Home';

const categories = ['Books', 'Clothing', 'Electronics', 'Furniture', 'Groceries', 'Toys'];

/** Forty products. Every third one has no discount; the others have 1 to 26 percent. */
function pool(): Product[] {
  return Array.from({ length: 40 }, (_, index) => {
    const id = index + 1;
    const percent = id % 3 === 0 ? 0 : (id * 7) % 27 || 5;
    return makeProduct(id, `Item ${id}`, `SKU-${id}`, {
      price: 100,
      mrp: percent > 0 ? Math.round((100 / (100 - percent)) * 100 * 100) / 100 : 100,
      discountPercent: percent > 0 ? percent : undefined,
    });
  });
}

function routes(extra: Parameters<typeof mockFetch>[0] = {}) {
  return {
    'GET /api/products': () => pageOf(pool(), { size: 40, totalElements: 5000 }),
    'GET /api/products/categories': () => categories,
    'GET /api/products/search': (url: URL) => {
      const category = url.searchParams.get('category') ?? '';
      return pageOf([
        makeProduct(100, `${category} one`, `${category}-1`),
        makeProduct(101, `${category} two`, `${category}-2`),
      ]);
    },
    ...extra,
  };
}

const section = (name: string) => screen.findByRole('region', { name });

describe('Home page', () => {
  it('has a banner with a short headline and a Shop now button to the product list', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Find what you need. Order in a few clicks.');
    expect(screen.getByRole('link', { name: 'Shop now' })).toHaveAttribute('href', '/products');
  });

  it('says in one short line how to start, and the banner button is the light one for the dark blue', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    expect(screen.getByText('Shop by category or search for what you need.')).toBeInTheDocument();
    expect(screen.queryByText(/search box at the top of the page/)).not.toBeInTheDocument();
    const button = screen.getByRole('link', { name: 'Shop now' });
    expect(button).toHaveClass('btn', 'btn--light', 'btn--large');
    expect(button).not.toHaveClass('btn--buy');
  });

  it('gives only the "Top deals" row the soft blue wash', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    const deals = await section('Top deals');
    expect(deals).toHaveClass('row-section', 'row-section--deals');
    const books = await section('Books');
    expect(books).toHaveClass('row-section');
    expect(books).not.toHaveClass('row-section--deals');
    expect(document.querySelectorAll('.row-section--deals')).toHaveLength(1);
  });

  it('shows "Top deals": the biggest discounts among the first 40 products, best first, at most 12', async () => {
    const fetchMock = mockFetch(routes());
    renderAt(<Home />);

    const deals = await section('Top deals');
    const links = await within(deals).findAllByRole('link', { name: /^Item / });
    expect(links).toHaveLength(12);

    const percents = within(deals)
      .getAllByText(/% off$/)
      .map((element) => Number.parseInt(element.textContent ?? '', 10));
    expect(percents).toEqual([...percents].sort((a, b) => b - a));
    // Products without a discount are not deals.
    expect(within(deals).queryByRole('link', { name: 'Item 3' })).not.toBeInTheDocument();
    expect(within(deals).queryByRole('link', { name: 'Item 6' })).not.toBeInTheDocument();

    const poolCall = requestedUrls(fetchMock).find((url) => url.startsWith('/api/products?'))!;
    expect(new URL(poolCall, 'http://localhost').searchParams.get('size')).toBe('40');
  });

  it('gives "Top deals" a View all link to the product list', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    const deals = await section('Top deals');
    expect(within(deals).getByRole('link', { name: /^View all/ })).toHaveAttribute('href', '/products');
  });

  it('shows one row for each of the first three categories, each with a View all link', async () => {
    const fetchMock = mockFetch(routes());
    renderAt(<Home />);

    for (const name of ['Books', 'Clothing', 'Electronics']) {
      const row = await section(name);
      expect(await within(row).findByRole('link', { name: `${name} one` })).toHaveAttribute('href', '/products/100');
      expect(within(row).getByRole('link', { name: /^View all/ })).toHaveAttribute(
        'href',
        `/products?category=${name}`,
      );
    }
    expect(screen.queryByRole('region', { name: 'Furniture' })).not.toBeInTheDocument();

    const asked = requestedUrls(fetchMock)
      .filter((url) => url.startsWith('/api/products/search'))
      .map((url) => new URL(url, 'http://localhost').searchParams.get('category'));
    expect(asked.sort()).toEqual(['Books', 'Clothing', 'Electronics']);
  });

  it('shows the other categories as tiles with a Shop now look', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    const tiles = await screen.findByRole('heading', { level: 2, name: 'More to explore' });
    const list = tiles.closest('section')!;
    expect(within(list).getByRole('link', { name: /Furniture/ })).toHaveAttribute(
      'href',
      '/products?category=Furniture',
    );
    expect(within(list).getByRole('link', { name: /Groceries/ })).toHaveAttribute(
      'href',
      '/products?category=Groceries',
    );
    expect(within(list).getByRole('link', { name: /Toys/ })).toHaveAttribute('href', '/products?category=Toys');
    expect(within(list).queryByRole('link', { name: /Books/ })).not.toBeInTheDocument();
    expect(within(list).getAllByText('Shop now')).toHaveLength(3);
  });

  it('shows no tiles when there are only three categories or fewer', async () => {
    mockFetch(routes({ 'GET /api/products/categories': () => ['Books', 'Clothing'] }));
    renderAt(<Home />);

    await section('Books');
    expect(screen.queryByRole('heading', { name: 'More to explore' })).not.toBeInTheDocument();
  });

  it('shows each product card with its price, and the discount only when there is one', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    const deals = await section('Top deals');
    const card = (await within(deals).findAllByRole('listitem'))[0];
    expect(within(card).getByText('$100.00')).toBeInTheDocument();
    expect(within(card).getByText(/% off$/)).toBeInTheDocument();

    const books = await section('Books');
    const plain = (await within(books).findAllByRole('listitem'))[0];
    expect(within(plain).queryByText(/% off/)).not.toBeInTheDocument();
  });

  it('shows grey boxes while a row loads', () => {
    mockFetch({ 'GET /api/products': () => new Promise(() => {}), 'GET /api/products/categories': () => categories });
    renderAt(<Home />);

    expect(screen.getByRole('region', { name: 'Top deals, loading' })).toHaveAttribute('aria-busy', 'true');
  });

  it('leaves "Top deals" out when none of the products has a discount', async () => {
    mockFetch(
      routes({ 'GET /api/products': () => pageOf([makeProduct(1, 'Plain', 'P-1', { price: 5 })], { size: 40 }) }),
    );
    renderAt(<Home />);

    await section('Books');
    expect(screen.queryByRole('region', { name: 'Top deals' })).not.toBeInTheDocument();
  });

  it('says what went wrong in the "Top deals" row and tries again on request, without hiding the rest', async () => {
    let attempts = 0;
    mockFetch(
      routes({
        'GET /api/products': () => {
          attempts += 1;
          return attempts === 1
            ? problem(503, 'Service Unavailable', 'The product service is busy')
            : pageOf(pool(), { size: 40 });
        },
      }),
    );
    const user = userEvent.setup();
    renderAt(<Home />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the top deals. The product service is busy.');
    expect(await section('Books')).toBeInTheDocument();

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await section('Top deals')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says what went wrong in one category row, and the other rows still show', async () => {
    mockFetch(
      routes({
        'GET /api/products/search': (url: URL) =>
          url.searchParams.get('category') === 'Clothing'
            ? problem(500, 'Internal Server Error', 'database is down')
            : pageOf([makeProduct(100, 'Fine item', 'F-1')]),
      }),
    );
    renderAt(<Home />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load Clothing. database is down.');
    expect(await within(await section('Books')).findByRole('link', { name: 'Fine item' })).toBeInTheDocument();
  });

  it('says so when the categories cannot be loaded, and still shows the top deals', async () => {
    mockFetch(routes({ 'GET /api/products/categories': () => problem(503, 'Service Unavailable', 'no answer') }));
    renderAt(<Home />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the categories. no answer.');
    expect(await section('Top deals')).toBeInTheDocument();
  });

  it('titles the page', async () => {
    mockFetch(routes());
    renderAt(<Home />);
    await waitFor(() => expect(document.title).toBe('Shop online · MicroShop'));
  });

  it('has buttons that scroll a row, with a label that says which row', async () => {
    mockFetch(routes());
    renderAt(<Home />);

    const deals = await section('Top deals');
    expect(within(deals).getByRole('button', { name: 'Scroll Top deals to the left' })).toBeInTheDocument();
    expect(within(deals).getByRole('button', { name: 'Scroll Top deals to the right' })).toBeInTheDocument();
  });
});

describe('topDeals', () => {
  it('sorts by discount, keeps the original order for equal discounts, and drops products without one', () => {
    const list = [
      makeProduct(1, 'A', 'A', { price: 90, mrp: 100 }), // 10%
      makeProduct(2, 'B', 'B', { price: 100 }),
      makeProduct(3, 'C', 'C', { price: 50, mrp: 100 }), // 50%
      makeProduct(4, 'D', 'D', { price: 90, mrp: 100 }), // 10%
      makeProduct(5, 'E', 'E', { price: 100, mrp: 100 }),
    ];
    expect(topDeals(list).map((product) => product.name)).toEqual(['C', 'A', 'D']);
  });

  it('keeps only the first few', () => {
    const list = Array.from({ length: 30 }, (_, index) =>
      makeProduct(index, `P${index}`, `P${index}`, { price: 50, mrp: 100 }),
    );
    expect(topDeals(list, 5)).toHaveLength(5);
    expect(topDeals(list)).toHaveLength(12);
  });
});
