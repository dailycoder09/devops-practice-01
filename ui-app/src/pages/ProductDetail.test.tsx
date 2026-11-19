import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import type { ProductDetail as ProductDetailData, Role } from '../api/types';
import { ProductDetail } from './ProductDetail';
import { deferred, fakeCart, makeDetail, mockFetch, problem, renderAt, requestedUrls } from '../test/helpers';

const images = [
  { url: 'https://placehold.co/800x800/png?text=One', alt: 'Front view' },
  { url: 'https://placehold.co/800x800/png?text=Two', alt: 'Side view' },
  { url: 'https://placehold.co/800x800/png?text=Three' }, // no alt: the product name stands in
];

function renderDetail(path: string, auth?: Role) {
  return renderAt(
    <Routes>
      <Route path="/products/:id" element={<ProductDetail />} />
      <Route path="/products" element={<p>The product list</p>} />
    </Routes>,
    path,
    { auth },
  );
}

function serve(detail: ProductDetailData) {
  return mockFetch({ [`GET /api/products/${detail.id}/details`]: () => detail });
}

function gallery() {
  return screen.getByRole('group', { name: 'Product images' });
}

function mainImage() {
  // Thumbnails are decorative (empty alt), so the only named image is the main one.
  return within(gallery()).getByRole('img');
}

function thumb(position: number, of: number) {
  return within(gallery()).getByRole('button', { name: `Show image ${position} of ${of}` });
}

describe('Product detail page', () => {
  it('asks for the details of the product in the URL and titles the page with its name', async () => {
    const fetchMock = serve(makeDetail({ id: 7, name: 'Golf Putter' }));
    renderDetail('/products/7');

    expect(await screen.findByRole('heading', { level: 1, name: 'Golf Putter' })).toBeInTheDocument();
    expect(requestedUrls(fetchMock)).toEqual(['/api/products/7/details']);
    await waitFor(() => expect(document.title).toBe('Golf Putter · MicroShop'));
  });

  it('shows a loading message first', async () => {
    serve(makeDetail());
    renderDetail('/products/1');

    expect(screen.getByRole('status')).toHaveTextContent('Loading product…');
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByText('Loading product…')).not.toBeInTheDocument();
  });

  it('shows brand, name, category, a copyable SKU, warranty and seller', async () => {
    serve(
      makeDetail({
        name: 'Alpha Phone',
        brand: 'Alpha',
        category: 'Electronics',
        warranty: '2 years',
        seller: 'Alpha Retail Ltd',
      }),
    );
    renderDetail('/products/1');

    expect(await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' })).toBeInTheDocument();
    expect(screen.getByText('Alpha')).toHaveClass('detail-brand');
    expect(screen.getByText('Electronics')).toBeInTheDocument();
    const sku = screen.getByRole('button', { name: 'ALPHA-1' });
    expect(sku).toHaveClass('copy', 'mono');
    expect(sku).toHaveAttribute('title', 'Copy SKU');

    expect(screen.getByText('Warranty').nextElementSibling).toHaveTextContent('2 years');
    expect(screen.getByText('Seller').nextElementSibling).toHaveTextContent('Alpha Retail Ltd');
  });

  it('puts warranty and seller right under the stock line, before the highlights', async () => {
    serve(makeDetail({ warranty: '2 years', seller: 'Alpha Retail Ltd', highlights: ['Fast chip'] }));
    renderDetail('/products/1');

    const stockLine = await screen.findByText(/In stock|Only \d+ left|Out of stock|Stock unavailable/);
    const facts = screen.getByText('Warranty').closest('dl')!;
    const highlights = screen.getByRole('heading', { level: 2, name: 'Highlights' });
    expect(stockLine.compareDocumentPosition(facts) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(facts.compareDocumentPosition(highlights) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(facts).toHaveClass('detail-facts');
  });

  it('leaves out the brand, category, warranty and seller when there are none', async () => {
    serve(makeDetail({ category: undefined }));
    renderDetail('/products/1');

    await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });
    expect(document.querySelector('.detail-brand')).toBeNull();
    expect(screen.queryByText('Warranty')).not.toBeInTheDocument();
    expect(screen.queryByText('Seller')).not.toBeInTheDocument();
    expect(screen.queryByText('Electronics')).not.toBeInTheDocument();
  });

  it('links back to the product list from a breadcrumb', async () => {
    serve(makeDetail());
    const user = userEvent.setup();
    renderDetail('/products/1');

    const breadcrumb = await screen.findByRole('navigation', { name: 'Breadcrumb' });
    expect(within(breadcrumb).getByText('Alpha Phone')).toHaveAttribute('aria-current', 'page');
    await user.click(within(breadcrumb).getByRole('link', { name: 'Products' }));
    expect(await screen.findByText('The product list')).toBeInTheDocument();
  });

  describe('gallery', () => {
    it('shows the first image large, with a thumbnail for each image', async () => {
      serve(makeDetail({ images }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(mainImage()).toHaveAttribute('src', images[0].url);
      expect(mainImage()).toHaveAttribute('alt', 'Front view');
      expect(within(gallery()).getAllByRole('button')).toHaveLength(3);
      expect(thumb(1, 3)).toHaveAttribute('aria-current', 'true');
      expect(thumb(2, 3)).not.toHaveAttribute('aria-current');
      expect(thumb(3, 3)).not.toHaveAttribute('aria-current');
    });

    it('changes the main image when a thumbnail is clicked', async () => {
      serve(makeDetail({ images }));
      const user = userEvent.setup();
      renderDetail('/products/1');
      await screen.findByRole('heading', { level: 1 });

      await user.click(thumb(2, 3));
      expect(mainImage()).toHaveAttribute('src', images[1].url);
      expect(mainImage()).toHaveAttribute('alt', 'Side view');
      expect(thumb(2, 3)).toHaveAttribute('aria-current', 'true');
      expect(thumb(1, 3)).not.toHaveAttribute('aria-current');

      await user.click(thumb(3, 3));
      expect(mainImage()).toHaveAttribute('src', images[2].url);
      expect(mainImage()).toHaveAttribute('alt', 'Alpha Phone');
    });

    it('moves between images with the arrow keys and keeps focus on the selected thumbnail', async () => {
      serve(makeDetail({ images }));
      const user = userEvent.setup();
      renderDetail('/products/1');
      await screen.findByRole('heading', { level: 1 });

      await user.click(thumb(1, 3));
      await user.keyboard('{ArrowRight}');
      expect(mainImage()).toHaveAttribute('src', images[1].url);
      expect(thumb(2, 3)).toHaveAttribute('aria-current', 'true');
      expect(thumb(2, 3)).toHaveFocus();

      await user.keyboard('{ArrowRight}');
      expect(mainImage()).toHaveAttribute('src', images[2].url);
      expect(thumb(3, 3)).toHaveFocus();

      await user.keyboard('{ArrowLeft}');
      expect(mainImage()).toHaveAttribute('src', images[1].url);
      expect(thumb(2, 3)).toHaveFocus();
    });

    it('wraps around at either end', async () => {
      serve(makeDetail({ images }));
      const user = userEvent.setup();
      renderDetail('/products/1');
      await screen.findByRole('heading', { level: 1 });

      await user.click(thumb(1, 3));
      await user.keyboard('{ArrowLeft}');
      expect(thumb(3, 3)).toHaveAttribute('aria-current', 'true');
      await user.keyboard('{ArrowRight}');
      expect(thumb(1, 3)).toHaveAttribute('aria-current', 'true');
    });

    it('leaves Alt+Arrow (browser back and forward) alone', async () => {
      serve(makeDetail({ images }));
      const user = userEvent.setup();
      renderDetail('/products/1');
      await screen.findByRole('heading', { level: 1 });

      await user.click(thumb(1, 3));
      await user.keyboard('{Alt>}{ArrowRight}{/Alt}');
      expect(thumb(1, 3)).toHaveAttribute('aria-current', 'true');
    });

    it('shows the fallback tile when there are no images', async () => {
      serve(makeDetail({ images: [] }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(within(gallery()).queryByRole('button')).not.toBeInTheDocument();
      expect(gallery().querySelector('img')).toBeNull();
      const tile = within(gallery()).getByRole('img', { name: 'Alpha Phone' });
      expect(tile).toHaveClass('picture-fallback');
      expect(tile).toHaveTextContent('AP');
    });

    it('does not show a thumbnail strip for a single image', async () => {
      serve(makeDetail({ images: [images[0]] }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(mainImage()).toHaveAttribute('src', images[0].url);
      expect(within(gallery()).queryByRole('button')).not.toBeInTheDocument();
    });

    it('shows the fallback for an image that fails to load and for that image only', async () => {
      serve(makeDetail({ images }));
      const user = userEvent.setup();
      renderDetail('/products/1');
      await screen.findByRole('heading', { level: 1 });

      fireEvent.error(mainImage());
      expect(within(gallery()).getByRole('img', { name: 'Front view' })).toHaveClass('picture-fallback');
      expect(within(gallery()).getByRole('img', { name: 'Front view' })).toHaveTextContent('AP');

      // The other images are fine: the thumbnails still show, and so does the next main image.
      expect(gallery().querySelectorAll('.thumb img')).toHaveLength(3);
      await user.click(thumb(2, 3));
      expect(mainImage()).toHaveAttribute('src', images[1].url);
    });

    it('never renders an image URL that is not http or https', async () => {
      serve(makeDetail({ images: [{ url: 'javascript:alert(1)' }, { url: 'data:image/png;base64,AAAA' }] }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(gallery().querySelector('img')).toBeNull();
      expect(within(gallery()).getByRole('img', { name: 'Alpha Phone' })).toHaveClass('picture-fallback');
    });
  });

  describe('price', () => {
    it('shows the MRP struck through and the saving when the MRP is above the price', async () => {
      serve(makeDetail({ price: 799.99, mrp: 999.99, discountPercent: 20 }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(screen.getByText('$799.99')).toHaveClass('price-now');
      expect(screen.getByText('$999.99').tagName).toBe('S');
      expect(screen.getByText('You save $200.00 (20% off)')).toBeInTheDocument();
    });

    it('works out the percentage itself when the server sent none', async () => {
      serve(makeDetail({ price: 75, mrp: 100 }));
      renderDetail('/products/1');

      expect(await screen.findByText('You save $25.00 (25% off)')).toBeInTheDocument();
    });

    it.each([
      ['no MRP', undefined],
      ['an MRP equal to the price', 799.99],
      ['an MRP below the price', 500],
    ])('shows only the price with %s', async (_label, mrp) => {
      serve(makeDetail({ price: 799.99, mrp }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(screen.getByText('$799.99')).toHaveClass('price-now');
      expect(document.querySelector('s')).toBeNull();
      expect(screen.queryByText(/You save/)).not.toBeInTheDocument();
    });
  });

  describe('stock line', () => {
    it.each([
      [42, 'In stock (42)', 'ok'],
      [21, 'In stock (21)', 'ok'],
      [20, 'Only 20 left', 'low'],
      [7, 'Only 7 left', 'low'],
      [1, 'Only 1 left', 'low'],
      [0, 'Out of stock', 'out'],
      [null, 'Stock unavailable', 'unknown'],
      [undefined, 'Stock unavailable', 'unknown'],
    ])('quantity %s reads "%s"', async (stockQuantity, text, level) => {
      serve(makeDetail({ stockQuantity }));
      renderDetail('/products/1');

      expect(await screen.findByText(text)).toHaveClass('stock-line', `stock-line--${level}`);
    });
  });

  describe('sections', () => {
    it('lists the highlights', async () => {
      serve(makeDetail({ highlights: ['6.1 inch display', '2 day battery', '  '] }));
      renderDetail('/products/1');

      const section = (await screen.findByRole('heading', { level: 2, name: 'Highlights' })).closest('section')!;
      const items = within(section).getAllByRole('listitem');
      expect(items.map((item) => item.textContent)).toEqual(['6.1 inch display', '2 day battery']);
    });

    it('shows the description as plain text with its line breaks kept', async () => {
      const description = 'First paragraph.\n\nSecond line with <b>markup</b> & an ampersand.';
      serve(makeDetail({ description }));
      renderDetail('/products/1');

      const section = (await screen.findByRole('heading', { level: 2, name: 'Description' })).closest('section')!;
      const text = section.querySelector('p')!;
      expect(text).toHaveClass('detail-description');
      // Verbatim, newlines included. The line breaks are shown by `white-space: pre-line` on
      // .detail-description in app.css (jsdom does not load the stylesheet, so not checked here).
      expect(text.textContent).toBe(description);
      expect(text.querySelector('b')).toBeNull(); // markup is text, not HTML
      expect(text.innerHTML).toContain('&lt;b&gt;markup&lt;/b&gt;');
    });

    it('shows one table per specification group, with the key as the row header', async () => {
      serve(
        makeDetail({
          specifications: [
            {
              group: 'General',
              items: [
                { key: 'Model', value: 'AP-1' },
                { key: 'Colour', value: 'Graphite' },
              ],
            },
            { group: 'Display', items: [{ key: 'Size', value: '6.1 inch' }] },
            { group: 'Empty group', items: [] },
          ],
        }),
      );
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 2, name: 'Specifications' });
      expect(screen.getAllByRole('table')).toHaveLength(2);

      const general = screen.getByRole('table', { name: 'General' });
      const rows = within(general).getAllByRole('row');
      expect(rows).toHaveLength(2);
      expect(within(rows[0]).getByRole('rowheader', { name: 'Model' })).toHaveAttribute('scope', 'row');
      expect(within(rows[0]).getByRole('cell')).toHaveTextContent('AP-1');
      expect(within(rows[1]).getByRole('rowheader', { name: 'Colour' })).toBeInTheDocument();
      expect(within(rows[1]).getByRole('cell')).toHaveTextContent('Graphite');

      const display = screen.getByRole('table', { name: 'Display' });
      expect(within(display).getByRole('rowheader', { name: 'Size' })).toBeInTheDocument();
      expect(within(display).getByRole('cell')).toHaveTextContent('6.1 inch');
      expect(screen.queryByRole('table', { name: 'Empty group' })).not.toBeInTheDocument();
    });

    it('leaves out sections that have nothing to show', async () => {
      serve(makeDetail({ description: undefined, highlights: [], specifications: [] }));
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1 });
      expect(screen.queryByRole('heading', { level: 2, name: 'Highlights' })).not.toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: 'Description' })).not.toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: 'Specifications' })).not.toBeInTheDocument();
    });

    it('copes with lists missing from the response', async () => {
      const partial = { ...makeDetail() } as Partial<ProductDetailData>;
      delete partial.images;
      delete partial.highlights;
      delete partial.specifications;
      mockFetch({ 'GET /api/products/1/details': () => partial });
      renderDetail('/products/1');

      expect(await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' })).toBeInTheDocument();
      expect(within(gallery()).getByRole('img', { name: 'Alpha Phone' })).toHaveClass('picture-fallback');
    });
  });

  describe('when something is wrong', () => {
    it('says the product does not exist when the API answers 404', async () => {
      mockFetch({ 'GET /api/products/9/details': () => problem(404, 'Not Found', 'Product with id 9 not found') });
      renderDetail('/products/9');

      expect(await screen.findByRole('heading', { level: 1, name: 'Product not found' })).toBeInTheDocument();
      expect(screen.getByText("This product doesn't exist. It may have been removed.")).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to products' })).toHaveAttribute('href', '/products');
      expect(screen.getByRole('link', { name: 'Back to products' })).toHaveClass('btn', 'btn--buy');
      // A second way on, so the person is never stuck here.
      expect(screen.getByRole('link', { name: 'Go to home page' })).toHaveAttribute('href', '/');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      await waitFor(() => expect(document.title).toBe('Product not found · MicroShop'));
    });

    it('treats an id that is not a number as not found, without calling the API', async () => {
      const fetchMock = mockFetch({});
      renderDetail('/products/abc');

      expect(await screen.findByRole('heading', { level: 1, name: 'Product not found' })).toBeInTheDocument();
      expect(screen.getByText("This product doesn't exist. It may have been removed.")).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to products' })).toHaveAttribute('href', '/products');
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it.each(['0', '-3', '1.5', '12abc'])('treats the id "%s" as not found too', async (id) => {
      const fetchMock = mockFetch({});
      renderDetail(`/products/${id}`);

      expect(await screen.findByRole('heading', { level: 1, name: 'Product not found' })).toBeInTheDocument();
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('shows the reason and a retry for any other failure', async () => {
      let attempts = 0;
      mockFetch({
        'GET /api/products/1/details': () => {
          attempts += 1;
          return attempts === 1 ? problem(500, 'Internal Server Error', 'database is down') : makeDetail();
        },
      });
      const user = userEvent.setup();
      renderDetail('/products/1');

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the product. database is down.');
      expect(screen.queryByText("This product doesn't exist. It may have been removed.")).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to products' })).toHaveAttribute('href', '/products');

      await user.click(screen.getByRole('button', { name: 'Try again' }));
      expect(await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('says so when the server cannot be reached at all', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
      renderDetail('/products/1');

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the product. Could not reach the server.');
    });
  });
});

describe('Product detail page: ordering', () => {
  const catalog = { 'ALPHA-1': { name: 'Alpha Phone', price: 799.99 } };
  const detail = makeDetail({ id: 1, name: 'Alpha Phone', skuCode: 'ALPHA-1' });

  /** The product page plus a pretend cart-service holding `initial` (SKU to quantity). */
  function serve(initial: Record<string, number> = {}) {
    const server = fakeCart(catalog, initial);
    const fetchMock = mockFetch({ 'GET /api/products/1/details': () => detail, ...server.routes });
    return { server, fetchMock };
  }

  const quantityBox = () => screen.getByRole('spinbutton', { name: 'Quantity' });
  const addButton = () => screen.getByRole('button', { name: /^(Add to cart|Adding…)$/ });

  /** Waits until the page and the cart have loaded, so the button is ready to use. */
  async function ready() {
    await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });
    await waitFor(() => expect(addButton()).toBeEnabled());
  }

  describe('a visitor', () => {
    it('is offered a link to log in, and no way to add to a cart', async () => {
      const { fetchMock } = serve();
      renderDetail('/products/1');

      await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });
      expect(screen.getByRole('link', { name: 'Log in to order' })).toHaveAttribute('href', '/login');
      expect(screen.queryByRole('button', { name: 'Add to cart' })).not.toBeInTheDocument();
      expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
      expect(requestedUrls(fetchMock)).toEqual(['/api/products/1/details']);
    });
  });

  describe.each<Role>(['USER', 'ADMIN'])('a logged-in %s', (role) => {
    it('sees a quantity box that starts at 1 and an Add to cart button, not the login link', async () => {
      serve();
      renderDetail('/products/1', role);
      await ready();

      expect(quantityBox()).toHaveValue(1);
      expect(quantityBox()).toHaveAttribute('min', '1');
      expect(quantityBox()).toHaveAttribute('max', '99');
      expect(screen.queryByRole('link', { name: 'Log in to order' })).not.toBeInTheDocument();
    });
  });

  it('adds one to the cart and says so', async () => {
    const { server, fetchMock } = serve();
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.click(addButton());

    expect(await screen.findByText('Added 1 to your cart.')).toBeInTheDocument();
    expect(server.quantities.get('ALPHA-1')).toBe(1);
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    expect(put[0]).toBe('/api/cart/items/ALPHA-1');
    expect(JSON.parse(String(put[1]?.body))).toEqual({ quantity: 1 });
    expect(put[1]?.headers).toMatchObject({ Authorization: 'Bearer user-token' });
    expect(screen.getByRole('link', { name: 'View cart' })).toHaveAttribute('href', '/cart');
  });

  it('adds the number in the box', async () => {
    const { server } = serve();
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.clear(quantityBox());
    await user.type(quantityBox(), '3');
    await user.click(addButton());

    expect(await screen.findByText('Added 3 to your cart.')).toBeInTheDocument();
    expect(server.quantities.get('ALPHA-1')).toBe(3);
  });

  it('adds to the quantity already in the cart instead of replacing it', async () => {
    const { server, fetchMock } = serve({ 'ALPHA-1': 3 });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.clear(quantityBox());
    await user.type(quantityBox(), '2');
    await user.click(addButton());

    expect(await screen.findByText('Added 2 to your cart.')).toBeInTheDocument();
    expect(server.quantities.get('ALPHA-1')).toBe(5);
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    expect(JSON.parse(String(put[1]?.body))).toEqual({ quantity: 5 });
  });

  it('adds again on a second click, counting what the first click added', async () => {
    const { server } = serve();
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.click(addButton());
    await screen.findByText('Added 1 to your cart.');
    await waitFor(() => expect(addButton()).toBeEnabled());
    await user.click(addButton());

    await waitFor(() => expect(server.quantities.get('ALPHA-1')).toBe(2));
  });

  it('is disabled while the request runs, so a double click adds once', async () => {
    const gate = deferred();
    const server = fakeCart(catalog);
    const put = server.routes['PUT /api/cart/items/ALPHA-1'];
    const fetchMock = mockFetch({
      'GET /api/products/1/details': () => detail,
      ...server.routes,
      'PUT /api/cart/items/ALPHA-1': async (url, init) => {
        await gate.promise;
        return put(url, init);
      },
    });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.click(addButton());

    expect(screen.getByRole('button', { name: 'Adding…' })).toBeDisabled();
    expect(quantityBox()).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Adding…' }));
    gate.resolve();

    expect(await screen.findByText('Added 1 to your cart.')).toBeInTheDocument();
    expect(addButton()).toHaveTextContent('Add to cart');
    expect(addButton()).toBeEnabled();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });

  it.each([
    ['0', '0'],
    ['100', '100'],
    ['-1', '-1'],
    ['1.5', '1.5'],
    ['nothing', ''],
  ])('does not send a quantity of %s, and asks for a whole number from 1 to 99', async (_name, typed) => {
    const { fetchMock } = serve();
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.clear(quantityBox());
    if (typed) await user.type(quantityBox(), typed);
    await user.click(addButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a whole number from 1 to 99.');
    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveAttribute('aria-invalid', 'true');
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
    expect(screen.queryByText(/Added/)).not.toBeInTheDocument();
  });

  it('does not let the cart go over 99 of one product, and says why', async () => {
    const { fetchMock } = serve({ 'ALPHA-1': 98 });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.clear(quantityBox());
    await user.type(quantityBox(), '5');
    await user.click(addButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('You already have 98 in your cart. The most you can have is 99.');
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
  });

  it('shows the reason when the server refuses, and no confirmation', async () => {
    const server = fakeCart(catalog);
    mockFetch({
      'GET /api/products/1/details': () => detail,
      ...server.routes,
      'PUT /api/cart/items/ALPHA-1': () => problem(422, 'Unprocessable Entity', 'Unknown product: ALPHA-1'),
    });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.click(addButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('Unknown product: ALPHA-1');
    expect(screen.queryByText(/Added/)).not.toBeInTheDocument();
    expect(addButton()).toBeEnabled();
  });

  it('shows the reason when the cart service is down', async () => {
    const server = fakeCart(catalog);
    mockFetch({
      'GET /api/products/1/details': () => detail,
      ...server.routes,
      'PUT /api/cart/items/ALPHA-1': () => problem(503, 'Service Unavailable', 'The product service did not answer. Please try again.'),
    });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();

    await user.click(addButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('The product service did not answer. Please try again.');
  });

  it('removes an old error when the next try works', async () => {
    const server = fakeCart(catalog);
    let fail = true;
    const put = server.routes['PUT /api/cart/items/ALPHA-1'];
    mockFetch({
      'GET /api/products/1/details': () => detail,
      ...server.routes,
      'PUT /api/cart/items/ALPHA-1': (url, init) => (fail ? problem(503, 'Service Unavailable', 'Try again later') : put(url, init)),
    });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');
    await ready();
    await user.click(addButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again later');

    fail = false;
    await user.click(addButton());

    expect(await screen.findByText('Added 1 to your cart.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps Add to cart off until the cart has loaded, so it never replaces a quantity it has not seen', async () => {
    const gate = deferred();
    const server = fakeCart(catalog, { 'ALPHA-1': 4 });
    const cartRoute = server.routes['GET /api/cart'];
    mockFetch({
      'GET /api/products/1/details': () => detail,
      ...server.routes,
      'GET /api/cart': async (url, init) => {
        await gate.promise;
        return cartRoute(url, init);
      },
    });
    renderDetail('/products/1', 'USER');
    await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });

    expect(addButton()).toBeDisabled();
    gate.resolve();
    await waitFor(() => expect(addButton()).toBeEnabled());
  });

  it('says so, and keeps Add to cart off, when the cart could not be loaded', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 4 });
    let up = false;
    const cartRoute = server.routes['GET /api/cart'];
    const fetchMock = mockFetch({
      'GET /api/products/1/details': () => detail,
      ...server.routes,
      'GET /api/cart': (url, init) => (up ? cartRoute(url, init) : problem(503, 'Service Unavailable', 'The product service did not answer. Please try again.')),
    });
    const user = userEvent.setup();
    renderDetail('/products/1', 'USER');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not load your cart. The product service did not answer. Please try again.',
    );
    expect(addButton()).toBeDisabled();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);

    up = true;
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(addButton()).toBeEnabled());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Product detail page: storefront layout', () => {
  const catalog = { 'ALPHA-1': { name: 'Alpha Phone', price: 799.99 } };

  /** The product page, with a cart page and a home page to land on. */
  function renderShop(detail: ProductDetailData, auth?: Role, initial: Record<string, number> = {}) {
    const server = fakeCart(catalog, initial);
    const fetchMock = mockFetch({ [`GET /api/products/${detail.id}/details`]: () => detail, ...server.routes });
    renderAt(
      <Routes>
        <Route path="/products/:id" element={<ProductDetail />} />
        <Route path="/products" element={<p>The product list</p>} />
        <Route path="/cart" element={<p>The cart page</p>} />
        <Route path="/login" element={<p>The login page</p>} />
        <Route path="/" element={<p>The home page</p>} />
      </Routes>,
      `/products/${detail.id}`,
      { auth },
    );
    return { server, fetchMock };
  }

  const detail = makeDetail({ id: 1, name: 'Alpha Phone', skuCode: 'ALPHA-1', category: 'Electronics', images });
  const buyButton = () => screen.getByRole('button', { name: /^(Buy now|Opening cart…)$/ });
  const addButton = () => screen.getByRole('button', { name: /^(Add to cart|Adding…)$/ });
  const ready = async () => {
    await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });
    await waitFor(() => expect(addButton()).toBeEnabled());
  };

  describe('the breadcrumb', () => {
    it('goes Home, Products, the category, then the product name', async () => {
      renderShop(detail);

      const crumbs = await screen.findByRole('navigation', { name: 'Breadcrumb' });
      const links = within(crumbs).getAllByRole('link');
      expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
        ['Home', '/'],
        ['Products', '/products'],
        ['Electronics', '/products?category=Electronics'],
      ]);
      expect(within(crumbs).getByText('Alpha Phone')).toHaveAttribute('aria-current', 'page');
    });

    it('leaves the category out when the product has none', async () => {
      renderShop(makeDetail({ id: 1, category: undefined }));

      const crumbs = await screen.findByRole('navigation', { name: 'Breadcrumb' });
      expect(within(crumbs).getAllByRole('link').map((link) => link.textContent)).toEqual(['Home', 'Products']);
    });

    it('takes the person home from the first link', async () => {
      const user = userEvent.setup();
      renderShop(detail);

      await user.click(within(await screen.findByRole('navigation', { name: 'Breadcrumb' })).getByRole('link', { name: 'Home' }));

      expect(await screen.findByText('The home page')).toBeInTheDocument();
    });
  });

  describe('a visitor', () => {
    it('gets one big "Log in to order" button instead of the two buying buttons', async () => {
      renderShop(detail);

      const login = await screen.findByRole('link', { name: 'Log in to order' });
      expect(login).toHaveAttribute('href', '/login');
      expect(login).toHaveClass('btn');
      expect(screen.queryByRole('button', { name: 'Buy now' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Add to cart' })).not.toBeInTheDocument();
    });

    it('is sent back to this product after logging in', async () => {
      const user = userEvent.setup();
      renderShop(detail);

      await user.click(await screen.findByRole('link', { name: 'Log in to order' }));

      expect(await screen.findByText('The login page')).toBeInTheDocument();
    });

    it('can still switch pictures in the gallery', async () => {
      const user = userEvent.setup();
      renderShop(detail);
      await screen.findByRole('heading', { level: 1 });

      await user.click(thumb(2, 3));

      expect(mainImage()).toHaveAttribute('src', images[1].url);
    });
  });

  describe('the two big buttons', () => {
    it('sit below the gallery, outside the group of pictures, with Add to cart before Buy now', async () => {
      renderShop(detail, 'USER');
      await ready();

      expect(gallery()).not.toContainElement(addButton());
      expect(gallery()).not.toContainElement(buyButton());
      expect(addButton()).toHaveClass('btn--cart');
      expect(buyButton()).toHaveClass('btn--buy');
      expect(addButton().compareDocumentPosition(buyButton()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('"Buy now" puts the chosen quantity in the cart and opens the cart page', async () => {
      const user = userEvent.setup();
      const { server, fetchMock } = renderShop(detail, 'USER');
      await ready();

      await user.clear(screen.getByRole('spinbutton', { name: 'Quantity' }));
      await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '3');
      await user.click(buyButton());

      expect(await screen.findByText('The cart page')).toBeInTheDocument();
      expect(server.quantities.get('ALPHA-1')).toBe(3);
      const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!;
      expect(put[0]).toBe('/api/cart/items/ALPHA-1');
      expect(JSON.parse(String(put[1]?.body))).toEqual({ quantity: 3 });
    });

    it('"Buy now" sets the quantity to the chosen number, it does not add to what the cart already has', async () => {
      const user = userEvent.setup();
      const { server } = renderShop(detail, 'USER', { 'ALPHA-1': 5 });
      await ready();

      await user.click(buyButton());

      expect(await screen.findByText('The cart page')).toBeInTheDocument();
      expect(server.quantities.get('ALPHA-1')).toBe(1);
    });

    it('"Add to cart" still adds to what the cart already has, and stays on the page', async () => {
      const user = userEvent.setup();
      const { server } = renderShop(detail, 'USER', { 'ALPHA-1': 5 });
      await ready();

      await user.click(addButton());

      expect(await screen.findByText('Added 1 to your cart.')).toBeInTheDocument();
      expect(server.quantities.get('ALPHA-1')).toBe(6);
      expect(screen.queryByText('The cart page')).not.toBeInTheDocument();
    });

    it('turns both buttons and the quantity box off while one of them is working', async () => {
      const gate = deferred();
      const server = fakeCart(catalog);
      const put = server.routes['PUT /api/cart/items/ALPHA-1'];
      mockFetch({
        'GET /api/products/1/details': () => detail,
        ...server.routes,
        'PUT /api/cart/items/ALPHA-1': async (url, init) => {
          await gate.promise;
          return put(url, init);
        },
      });
      const user = userEvent.setup();
      renderAt(
        <Routes>
          <Route path="/products/:id" element={<ProductDetail />} />
          <Route path="/cart" element={<p>The cart page</p>} />
        </Routes>,
        '/products/1',
        { auth: 'USER' },
      );
      await ready();

      await user.click(buyButton());

      expect(screen.getByRole('button', { name: 'Opening cart…' })).toBeDisabled();
      expect(addButton()).toBeDisabled();
      expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toBeDisabled();
      gate.resolve();
      expect(await screen.findByText('The cart page')).toBeInTheDocument();
    });

    it('"Buy now" does not send a quantity the server would refuse, and says what is allowed', async () => {
      const user = userEvent.setup();
      const { fetchMock } = renderShop(detail, 'USER');
      await ready();

      await user.clear(screen.getByRole('spinbutton', { name: 'Quantity' }));
      await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '100');
      await user.click(buyButton());

      expect(await screen.findByRole('alert')).toHaveTextContent('Enter a whole number from 1 to 99.');
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
      expect(screen.queryByText('The cart page')).not.toBeInTheDocument();
    });

    it('"Buy now" stays on the page and shows the reason when the server refuses', async () => {
      const server = fakeCart(catalog);
      mockFetch({
        'GET /api/products/1/details': () => detail,
        ...server.routes,
        'PUT /api/cart/items/ALPHA-1': () => problem(503, 'Service Unavailable', 'The product service did not answer. Please try again.'),
      });
      const user = userEvent.setup();
      renderAt(
        <Routes>
          <Route path="/products/:id" element={<ProductDetail />} />
          <Route path="/cart" element={<p>The cart page</p>} />
        </Routes>,
        '/products/1',
        { auth: 'USER' },
      );
      await ready();

      await user.click(buyButton());

      expect(await screen.findByRole('alert')).toHaveTextContent('The product service did not answer. Please try again.');
      expect(screen.queryByText('The cart page')).not.toBeInTheDocument();
      expect(buyButton()).toBeEnabled();
    });

    it('shows the trace id of a failed request as a reference', async () => {
      const server = fakeCart(catalog);
      mockFetch({
        'GET /api/products/1/details': () => detail,
        ...server.routes,
        'PUT /api/cart/items/ALPHA-1': () =>
          new Response(JSON.stringify({ title: 'Service Unavailable', status: 503, detail: 'Try again later' }), {
            status: 503,
            headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'trace-42' },
          }),
      });
      const user = userEvent.setup();
      renderAt(
        <Routes>
          <Route path="/products/:id" element={<ProductDetail />} />
        </Routes>,
        '/products/1',
        { auth: 'USER' },
      );
      await ready();

      await user.click(addButton());

      expect(await screen.findByRole('alert')).toHaveTextContent('Try again later. Reference: trace-42');
    });
  });

  describe('when the product is out of stock', () => {
    const soldOut = makeDetail({ id: 1, name: 'Alpha Phone', skuCode: 'ALPHA-1', stockQuantity: 0 });

    it('says so in red and turns both buttons and the quantity box off', async () => {
      renderShop(soldOut, 'USER');
      await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });

      expect(await screen.findByText('Out of stock')).toHaveClass('stock-line--out');
      await waitFor(() => expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toBeDisabled());
      expect(addButton()).toBeDisabled();
      expect(buyButton()).toBeDisabled();
    });

    it('does not send anything when a disabled button is clicked', async () => {
      const user = userEvent.setup();
      const { fetchMock } = renderShop(soldOut, 'USER');
      await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });

      await user.click(buyButton());
      await user.click(addButton());

      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
    });

    it('still asks a visitor to log in', async () => {
      renderShop(soldOut);
      expect(await screen.findByRole('link', { name: 'Log in to order' })).toBeInTheDocument();
    });

    it('keeps the buttons on for a product that is low but not gone, and shows the orange line', async () => {
      renderShop(makeDetail({ id: 1, name: 'Alpha Phone', skuCode: 'ALPHA-1', stockQuantity: 3 }), 'USER');
      await ready();

      expect(screen.getByText('Only 3 left')).toHaveClass('stock-line--low');
      expect(buyButton()).toBeEnabled();
    });

    it('keeps the buttons on when the stock is not known', async () => {
      renderShop(makeDetail({ id: 1, name: 'Alpha Phone', skuCode: 'ALPHA-1', stockQuantity: null }), 'USER');
      await ready();

      expect(buyButton()).toBeEnabled();
    });
  });
});
