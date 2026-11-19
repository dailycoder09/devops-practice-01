import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Overview } from './Overview';
import { makeItem, makeProduct, makeServerInfo, makeStats, makeUser, mockFetch, pageOf, problem, renderAt } from '../test/helpers';

function totalsRoutes() {
  return {
    'GET /api/products': () => pageOf([makeProduct(1, 'Alpha', 'A-1')], { size: 1, totalElements: 5010 }),
    'GET /api/products/categories': () => ['Electronics', 'Home', 'Toys'],
    'GET /api/inventory': () => pageOf([makeItem(1, 'A-1', 5, 1)], { size: 1, totalElements: 4980 }),
  };
}

describe('Admin overview', () => {
  it('shows the nameplate, addresses and EC2 facts for a service that answers', async () => {
    mockFetch({
      ...totalsRoutes(),
      'GET /api/products/server-info': () =>
        makeServerInfo('ip-10-0-1-23.ec2.internal', { instanceId: 'i-0abc123', zone: 'eu-west-1a', forwardedFor: '198.51.100.4' }),
      'GET /api/inventory/server-info': () => makeServerInfo('ip-10-0-2-77.ec2.internal', { instanceId: 'i-0def456', zone: 'eu-west-1b' }),
    });
    renderAt(<Overview />);

    const panel = await screen.findByRole('region', { name: 'Product service' });
    expect(await within(panel).findByText('ip-10-0-1-23.ec2.internal')).toBeInTheDocument();
    expect(within(panel).getByText('Up')).toBeInTheDocument();
    expect(within(panel).getByText('10.0.1.23')).toBeInTheDocument();
    expect(within(panel).queryByText('127.0.0.1')).not.toBeInTheDocument(); // loopback says nothing about the server
    expect(within(panel).queryByText('fe80::1')).not.toBeInTheDocument(); // IPv4 only
    expect(within(panel).getByText('i-0abc123')).toBeInTheDocument();
    expect(within(panel).getByText('eu-west-1a')).toBeInTheDocument();
    expect(within(panel).getByText('198.51.100.4')).toBeInTheDocument();
    expect(within(panel).getByText('10.0.1.23:8081')).toBeInTheDocument();
    expect(within(panel).getByText('1.2.3')).toBeInTheDocument();
    expect(within(panel).getByText('210 of 512 MB')).toBeInTheDocument();
    expect(within(panel).getByText(/^00:00:\d\d$/)).toBeInTheDocument(); // live uptime

    const other = screen.getByRole('region', { name: 'Inventory service' });
    expect(await within(other).findByText('ip-10-0-2-77.ec2.internal')).toBeInTheDocument();
  });

  it('says "Not on EC2" when there is no cloud block', async () => {
    mockFetch({
      ...totalsRoutes(),
      'GET /api/products/server-info': () => makeServerInfo('laptop.local'),
      'GET /api/inventory/server-info': () => makeServerInfo('laptop.local'),
    });
    renderAt(<Overview />);

    const panel = await screen.findByRole('region', { name: 'Product service' });
    expect(await within(panel).findByText('Not on EC2')).toBeInTheDocument();
  });

  it('marks a service unreachable when its call fails, and keeps the other one', async () => {
    mockFetch({
      ...totalsRoutes(),
      'GET /api/products/server-info': () => makeServerInfo('ip-10-0-1-23'),
      'GET /api/inventory/server-info': () => problem(503, 'Service Unavailable', 'no healthy targets'),
    });
    renderAt(<Overview />);

    const down = screen.getByRole('region', { name: 'Inventory service' });
    expect(await within(down).findByText('Unreachable')).toBeInTheDocument();
    expect(within(down).getByRole('alert')).toHaveTextContent('no healthy targets.');
    const up = screen.getByRole('region', { name: 'Product service' });
    expect(await within(up).findByText('ip-10-0-1-23')).toBeInTheDocument();
    expect(within(up).getByText('Up')).toBeInTheDocument();
  });

  it('notices when a refresh is answered by a different server', async () => {
    let calls = 0;
    mockFetch({
      ...totalsRoutes(),
      'GET /api/products/server-info': () => makeServerInfo(++calls === 1 ? 'host-a' : 'host-b'),
      'GET /api/inventory/server-info': () => makeServerInfo('inv-1'),
    });
    const user = userEvent.setup();
    renderAt(<Overview />);

    const panel = await screen.findByRole('region', { name: 'Product service' });
    expect(await within(panel).findByText('host-a')).toBeInTheDocument();
    expect(within(panel).queryByText(/different server than last time/)).not.toBeInTheDocument();

    await user.click(within(panel).getByRole('button', { name: 'Refresh' }));

    expect(await within(panel).findByText('host-b')).toBeInTheDocument();
    expect(await within(panel).findByText('Answered by a different server than last time.')).toBeInTheDocument();
  });

  it('shows each total, and a dash for one that fails without hiding the others', async () => {
    mockFetch({
      ...totalsRoutes(),
      'GET /api/inventory': () => problem(500, 'Internal Server Error'),
      'GET /api/products/server-info': () => makeServerInfo('a'),
      'GET /api/inventory/server-info': () => makeServerInfo('b'),
    });
    renderAt(<Overview />);

    const totals = screen.getByLabelText('Totals');
    expect(await within(totals).findByText('5,010')).toBeInTheDocument();
    expect(await within(totals).findByText('3')).toBeInTheDocument();
    const inventoryTotal = within(totals).getByText('Inventory records').closest('div')!;
    expect(await within(inventoryTotal).findByText('—')).toBeInTheDocument();
  });

  it('has the numbers, the service status and the low stock list, and still has the server panels and the traffic widget', async () => {
    mockFetch({
      ...totalsRoutes(),
      'GET /api/inventory/low-stock': () => pageOf([makeItem(9, 'LOW-1', 0, 10)], { size: 5, totalElements: 331 }),
      'GET /api/users': () => pageOf([makeUser(1, 'admin@example.com', 'ADMIN')], { size: 1, totalElements: 12 }),
      'GET /api/cart': () => ({ items: [], itemCount: 0, total: 0 }),
      'GET /api/orders/stats': () => makeStats(),
      'GET /api/products/server-info': () => makeServerInfo('product-host'),
      'GET /api/inventory/server-info': () => makeServerInfo('inventory-host'),
    });
    renderAt(<Overview />, '/admin', { auth: 'ADMIN' });

    const totals = screen.getByLabelText('Totals');
    expect(await within(totals).findByText('$11,195.66')).toBeInTheDocument();
    expect(await within(totals).findByText('331')).toBeInTheDocument();
    expect(await within(totals).findByText('12')).toBeInTheDocument();

    const status = screen.getByRole('region', { name: 'Service status' });
    expect(await within(status).findByText('All 5 services answered.')).toBeInTheDocument();

    const lowStock = screen.getByRole('region', { name: 'Low stock' });
    expect(await within(lowStock).findByText('LOW-1')).toBeInTheDocument();

    expect(await screen.findByText('product-host')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Traffic distribution' })).toBeInTheDocument();
  });

  it('keeps every other part working when the order service is down', async () => {
    mockFetch({
      ...totalsRoutes(),
      'GET /api/inventory/low-stock': () => pageOf([makeItem(9, 'LOW-1', 0, 10)], { size: 5, totalElements: 1 }),
      'GET /api/users': () => pageOf([], { size: 1, totalElements: 12 }),
      'GET /api/cart': () => ({ items: [], itemCount: 0, total: 0 }),
      'GET /api/orders/stats': () => problem(503, 'Service Unavailable'),
      'GET /api/products/server-info': () => makeServerInfo('product-host'),
      'GET /api/inventory/server-info': () => makeServerInfo('inventory-host'),
    });
    renderAt(<Overview />, '/admin', { auth: 'ADMIN' });

    const status = screen.getByRole('region', { name: 'Service status' });
    expect(await within(status).findByText('1 service did not answer.')).toBeInTheDocument();
    const orderRow = within(status).getByText('Order service').closest('li')!;
    expect(within(orderRow).getByText('Not answering')).toBeInTheDocument();
    const revenue = within(screen.getByLabelText('Totals')).getByText('Revenue').closest('div')!;
    expect(await within(revenue).findByText('—')).toBeInTheDocument();
    expect(await screen.findByText('LOW-1')).toBeInTheDocument();
    expect(await screen.findByText('product-host')).toBeInTheDocument();
  });
});
