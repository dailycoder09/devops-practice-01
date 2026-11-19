import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Products } from './Products';
import { makeProduct, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

const products = [
  makeProduct(1, 'Alpha Phone', 'ALPHA-1', {
    price: 1234.5,
    brand: 'Alpha',
    mrp: 1500,
    discountPercent: 18,
    imageUrl: 'https://placehold.co/800x800/png?text=Alpha',
  }),
  makeProduct(2, 'Bravo Lamp', 'BRAVO-2', { category: 'Home' }),
  // An MRP equal to the price is not a discount: the server sends no discountPercent.
  makeProduct(3, 'Charlie Mug', 'CHARLIE-3', { category: 'Home', brand: 'Charlie Co', mrp: 19.99 }),
];

function baseRoutes() {
  return {
    'GET /api/products': () => pageOf(products, { totalElements: 45 }),
    'GET /api/products/search': () => pageOf([products[1]]),
    'GET /api/products/categories': () => ['Electronics', 'Home'],
    // CHARLIE-3 is deliberately missing: the inventory service has no record for it.
    'GET /api/inventory/batch': () => ({ 'ALPHA-1': 215, 'BRAVO-2': 13 }),
  };
}

/** The card (list item) that holds a product's name link. */
async function cardOf(name: string): Promise<HTMLElement> {
  const link = await screen.findByRole('link', { name });
  return link.closest('li')!;
}

describe('Products page', () => {
  it('shows each product as a card with a link to its detail page', async () => {
    mockFetch(baseRoutes());
    renderAt(<Products />, '/products');

    const alpha = await screen.findByRole('link', { name: 'Alpha Phone' });
    expect(alpha).toHaveAttribute('href', '/products/1');
    expect(screen.getByRole('link', { name: 'Bravo Lamp' })).toHaveAttribute('href', '/products/2');
    expect(screen.getByRole('link', { name: 'Charlie Mug' })).toHaveAttribute('href', '/products/3');
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
    // Names are links now. They no longer open the SKU panel.
    expect(screen.queryByRole('button', { name: 'Alpha Phone' })).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows brand, price, picture and initials on each card, and omits what is absent', async () => {
    mockFetch(baseRoutes());
    renderAt(<Products />, '/products');

    const alpha = await cardOf('Alpha Phone');
    expect(within(alpha).getByText('Alpha')).toBeInTheDocument();
    expect(within(alpha).getByText('$1,234.50')).toBeInTheDocument();
    const picture = alpha.querySelector('img')!;
    expect(picture).toHaveAttribute('src', 'https://placehold.co/800x800/png?text=Alpha');
    expect(picture).toHaveAttribute('loading', 'lazy');

    // No brand and no image on this one: no brand line, the initials tile instead.
    const bravo = await cardOf('Bravo Lamp');
    expect(bravo.querySelector('.product-brand')).toBeNull();
    expect(bravo.querySelector('img')).toBeNull();
    expect(within(bravo).getByText('BL')).toBeInTheDocument();
    expect(within(bravo).getByText('$19.99')).toBeInTheDocument();
  });

  it('strikes through the MRP and shows the percentage only when the product has a discount', async () => {
    mockFetch(baseRoutes());
    renderAt(<Products />, '/products');

    const alpha = await cardOf('Alpha Phone');
    const mrp = within(alpha).getByText('$1,500.00');
    expect(mrp.tagName).toBe('S');
    expect(within(alpha).getByText('18% off')).toHaveClass('discount');

    for (const name of ['Bravo Lamp', 'Charlie Mug']) {
      const card = await cardOf(name);
      expect(card.querySelector('s')).toBeNull();
      expect(within(card).queryByText(/% off/)).not.toBeInTheDocument();
    }
    expect(screen.getAllByText(/% off/)).toHaveLength(1);
  });

  describe('stock on the cards', () => {
    // Alpha has 215 (plenty), Bravo has 13 (low), Charlie has no record (unknown).
    it('says "Only 13 left" on the card that is low and nothing on the others, from one batch call', async () => {
      const fetchMock = mockFetch(baseRoutes());
      renderAt(<Products />, '/products');

      const note = await screen.findByText('Only 13 left');
      expect(note).toHaveClass('product-note', 'product-note--low');
      const bravo = await cardOf('Bravo Lamp');
      expect(bravo).toContainElement(note);
      expect(bravo).not.toHaveClass('product-card--out');

      // Plenty in stock and no record: no note, no number, no badge, and no colour legend under the grid.
      for (const name of ['Alpha Phone', 'Charlie Mug']) {
        const card = await cardOf(name);
        expect(card.querySelector('.product-note')).toBeNull();
        expect(card.querySelector('.stock')).toBeNull();
        expect(card).not.toHaveClass('product-card--out');
        expect(within(card).queryByText(/left|out of stock|^stock$/i)).not.toBeInTheDocument();
      }
      expect(screen.queryByText('215')).not.toBeInTheDocument();
      expect(screen.queryByText(/Stock: green above 20/)).not.toBeInTheDocument();
      expect(document.querySelectorAll('.product-note')).toHaveLength(1);

      expect(screen.getByText('Showing 1 to 3 of 45 products')).toBeInTheDocument();
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
      expect(screen.queryByText(/Stock levels are unavailable/)).not.toBeInTheDocument();

      const batchCalls = requestedUrls(fetchMock).filter((url) => url.startsWith('/api/inventory/batch'));
      expect(batchCalls).toEqual(['/api/inventory/batch?skuCodes=ALPHA-1,BRAVO-2,CHARLIE-3']);
    });

    it('marks a sold-out product with a red "Out of stock" tag and a dimmed card, and no other card', async () => {
      mockFetch({
        ...baseRoutes(),
        'GET /api/inventory/batch': () => ({ 'ALPHA-1': 0, 'BRAVO-2': 13, 'CHARLIE-3': 215 }),
      });
      renderAt(<Products />, '/products');

      const tag = await screen.findByText('Out of stock');
      expect(tag).toHaveClass('product-note', 'product-note--out');
      const alpha = await cardOf('Alpha Phone');
      expect(alpha).toContainElement(tag);
      expect(alpha).toHaveClass('product-card--out');
      // The price stays, and the card is still a link to the product.
      expect(within(alpha).getByText('$1,234.50')).toBeInTheDocument();
      expect(within(alpha).getByRole('link', { name: 'Alpha Phone' })).toHaveAttribute('href', '/products/1');

      for (const name of ['Bravo Lamp', 'Charlie Mug']) {
        expect(await cardOf(name)).not.toHaveClass('product-card--out');
      }
      expect(document.querySelectorAll('.product-card--out')).toHaveLength(1);
      expect(screen.getAllByText(/Out of stock/)).toHaveLength(1);
      expect(screen.getByText('Only 13 left')).toHaveClass('product-note--low');
    });

    it.each([
      [20, 'Only 20 left'],
      [1, 'Only 1 left'],
    ])('a stock of %i counts as low and reads "%s", while 21 says nothing', async (quantity, text) => {
      mockFetch({ ...baseRoutes(), 'GET /api/inventory/batch': () => ({ 'ALPHA-1': quantity, 'BRAVO-2': 21 }) });
      renderAt(<Products />, '/products');

      expect(await screen.findByText(text)).toHaveClass('product-note--low');
      // 21 is plenty: no note on Bravo.
      expect((await cardOf('Bravo Lamp')).querySelector('.product-note')).toBeNull();
    });

    it('shows no note while the stock numbers are still on their way', async () => {
      mockFetch({ ...baseRoutes(), 'GET /api/inventory/batch': () => new Promise(() => {}) });
      renderAt(<Products />, '/products');

      expect(await screen.findByRole('link', { name: 'Alpha Phone' })).toBeInTheDocument();
      expect(document.querySelector('.product-note')).toBeNull();
      expect(document.querySelector('.stock')).toBeNull();
      expect(document.querySelector('.product-card--out')).toBeNull();
    });

    it('still shows the cards, with no notes but with a notice, when the stock call fails', async () => {
      mockFetch({
        ...baseRoutes(),
        'GET /api/inventory/batch': () => problem(503, 'Service Unavailable', 'inventory is down'),
      });
      renderAt(<Products />, '/products');

      expect(await screen.findByRole('link', { name: 'Alpha Phone' })).toBeInTheDocument();
      expect(
        await screen.findByText('Stock levels are unavailable right now. The inventory service may be down.'),
      ).toBeInTheDocument();
      // A guess is worse than silence: no "Only N left", no "Out of stock", and no card is dimmed.
      expect(document.querySelector('.product-note')).toBeNull();
      expect(document.querySelector('.stock')).toBeNull();
      expect(document.querySelector('.product-card--out')).toBeNull();
      expect(screen.getByRole('link', { name: 'Bravo Lamp' })).toBeInTheDocument();
      expect(screen.getByText('$1,234.50')).toBeInTheDocument();
    });
  });

  it('searches when the URL has a search term or category', async () => {
    const fetchMock = mockFetch(baseRoutes());
    renderAt(<Products />, '/products?q=lamp&category=Home&page=2');

    expect(await screen.findByRole('link', { name: 'Bravo Lamp' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('lamp');
    expect(screen.getByRole('combobox', { name: 'Category' })).toHaveValue('Home');
    const search = requestedUrls(fetchMock).find((url) => url.startsWith('/api/products/search'))!;
    const params = new URL(search, 'http://localhost').searchParams;
    expect(params.get('searchTerm')).toBe('lamp');
    expect(params.get('category')).toBe('Home');
    expect(params.get('page')).toBe('1'); // the URL is one-based, the API zero-based
  });

  it('waits for a pause in typing, then searches', async () => {
    const fetchMock = mockFetch(baseRoutes());
    const user = userEvent.setup();
    renderAt(<Products />, '/products');
    await screen.findByRole('link', { name: 'Alpha Phone' });

    await user.type(screen.getByRole('searchbox'), 'lamp');

    await waitFor(() =>
      expect(requestedUrls(fetchMock).some((url) => url.startsWith('/api/products/search?searchTerm=lamp'))).toBe(true),
    );
    // One request for the finished word, not one per keystroke.
    expect(requestedUrls(fetchMock).filter((url) => url.startsWith('/api/products/search'))).toHaveLength(1);
  });

  it('searches by category when one is picked', async () => {
    const fetchMock = mockFetch(baseRoutes());
    const user = userEvent.setup();
    renderAt(<Products />, '/products');
    await screen.findByRole('link', { name: 'Alpha Phone' });
    await screen.findByRole('option', { name: 'Home' });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'Home');

    expect(await screen.findByRole('link', { name: 'Bravo Lamp' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Alpha Phone' })).not.toBeInTheDocument();
    const search = requestedUrls(fetchMock).find((url) => url.startsWith('/api/products/search'))!;
    expect(new URL(search, 'http://localhost').searchParams.get('category')).toBe('Home');
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
  });

  it('moves to the next page and asks the API for it', async () => {
    const fetchMock = mockFetch(baseRoutes());
    const user = userEvent.setup();
    renderAt(<Products />, '/products');
    await screen.findByRole('link', { name: 'Alpha Phone' });

    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() =>
      expect(
        requestedUrls(fetchMock).some(
          (url) => url.startsWith('/api/products?') && new URL(url, 'http://localhost').searchParams.get('page') === '1',
        ),
      ).toBe(true),
    );
  });

  it('shows an empty state with a way to clear the filters', async () => {
    mockFetch({ ...baseRoutes(), 'GET /api/products/search': () => pageOf([]) });
    const user = userEvent.setup();
    renderAt(<Products />, '/products?q=zzz');

    expect(await screen.findByText('No products match. Clear the search or pick another category.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear search and category' }));
    expect(await screen.findByRole('link', { name: 'Alpha Phone' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('');
  });

  it('says what went wrong when the product list cannot load', async () => {
    mockFetch({ ...baseRoutes(), 'GET /api/products': () => problem(500, 'Internal Server Error', 'database is down') });
    renderAt(<Products />, '/products');

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load products. database is down.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('Products page: sorting, price and search text', () => {
  const productCalls = (fetchMock: ReturnType<typeof mockFetch>) =>
    requestedUrls(fetchMock)
      .filter((url) => url.startsWith('/api/products?') || url.startsWith('/api/products/search'))
      .map((url) => new URL(url, 'http://localhost'));
  const lastCall = (fetchMock: ReturnType<typeof mockFetch>) => {
    const calls = productCalls(fetchMock);
    return calls[calls.length - 1];
  };

  describe('sort tabs', () => {
    it('offers only the sorts the product API has, with the default one marked', async () => {
      mockFetch(baseRoutes());
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });

      const bar = screen.getByRole('group', { name: 'Sort products' });
      expect(within(bar).getAllByRole('button').map((button) => button.textContent)).toEqual([
        'Default',
        'Price: Low to High',
        'Price: High to Low',
        'Newest First',
      ]);
      expect(within(bar).getByRole('button', { name: 'Default' })).toHaveAttribute('aria-pressed', 'true');
      expect(within(bar).getByRole('button', { name: 'Newest First' })).toHaveAttribute('aria-pressed', 'false');
    });

    it('lists in the order the products were added until a sort is chosen', async () => {
      const fetchMock = mockFetch(baseRoutes());
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });

      expect(lastCall(fetchMock).searchParams.get('sortBy')).toBe('id');
      expect(lastCall(fetchMock).searchParams.get('sortDir')).toBe('asc');
      expect(lastCall(fetchMock).searchParams.get('size')).toBe('24');
    });

    it.each([
      ['Price: Low to High', 'price', 'asc'],
      ['Price: High to Low', 'price', 'desc'],
      ['Newest First', 'id', 'desc'],
    ])('%s asks the API to sort by %s %s and marks the tab', async (label, sortBy, sortDir) => {
      const fetchMock = mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });

      await user.click(screen.getByRole('button', { name: label }));

      await waitFor(() => expect(lastCall(fetchMock).searchParams.get('sortBy')).toBe(sortBy));
      expect(lastCall(fetchMock).searchParams.get('sortDir')).toBe(sortDir);
      expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: 'Default' })).toHaveAttribute('aria-pressed', 'false');
    });

    it('keeps the search and the category when the sort changes, and goes back to page one', async () => {
      const fetchMock = mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products?search=lamp&category=Home&page=3');
      await screen.findByRole('link', { name: 'Bravo Lamp' });

      await user.click(screen.getByRole('button', { name: 'Price: Low to High' }));

      await waitFor(() => expect(lastCall(fetchMock).searchParams.get('sortBy')).toBe('price'));
      const params = lastCall(fetchMock).searchParams;
      expect(params.get('searchTerm')).toBe('lamp');
      expect(params.get('category')).toBe('Home');
      expect(params.get('page')).toBe('0');
    });

    it('opens with the sort that is in the address, and ignores a sort it does not know', async () => {
      const fetchMock = mockFetch(baseRoutes());
      const first = renderAt(<Products />, '/products?sort=price-desc');
      await screen.findByRole('link', { name: 'Alpha Phone' });
      expect(lastCall(fetchMock).searchParams.get('sortDir')).toBe('desc');
      expect(screen.getByRole('button', { name: 'Price: High to Low' })).toHaveAttribute('aria-pressed', 'true');
      first.unmount();

      const second = mockFetch(baseRoutes());
      renderAt(<Products />, '/products?sort=bogus');
      await screen.findByRole('link', { name: 'Alpha Phone' });
      expect(lastCall(second).searchParams.get('sortBy')).toBe('id');
      expect(screen.getByRole('button', { name: 'Default' })).toHaveAttribute('aria-pressed', 'true');
    });
  });

  describe('price filter', () => {
    it('sends the lowest and highest price to the search endpoint', async () => {
      const fetchMock = mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });

      await user.type(screen.getByLabelText('Lowest'), '100');
      await user.type(screen.getByLabelText('Highest'), '499.99');
      await user.click(screen.getByRole('button', { name: 'Apply price' }));

      await waitFor(() => expect(lastCall(fetchMock).pathname).toBe('/api/products/search'));
      expect(lastCall(fetchMock).searchParams.get('minPrice')).toBe('100');
      expect(lastCall(fetchMock).searchParams.get('maxPrice')).toBe('499.99');
      expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
    });

    it('works with only one of the two', async () => {
      const fetchMock = mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });

      await user.type(screen.getByLabelText('Highest'), '50');
      await user.click(screen.getByRole('button', { name: 'Apply price' }));

      await waitFor(() => expect(lastCall(fetchMock).searchParams.get('maxPrice')).toBe('50'));
      expect(lastCall(fetchMock).searchParams.has('minPrice')).toBe(false);
    });

    it('opens with the prices that are in the address', async () => {
      const fetchMock = mockFetch(baseRoutes());
      renderAt(<Products />, '/products?minPrice=10&maxPrice=20');
      await screen.findByRole('link', { name: 'Bravo Lamp' });

      expect(screen.getByLabelText('Lowest')).toHaveValue('10');
      expect(screen.getByLabelText('Highest')).toHaveValue('20');
      expect(lastCall(fetchMock).searchParams.get('minPrice')).toBe('10');
    });

    it.each(['abc', '-5', '1e3', '12.345'])('does not send the price "%s", and says what is allowed', async (typed) => {
      const fetchMock = mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });
      const before = productCalls(fetchMock).length;

      await user.type(screen.getByLabelText('Lowest'), typed);
      await user.click(screen.getByRole('button', { name: 'Apply price' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Enter prices as numbers, for example 20 or 49.99.');
      expect(screen.getByLabelText('Lowest')).toHaveAttribute('aria-invalid', 'true');
      expect(productCalls(fetchMock)).toHaveLength(before);
    });

    it('does not send a lowest price that is above the highest price', async () => {
      const fetchMock = mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });
      const before = productCalls(fetchMock).length;

      await user.type(screen.getByLabelText('Lowest'), '500');
      await user.type(screen.getByLabelText('Highest'), '100');
      await user.click(screen.getByRole('button', { name: 'Apply price' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('The lowest price must not be higher than the highest price.');
      expect(productCalls(fetchMock)).toHaveLength(before);
    });

    it('Clear filters takes the prices away too', async () => {
      const user = userEvent.setup();
      mockFetch(baseRoutes());
      renderAt(<Products />, '/products?minPrice=10&maxPrice=20');
      await screen.findByRole('link', { name: 'Bravo Lamp' });

      await user.click(screen.getByRole('button', { name: 'Clear filters' }));

      await waitFor(() => expect(screen.getByLabelText('Lowest')).toHaveValue(''));
      expect(screen.getByLabelText('Highest')).toHaveValue('');
      expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
    });
  });

  describe('the line above the results', () => {
    it('says "Showing results for" the search text and has a Clear search button', async () => {
      const fetchMock = mockFetch({ ...baseRoutes(), 'GET /api/products/search': () => pageOf([products[1]], { totalElements: 1 }) });
      const user = userEvent.setup();
      renderAt(<Products />, '/products?search=lamp');

      expect(await screen.findByText(/Showing 1 to 1 of 1 results for “lamp”/)).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Search results');

      await user.click(screen.getByRole('button', { name: 'Clear search' }));

      expect(await screen.findByText('Showing 1 to 3 of 45 products')).toBeInTheDocument();
      expect(screen.getByRole('searchbox')).toHaveValue('');
      expect(lastCall(fetchMock).pathname).toBe('/api/products');
    });

    it('accepts the search text under the older name q too', async () => {
      mockFetch({ ...baseRoutes(), 'GET /api/products/search': () => pageOf([products[1]], { totalElements: 1 }) });
      renderAt(<Products />, '/products?q=lamp');
      expect(await screen.findByText(/results for “lamp”/)).toBeInTheDocument();
    });

    it('names the category in the heading, the line and the breadcrumb', async () => {
      mockFetch({ ...baseRoutes(), 'GET /api/products/search': () => pageOf([products[1]], { totalElements: 12 }) });
      renderAt(<Products />, '/products?category=Home');

      expect(await screen.findByText('Showing 1 to 1 of 12 products in Home')).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Home');
      const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
      expect(within(crumbs).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
      expect(within(crumbs).getByRole('link', { name: 'Products' })).toHaveAttribute('href', '/products');
      expect(within(crumbs).getByText('Home', { selector: '[aria-current="page"]' })).toBeInTheDocument();
    });

    it('has no Clear search button when nothing was searched for', async () => {
      mockFetch(baseRoutes());
      renderAt(<Products />, '/products');
      await screen.findByRole('link', { name: 'Alpha Phone' });
      expect(screen.queryByRole('button', { name: 'Clear search' })).not.toBeInTheDocument();
    });
  });

  describe('filters on a phone', () => {
    it('has a Filters button that shows how many filters are on and opens and closes the panel', async () => {
      mockFetch(baseRoutes());
      const user = userEvent.setup();
      renderAt(<Products />, '/products?search=lamp&category=Home');
      await screen.findByRole('link', { name: 'Bravo Lamp' });

      const toggle = screen.getByRole('button', { name: /^Filters/ });
      expect(toggle).toHaveTextContent('2');
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      const panel = document.getElementById(toggle.getAttribute('aria-controls')!)!;
      expect(panel).not.toHaveClass('is-open');

      await user.click(toggle);
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
      expect(panel).toHaveClass('is-open');

      await user.click(screen.getByRole('button', { name: 'Done' }));
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      expect(panel).not.toHaveClass('is-open');
    });
  });
});
