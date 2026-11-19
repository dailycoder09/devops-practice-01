import { screen, within } from '@testing-library/react';
import { KpiCards } from './KpiCards';
import { makeItem, makeProduct, makeStats, makeUser, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

function kpiRoutes() {
  return {
    'GET /api/products': () => pageOf([makeProduct(1, 'Alpha', 'A-1')], { size: 1, totalElements: 5010 }),
    'GET /api/products/categories': () => ['Electronics', 'Home', 'Toys'],
    'GET /api/inventory': () => pageOf([makeItem(1, 'A-1', 5, 1)], { size: 1, totalElements: 4980 }),
    'GET /api/inventory/low-stock': () => pageOf([makeItem(2, 'B-2', 0, 10)], { size: 1, totalElements: 331 }),
    'GET /api/users': () => pageOf([makeUser(1, 'admin@example.com', 'ADMIN')], { size: 1, totalElements: 12 }),
    'GET /api/orders/stats': () => makeStats({ totalOrders: 15, pending: 2, confirmed: 9, rejected: 4, revenue: 16145.62 }),
  };
}

/** The card with this label, and what it shows. */
function card(label: string): HTMLElement {
  return within(screen.getByLabelText('Totals')).getByText(label).closest('div')!;
}

describe('KPI cards on the admin overview', () => {
  it('shows the catalog and stock numbers', async () => {
    const fetchMock = mockFetch(kpiRoutes());
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });

    expect(await within(card('Products')).findByText('5,010')).toBeInTheDocument();
    expect(await within(card('Categories')).findByText('3')).toBeInTheDocument();
    expect(await within(card('Inventory records')).findByText('4,980')).toBeInTheDocument();
    expect(await within(card('Low stock items')).findByText('331')).toBeInTheDocument();
    const urls = requestedUrls(fetchMock);
    expect(urls).toContain('/api/inventory/low-stock?size=1');
    expect(urls).toContain('/api/products?size=1');
    expect(urls).toContain('/api/inventory?size=1');
  });

  it('shows the order numbers from the stats, and the revenue as money', async () => {
    const fetchMock = mockFetch(kpiRoutes());
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });

    expect(await within(card('Orders')).findByText('15')).toBeInTheDocument();
    expect(within(card('Orders')).getByText(/2 pending/)).toBeInTheDocument();
    expect(within(card('Confirmed orders')).getByText('9')).toBeInTheDocument();
    expect(within(card('Rejected orders')).getByText('4')).toBeInTheDocument();
    expect(within(card('Revenue')).getByText('$16,145.62')).toBeInTheDocument();
    // One call feeds all of them.
    expect(requestedUrls(fetchMock).filter((url) => url === '/api/orders/stats')).toHaveLength(1);
  });

  it('shows how many users there are, from the total of a page of one', async () => {
    const fetchMock = mockFetch(kpiRoutes());
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });

    expect(await within(card('Users')).findByText('12')).toBeInTheDocument();
    expect(requestedUrls(fetchMock)).toContain('/api/users?size=1');
  });

  it('links the cards to the pages that have the details', async () => {
    mockFetch(kpiRoutes());
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });
    await within(card('Users')).findByText('12');
    await within(card('Orders')).findByText('15');
    await within(card('Low stock items')).findByText('331');

    expect(within(card('Users')).getByRole('link', { name: 'See all' })).toHaveAttribute('href', '/admin/users');
    expect(within(card('Orders')).getByRole('link', { name: 'See all' })).toHaveAttribute('href', '/admin/orders');
    expect(within(card('Low stock items')).getByRole('link', { name: 'See the list' })).toHaveAttribute(
      'href',
      '/admin/inventory?low=1',
    );
  });

  it('shows dashes for the order numbers when the order service fails, and keeps the other numbers', async () => {
    mockFetch({ ...kpiRoutes(), 'GET /api/orders/stats': () => problem(503, 'Service Unavailable') });
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });

    expect(await within(card('Products')).findByText('5,010')).toBeInTheDocument();
    for (const label of ['Orders', 'Confirmed orders', 'Rejected orders', 'Revenue']) {
      expect(await within(card(label)).findByText('—')).toBeInTheDocument();
    }
    expect(within(card('Revenue')).getByText('(unavailable)')).toBeInTheDocument();
    expect(await within(card('Users')).findByText('12')).toBeInTheDocument();
  });

  it('shows a dash for the low stock number when that endpoint fails', async () => {
    mockFetch({ ...kpiRoutes(), 'GET /api/inventory/low-stock': () => problem(400, 'Invalid Request') });
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });

    expect(await within(card('Low stock items')).findByText('—')).toBeInTheDocument();
    expect(await within(card('Inventory records')).findByText('4,980')).toBeInTheDocument();
  });

  it('shows a zero as a zero, not as a dash', async () => {
    mockFetch({
      ...kpiRoutes(),
      'GET /api/inventory/low-stock': () => pageOf([], { size: 1, totalElements: 0 }),
      'GET /api/orders/stats': () => makeStats({ totalOrders: 0, pending: 0, confirmed: 0, rejected: 0, revenue: 0 }),
    });
    renderAt(<KpiCards />, '/admin', { auth: 'ADMIN' });

    expect(await within(card('Low stock items')).findByText('0')).toBeInTheDocument();
    expect(await within(card('Revenue')).findByText('$0.00')).toBeInTheDocument();
    expect(within(card('Orders')).getByText('0')).toBeInTheDocument();
  });
});
