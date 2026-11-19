import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AdminOrders } from './Orders';
import { deferred, makeAdminOrder, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

const orders = [
  makeAdminOrder(12, 'jane@example.com', {
    total: 1599.98,
    items: [
      { sku: 'ALPHA-1', name: 'Alpha Phone', unitPrice: 799.99, quantity: 2, lineTotal: 1599.98 },
    ],
  }),
  makeAdminOrder(11, 'bob@example.com', {
    status: 'REJECTED',
    total: 3099.98,
    rejectReason: 'Not enough stock for SKU BETA-2: wanted 2, available 0',
    items: [
      { sku: 'BETA-2', name: 'Beta Laptop', unitPrice: 1549.99, quantity: 1, lineTotal: 1549.99 },
      { sku: 'GAMMA-3', name: 'Gamma Mouse', unitPrice: 1549.99, quantity: 3, lineTotal: 4649.97 },
    ],
  }),
  makeAdminOrder(10, null, { status: 'PENDING', total: 12, userId: 9 }),
];

const allOrderUrls = (fetchMock: ReturnType<typeof mockFetch>) =>
  requestedUrls(fetchMock).filter((url) => url.startsWith('/api/orders/all'));

const rowOf = (text: string) => screen.getByText(text).closest('tr')!;

function load(extra: Parameters<typeof mockFetch>[0] = {}) {
  return mockFetch({ 'GET /api/orders/all': () => pageOf(orders), ...extra });
}

describe('Admin orders page', () => {
  it('lists every order with its number, customer, status, total and item count', async () => {
    const fetchMock = load();
    renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });

    expect(await screen.findByText('jane@example.com')).toBeInTheDocument();
    expect(allOrderUrls(fetchMock)).toEqual(['/api/orders/all?page=0&size=20']);

    const first = rowOf('jane@example.com');
    expect(within(first).getByRole('button', { name: 'Order #12' })).toBeInTheDocument();
    expect(within(first).getByText('Confirmed')).toHaveClass('order-status--confirmed');
    expect(within(first).getByText('$1,599.98')).toBeInTheDocument();
    expect(within(first).getAllByRole('cell')[5]).toHaveTextContent('2'); // two units of one product

    const second = rowOf('bob@example.com');
    expect(within(second).getByText('Rejected')).toHaveClass('order-status--rejected');
    expect(within(second).getAllByRole('cell')[5]).toHaveTextContent('4'); // 1 + 3 units

    expect(screen.getByText('Showing 1 to 3 of 3 orders')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Customer' })).toBeInTheDocument();
  });

  it('sends the admin token', async () => {
    const fetchMock = load();
    renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
    await screen.findByText('jane@example.com');

    const call = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/api/orders/all'));
    expect(call?.[1]?.headers).toMatchObject({ Authorization: 'Bearer admin-token' });
  });

  it('shows the user number when the order has no email', async () => {
    load();
    renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
    await screen.findByText('jane@example.com');

    const row = screen.getByRole('button', { name: 'Order #10' }).closest('tr')!;
    expect(row).toHaveTextContent('User 9');
    expect(within(row).getByText('Pending')).toHaveClass('order-status--pending');
  });

  it('shows a loading line, then the table', async () => {
    const hold = deferred<unknown>();
    load({ 'GET /api/orders/all': () => hold.promise });
    renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });

    expect(screen.getByText('Loading orders…', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading orders…' })).toBeInTheDocument();

    hold.resolve(pageOf(orders));
    expect(await screen.findByText('jane@example.com')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Loading orders…' })).not.toBeInTheDocument();
  });

  describe('status filter', () => {
    it('starts on All and asks for one status when a status is chosen', async () => {
      const fetchMock = load();
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');

      const group = screen.getByRole('group', { name: 'Filter by status' });
      expect(within(group).getAllByRole('button').map((button) => button.textContent)).toEqual([
        'All',
        'Confirmed',
        'Rejected',
        'Pending',
      ]);
      expect(within(group).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');

      await user.click(within(group).getByRole('button', { name: 'Rejected' }));

      await waitFor(() => expect(allOrderUrls(fetchMock)).toContain('/api/orders/all?page=0&size=20&status=REJECTED'));
      expect(within(group).getByRole('button', { name: 'Rejected' })).toHaveAttribute('aria-pressed', 'true');
      expect(within(group).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false');
    });

    it.each([
      ['Confirmed', 'CONFIRMED'],
      ['Pending', 'PENDING'],
    ])('asks for %s orders only', async (label, status) => {
      const fetchMock = load();
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');

      await user.click(screen.getByRole('button', { name: label }));

      await waitFor(() => expect(allOrderUrls(fetchMock)).toContain(`/api/orders/all?page=0&size=20&status=${status}`));
    });

    it('opens already filtered when the address has a status, and says so in the summary', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders/all': () => pageOf([orders[1]], { totalElements: 1 }),
      });
      renderAt(<AdminOrders />, '/admin/orders?status=REJECTED', { auth: 'ADMIN' });

      expect(await screen.findByText('Showing 1 to 1 of 1 rejected orders')).toBeInTheDocument();
      expect(allOrderUrls(fetchMock)).toEqual(['/api/orders/all?page=0&size=20&status=REJECTED']);
      expect(screen.getByRole('button', { name: 'Rejected' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('treats a status it does not know as All instead of sending it', async () => {
      const fetchMock = load();
      renderAt(<AdminOrders />, '/admin/orders?status=DELETED', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');

      expect(allOrderUrls(fetchMock)).toEqual(['/api/orders/all?page=0&size=20']);
      expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('goes back to the first page when the status changes', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders/all': () => pageOf(orders, { number: 1, size: 3, totalElements: 9 }),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders?page=2', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');
      expect(allOrderUrls(fetchMock)[0]).toBe('/api/orders/all?page=1&size=20');

      await user.click(screen.getByRole('button', { name: 'Confirmed' }));

      await waitFor(() => expect(allOrderUrls(fetchMock)).toContain('/api/orders/all?page=0&size=20&status=CONFIRMED'));
    });
  });

  describe('opening an order', () => {
    it('shows the lines and the reject reason when a row is clicked, and hides them on a second click', async () => {
      load();
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');
      expect(screen.queryByText('Beta Laptop')).not.toBeInTheDocument();
      expect(screen.queryByText(/Not enough stock/)).not.toBeInTheDocument();

      await user.click(rowOf('bob@example.com'));

      const lines = screen.getByRole('list', { name: 'Items in order 11' });
      expect(within(lines).getByText('Beta Laptop')).toBeInTheDocument();
      expect(within(lines).getByText('BETA-2')).toBeInTheDocument();
      expect(within(lines).getByText('Gamma Mouse')).toBeInTheDocument();
      expect(within(lines).getByText('3 × $1,549.99')).toBeInTheDocument();
      expect(within(lines).getByText('$4,649.97')).toBeInTheDocument();
      expect(screen.getByText('Not enough stock for SKU BETA-2: wanted 2, available 0')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Order #11' })).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByText('Customer user ID: 7')).toBeInTheDocument();

      await user.click(rowOf('bob@example.com'));

      expect(screen.queryByRole('list', { name: 'Items in order 11' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Order #11' })).toHaveAttribute('aria-expanded', 'false');
    });

    it('opens from the keyboard, and more than one order can be open at once', async () => {
      load();
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');

      screen.getByRole('button', { name: 'Order #12' }).focus();
      await user.keyboard('{Enter}');
      await user.click(screen.getByRole('button', { name: 'Order #11' }));

      expect(screen.getByRole('list', { name: 'Items in order 12' })).toBeInTheDocument();
      expect(screen.getByRole('list', { name: 'Items in order 11' })).toBeInTheDocument();
    });

    it('shows no reason for an order that was not rejected', async () => {
      load();
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');

      await user.click(rowOf('jane@example.com'));

      expect(screen.getByText('Alpha Phone')).toBeInTheDocument();
      expect(screen.queryByText('Reason:')).not.toBeInTheDocument();
    });

    it('shows a reject reason as plain text, never as markup', async () => {
      mockFetch({
        'GET /api/orders/all': () =>
          pageOf([makeAdminOrder(3, 'eve@example.com', { status: 'REJECTED', rejectReason: '<img src=x onerror=alert(1)>' })]),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await user.click(await screen.findByRole('button', { name: 'Order #3' }));

      expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
      expect(document.querySelector('img[src="x"]')).toBeNull();
    });
  });

  describe('pages', () => {
    it('asks for the next page and the previous page', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders/all': (url) =>
          pageOf(orders, { number: Number(url.searchParams.get('page')), size: 20, totalElements: 45 }),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
      expect(screen.getByText('Showing 1 to 3 of 45 orders')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Next' }));
      expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
      expect(allOrderUrls(fetchMock)).toContain('/api/orders/all?page=1&size=20');
      expect(screen.getByText('Showing 21 to 23 of 45 orders')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Previous' }));
      expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument();
    });

    it('keeps the status filter when it moves to the next page', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders/all': (url) =>
          pageOf(orders, { number: Number(url.searchParams.get('page')), size: 20, totalElements: 45 }),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders?status=CONFIRMED', { auth: 'ADMIN' });
      await screen.findByText('jane@example.com');

      await user.click(screen.getByRole('button', { name: 'Next' }));

      await waitFor(() => expect(allOrderUrls(fetchMock)).toContain('/api/orders/all?page=1&size=20&status=CONFIRMED'));
    });
  });

  describe('empty and error states', () => {
    it('says there are no orders yet', async () => {
      mockFetch({ 'GET /api/orders/all': () => pageOf([]) });
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });

      expect(await screen.findByText('There are no orders yet.')).toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });

    it('says when no order has the chosen status, and offers to show all', async () => {
      const fetchMock = mockFetch({
        'GET /api/orders/all': (url) => (url.searchParams.get('status') ? pageOf([]) : pageOf(orders)),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders?status=PENDING', { auth: 'ADMIN' });

      expect(await screen.findByText('There are no pending orders yet.')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Show all orders' }));

      expect(await screen.findByText('jane@example.com')).toBeInTheDocument();
      expect(allOrderUrls(fetchMock).slice(-1)[0]).toBe('/api/orders/all?page=0&size=20');
    });

    it('offers the first page when a page far out has no orders', async () => {
      mockFetch({
        'GET /api/orders/all': (url) => (url.searchParams.get('page') === '0' ? pageOf(orders) : pageOf([], { number: 4 })),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders?page=5', { auth: 'ADMIN' });

      expect(await screen.findByText('No orders on this page.')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Go to the first page' }));
      expect(await screen.findByText('jane@example.com')).toBeInTheDocument();
    });

    it('shows the problem with a Try again button, and the orders after the retry works', async () => {
      let calls = 0;
      mockFetch({
        'GET /api/orders/all': () => (++calls === 1 ? problem(503, 'Service Unavailable') : pageOf(orders)),
      });
      const user = userEvent.setup();
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Could not load orders.');
      expect(alert).toHaveTextContent('Service Unavailable');
      expect(screen.queryByRole('table')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByText('jane@example.com')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('shows the server reason when the filter is refused', async () => {
      mockFetch({
        'GET /api/orders/all': () => problem(400, 'Invalid Request', "The value of 'status' is not valid"),
      });
      renderAt(<AdminOrders />, '/admin/orders', { auth: 'ADMIN' });

      expect(await screen.findByRole('alert')).toHaveTextContent("The value of 'status' is not valid");
    });
  });
});
