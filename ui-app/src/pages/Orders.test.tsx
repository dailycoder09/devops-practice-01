import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import type { Order, Role } from '../api/types';
import { formatDateTime } from '../lib/format';
import { Login } from './Login';
import { Orders } from './Orders';
import { deferred, makeOrder, makeProduct, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

const CLEAR_WARNING = 'Your order is confirmed. We could not empty your cart, please remove the items yourself before ordering again.';

/** Logged in as a user unless `null` is passed (a visitor). */
function renderOrders(path: Parameters<typeof renderAt>[1] = '/orders', auth: Role | null = 'USER') {
  return renderAt(
    <Routes>
      <Route path="/orders" element={<Orders />} />
      <Route path="/login" element={<Login />} />
      <Route path="/products" element={<p>The product list</p>} />
    </Routes>,
    path,
    { auth: auth ?? undefined },
  );
}

/** Two lines, so the order total differs from every line total. */
const twoLines = (id: number, overrides: Partial<Order> = {}) =>
  makeOrder(id, {
    total: 1619.98,
    items: [
      { sku: 'ALPHA-1', name: 'Alpha Phone', unitPrice: 799.99, quantity: 2, lineTotal: 1599.98 },
      { sku: 'BETA-2', name: 'Beta Lamp', unitPrice: 20, quantity: 1, lineTotal: 20 },
    ],
    ...overrides,
  });

/** What was asked of order-service (the cart provider also asks for the cart, which is not the point here). */
const ordersRequests = (fetchMock: ReturnType<typeof mockFetch>) => requestedUrls(fetchMock).filter((url) => url.startsWith('/api/orders'));

const orderCard = (id: number) => screen.getByRole('heading', { level: 2, name: `Order #${id}` }).closest('article')!;

describe('Orders page', () => {
  describe('for a visitor', () => {
    it('goes to the login page without asking for orders, and comes back after logging in', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders': () => pageOf([twoLines(5)]),
        'POST /api/users/login': () => ({ token: 'jwt-abc', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' }),
      });
      const user = userEvent.setup();
      renderOrders('/orders', null);

      expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
      expect(ordersRequests(fetchMock)).toEqual([]);

      await user.type(screen.getByLabelText('Email'), 'jane@example.com');
      await user.type(screen.getByLabelText('Password'), 'secret123');
      await user.click(screen.getByRole('button', { name: 'Log in' }));

      expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument();
      expect(await screen.findByRole('heading', { level: 2, name: 'Order #5' })).toBeInTheDocument();
    });
  });

  describe('loading', () => {
    it('asks for the first page of the caller\'s orders with their token, and says it is loading', async () => {
      const gate = deferred();
      const fetchMock = mockFetch({
        'GET /api/orders': async () => {
          await gate.promise;
          return pageOf([twoLines(5)]);
        },
      });
      renderOrders();

      expect(screen.getByText('Loading your orders…')).toBeInTheDocument();
      expect(screen.queryByText('You have not placed any orders yet.')).not.toBeInTheDocument();
      gate.resolve();
      expect(await screen.findByRole('heading', { level: 2, name: 'Order #5' })).toBeInTheDocument();
      expect(screen.queryByText('Loading your orders…')).not.toBeInTheDocument();
      const call = fetchMock.mock.calls.find(([path]) => String(path).startsWith('/api/orders'))!;
      expect(call[0]).toBe('/api/orders?page=0&size=20');
      expect(call[1]?.headers).toMatchObject({ Authorization: 'Bearer user-token' });
    });

    it('titles the page', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([]) });
      renderOrders();
      await screen.findByText('You have not placed any orders yet.');
      await waitFor(() => expect(document.title).toBe('My orders · MicroShop'));
    });

    it('says what went wrong when the orders cannot be loaded, and loads them on Try again', async () => {
      let attempts = 0;
      mockFetch({
        'GET /api/orders': () => {
          attempts += 1;
          return attempts === 1 ? problem(503, 'Service Unavailable', 'The order service is busy') : pageOf([twoLines(5)]);
        },
      });
      const user = userEvent.setup();
      renderOrders();

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your orders. The order service is busy.');
      expect(screen.queryByText('You have not placed any orders yet.')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByRole('heading', { level: 2, name: 'Order #5' })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('sends the person to the login page when the token is no longer accepted', async () => {
      mockFetch({ 'GET /api/orders': () => problem(401, 'Unauthorized') });
      renderOrders();

      expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');
    });
  });

  describe('no orders', () => {
    it('says so in plain words and links to the products', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([]) });
      renderOrders();

      expect(await screen.findByText('You have not placed any orders yet.')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/products');
      expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
    });

    it('has a quiet picture above the words, hidden from screen readers', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([]) });
      renderOrders();

      const message = await screen.findByText('You have not placed any orders yet.');
      const icon = message.closest('.empty')!.querySelector('svg')!;
      expect(icon).toHaveClass('empty-icon');
      expect(icon).toHaveAttribute('aria-hidden', 'true');
    });
  });

  describe('the list', () => {
    it('shows each order with its number, date, status, total and lines', async () => {
      const order = twoLines(12, { createdAt: '2026-11-19T10:15:00Z' });
      mockFetch({ 'GET /api/orders': () => pageOf([order]) });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #12' });

      const card = within(orderCard(12));
      expect(card.getByText(formatDateTime('2026-11-19T10:15:00Z'))).toBeInTheDocument();
      expect(card.getByText('Confirmed')).toBeInTheDocument();
      expect(card.getByText('Total').nextElementSibling).toHaveTextContent('$1,619.98');

      const alpha = within(card.getByText('Alpha Phone').closest('li')!);
      expect(alpha.getByText('ALPHA-1')).toBeInTheDocument();
      expect(alpha.getByText('$799.99')).toBeInTheDocument();
      expect(alpha.getByText('2')).toBeInTheDocument();
      expect(alpha.getByText('$1,599.98')).toBeInTheDocument();
      const beta = within(card.getByText('Beta Lamp').closest('li')!);
      expect(beta.getByText('BETA-2')).toBeInTheDocument();
      expect(beta.getAllByText('$20.00')).toHaveLength(2); // unit price and line total
    });

    it('writes the SKU in the normal font, not a code font', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #12' });

      const sku = screen.getByText('ALPHA-1');
      expect(sku).toHaveClass('cart-sku');
      expect(sku).not.toHaveClass('mono');
    });

    it('says "1 order" when there is only one, and "Showing 1 to 3 of 3 orders" for more', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      const { unmount } = renderOrders();
      expect(await screen.findByText('1 order')).toBeInTheDocument();
      expect(screen.queryByText(/Showing/)).not.toBeInTheDocument();
      unmount();

      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12), twoLines(11), twoLines(7)]) });
      renderOrders();
      expect(await screen.findByText('Showing 1 to 3 of 3 orders')).toBeInTheDocument();
      expect(screen.queryByText('1 order')).not.toBeInTheDocument();
    });

    it('shows the orders in the order the server sent them, newest first', async () => {
      mockFetch({
        'GET /api/orders': () => pageOf([twoLines(12, { createdAt: '2026-11-19T10:15:00Z' }), twoLines(11, { createdAt: '2026-11-18T09:00:00Z' }), twoLines(7)]),
      });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #12' });

      const titles = screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent);
      expect(titles).toEqual(['Order #12', 'Order #11', 'Order #7']);
    });

    it('shows a rejected order with its reason, and a confirmed order without one', async () => {
      mockFetch({
        'GET /api/orders': () =>
          pageOf([
            twoLines(13, { status: 'REJECTED', rejectReason: 'Not enough stock for APPLE-IP15P-128: wanted 3, available 1' }),
            twoLines(12),
          ]),
      });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #13' });

      const rejected = within(orderCard(13));
      expect(rejected.getByText('Rejected')).toBeInTheDocument();
      expect(rejected.getByText('Not enough stock for APPLE-IP15P-128: wanted 3, available 1')).toBeInTheDocument();
      const confirmed = within(orderCard(12));
      expect(confirmed.getByText('Confirmed')).toBeInTheDocument();
      expect(confirmed.queryByText(/Reason/)).not.toBeInTheDocument();
    });

    it('shows a rejected order whose reason is missing without failing', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(13, { status: 'REJECTED' })]) });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #13' });
      expect(within(orderCard(13)).getByText('Rejected')).toBeInTheDocument();
    });

    it('shows a pending order as pending', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(14, { status: 'PENDING' })]) });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #14' });
      expect(within(orderCard(14)).getByText('Pending')).toBeInTheDocument();
    });

    it('treats an order text as plain text, never as markup', async () => {
      mockFetch({
        'GET /api/orders': () =>
          pageOf([twoLines(15, { status: 'REJECTED', rejectReason: '<img src=x onerror=alert(1)>' })]),
      });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #15' });
      expect(within(orderCard(15)).getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
      expect(document.querySelector('img')).toBeNull();
    });
  });

  describe('paging', () => {
    it('shows the pager when there is more than one page, and asks for the next page', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders': (url) => {
          const page = Number(url.searchParams.get('page'));
          return pageOf([twoLines(100 - page)], { number: page, totalElements: 45 });
        },
      });
      const user = userEvent.setup();
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #100' });
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Next' }));

      expect(await screen.findByRole('heading', { level: 2, name: 'Order #99' })).toBeInTheDocument();
      expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
      expect(ordersRequests(fetchMock)).toEqual(['/api/orders?page=0&size=20', '/api/orders?page=1&size=20']);
    });

    it('opens the page given in the address', async () => {
      const fetchMock = mockFetch({ 'GET /api/orders': () => pageOf([twoLines(50)], { number: 2, totalElements: 45 }) });
      renderOrders('/orders?page=3');
      await screen.findByRole('heading', { level: 2, name: 'Order #50' });
      expect(ordersRequests(fetchMock)).toEqual(['/api/orders?page=2&size=20']);
    });
  });

  describe('after an order was placed', () => {
    it('shows a plain thank-you message', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      renderOrders({ pathname: '/orders', state: { placed: { orderId: 12, cartCleared: true } } });

      expect(await screen.findByRole('status')).toHaveTextContent('Thank you. Order number 12 has been placed.');
      expect(screen.queryByText(CLEAR_WARNING)).not.toBeInTheDocument();
      expect(await screen.findByRole('heading', { level: 2, name: 'Order #12' })).toBeInTheDocument();
    });

    it('also tells the person to empty the cart themselves when it could not be emptied', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      renderOrders({ pathname: '/orders', state: { placed: { orderId: 12, cartCleared: false } } });

      expect(await screen.findByText('Thank you. Order number 12 has been placed.')).toBeInTheDocument();
      expect(screen.getByText(CLEAR_WARNING)).toBeInTheDocument();
    });

    it('takes the message out of the history entry, so a reload does not show it again, but keeps showing it now', async () => {
      function Kept() {
        return <p data-testid="kept">{JSON.stringify(useLocation().state)}</p>;
      }
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      renderAt(
        <>
          <Kept />
          <Routes>
            <Route path="/orders" element={<Orders />} />
          </Routes>
        </>,
        { pathname: '/orders', search: '?page=1', state: { placed: { orderId: 12, cartCleared: false } } },
        { auth: 'USER' },
      );

      await waitFor(() => expect(screen.getByTestId('kept')).toHaveTextContent('null'));
      expect(screen.getByText('Thank you. Order number 12 has been placed.')).toBeInTheDocument();
      expect(screen.getByText(CLEAR_WARNING)).toBeInTheDocument();
      expect(await screen.findByRole('heading', { level: 2, name: 'Order #12' })).toBeInTheDocument();
    });

    it('shows no message on a normal visit', async () => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      renderOrders();
      await screen.findByRole('heading', { level: 2, name: 'Order #12' });
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
      expect(screen.queryByText(/Thank you/)).not.toBeInTheDocument();
    });

    it.each([
      ['a string', 'placed'],
      ['an order number that is not a number', { orderId: 'twelve', cartCleared: true }],
      ['nothing in it', {}],
    ])('ignores a navigation state that is %s', async (_name, placed) => {
      mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
      renderOrders({ pathname: '/orders', state: { placed } });
      await screen.findByRole('heading', { level: 2, name: 'Order #12' });
      expect(screen.queryByText(/Thank you/)).not.toBeInTheDocument();
    });
  });
});

describe('Orders page: order cards', () => {
  const alpha = makeProduct(11, 'Alpha Phone', 'ALPHA-1', { imageUrl: 'https://placehold.co/800x800/png?text=Alpha' });

  it('shows a green badge for a confirmed order, a red one for a rejected order and an amber one for a pending order', async () => {
    mockFetch({
      'GET /api/orders': () =>
        pageOf([twoLines(3, { status: 'CONFIRMED' }), twoLines(2, { status: 'REJECTED', rejectReason: 'No stock' }), twoLines(1, { status: 'PENDING' })]),
    });
    renderOrders();
    await screen.findByRole('heading', { level: 2, name: 'Order #3' });

    expect(within(orderCard(3)).getByText('Confirmed')).toHaveClass('order-status', 'order-status--confirmed');
    expect(within(orderCard(2)).getByText('Rejected')).toHaveClass('order-status--rejected');
    expect(within(orderCard(1)).getByText('Pending')).toHaveClass('order-status--pending');
  });

  it('shows the reason of a rejected order, and only a rejected order', async () => {
    mockFetch({
      'GET /api/orders': () => pageOf([twoLines(2, { status: 'REJECTED', rejectReason: 'The stock service did not answer. Please try again.' }), twoLines(1)]),
    });
    renderOrders();
    await screen.findByRole('heading', { level: 2, name: 'Order #2' });

    expect(within(orderCard(2)).getByText('The stock service did not answer. Please try again.')).toBeInTheDocument();
    expect(within(orderCard(1)).queryByText(/Reason/)).not.toBeInTheDocument();
  });

  it('shows a small picture for every line, taken from the product service', async () => {
    mockFetch({
      'GET /api/orders': () => pageOf([twoLines(12)]),
      'GET /api/products/sku/ALPHA-1': () => alpha,
    });
    renderOrders();
    await screen.findByRole('heading', { level: 2, name: 'Order #12' });

    const line = within(orderCard(12)).getByText('Alpha Phone', { selector: '.cart-name' }).closest('li')!;
    await waitFor(() => expect(line.querySelector('img')).toHaveAttribute('src', 'https://placehold.co/800x800/png?text=Alpha'));
    expect(within(line).getByRole('link', { name: 'Alpha Phone' })).toHaveAttribute('href', '/products/11');
  });

  it('shows the initials, and a name without a link, for a line whose product cannot be looked up', async () => {
    mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
    renderOrders();
    await screen.findByRole('heading', { level: 2, name: 'Order #12' });

    const line = within(orderCard(12)).getByText('Alpha Phone').closest('li')!;
    expect(line.querySelector('img')).toBeNull();
    expect(line).toHaveTextContent('AP');
    expect(within(line).queryByRole('link')).not.toBeInTheDocument();
  });

  it('asks the product service once for each SKU, however many orders use it', async () => {
    const fetchMock = mockFetch({
      'GET /api/orders': () => pageOf([twoLines(12), twoLines(11)]),
      'GET /api/products/sku/ALPHA-1': () => alpha,
    });
    renderOrders();
    await screen.findByRole('heading', { level: 2, name: 'Order #12' });
    await waitFor(() => expect(orderCard(12).querySelector('img')).not.toBeNull());

    const asked = requestedUrls(fetchMock).filter((url) => url.startsWith('/api/products/sku/'));
    expect(asked.sort()).toEqual(['/api/products/sku/ALPHA-1', '/api/products/sku/BETA-2']);
  });

  it('keeps the price shown at the time of the order, not the price the product has now', async () => {
    mockFetch({
      'GET /api/orders': () => pageOf([twoLines(12)]),
      'GET /api/products/sku/ALPHA-1': () => makeProduct(11, 'Alpha Phone', 'ALPHA-1', { price: 1.5 }),
    });
    renderOrders();
    await screen.findByRole('heading', { level: 2, name: 'Order #12' });
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(within(orderCard(12)).getByText('$799.99')).toBeInTheDocument();
    expect(within(orderCard(12)).queryByText('$1.50')).not.toBeInTheDocument();
  });

  it('shows a success mark with the thank-you message after an order', async () => {
    mockFetch({ 'GET /api/orders': () => pageOf([twoLines(12)]) });
    renderOrders({ pathname: '/orders', state: { placed: { orderId: 12, cartCleared: true } } });

    const notice = await screen.findByRole('status');
    expect(notice).toHaveClass('notice--success');
    expect(notice.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });
});
