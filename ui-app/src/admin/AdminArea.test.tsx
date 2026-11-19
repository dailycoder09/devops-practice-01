import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppRoutes } from '../App';
import {
  makeAdminOrder,
  makeItem,
  makeServerInfo,
  makeStats,
  makeUser,
  mockFetch,
  neverAnswers,
  pageOf,
  problem,
  renderAt,
} from '../test/helpers';

/** What the admin pages ask for. The shop header asks for the cart of whoever is logged in. */
function adminRoutes() {
  return {
    'GET /api/cart': () => ({ items: [], itemCount: 0, total: 0 }),
    'GET /api/products': () => pageOf([], { size: 1, totalElements: 12 }),
    'GET /api/products/categories': () => ['Books'],
    'GET /api/inventory': () => pageOf([makeItem(1, 'ALPHA-1', 5, 10)]),
    'GET /api/products/server-info': () => makeServerInfo('product-host'),
    'GET /api/inventory/server-info': () => makeServerInfo('inventory-host'),
    'GET /api/inventory/low-stock': () => pageOf([makeItem(1, 'ALPHA-1', 5, 10)]),
    'GET /api/users': () => pageOf([makeUser(1, 'admin@example.com', 'ADMIN')]),
    'GET /api/orders/all': () => pageOf([makeAdminOrder(5, 'jane@example.com')]),
    'GET /api/orders/stats': () => makeStats(),
  };
}

const sidebar = () => screen.getByRole('navigation', { name: 'Admin' });

describe('the admin area: who may come in', () => {
  it('sends a visitor to the login page, without asking for any admin data', async () => {
    const fetchMock = mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/admin');

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Admin' })).not.toBeInTheDocument();
    expect(
      fetchMock.mock.calls.map(([path]) => String(path)).filter((path) => path.startsWith('/api/inventory')),
    ).toEqual([]);
  });

  it('brings the visitor back to the page they asked for after logging in as an administrator', async () => {
    mockFetch({
      ...adminRoutes(),
      'POST /api/users/login': () => ({
        token: 'admin-jwt',
        expiresAt: '2099-01-01T00:00:00Z',
        email: 'admin@example.com',
        role: 'ADMIN',
      }),
    });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/admin/inventory');

    await screen.findByRole('heading', { name: 'Log in' });
    await user.type(screen.getByLabelText('Email'), 'admin@example.com');
    await user.type(screen.getByLabelText('Password'), 'admin12345');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Inventory' })).toBeInTheDocument();
    expect(sidebar()).toBeInTheDocument();
  });

  it('tells a logged-in user without the admin role that this area is for administrators, with a link home', async () => {
    const fetchMock = mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/admin', { auth: 'USER' });

    expect(await screen.findByText('This area is for administrators.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('navigation', { name: 'Admin' })).not.toBeInTheDocument();
    expect(
      fetchMock.mock.calls.map(([path]) => String(path)).filter((path) => path.startsWith('/api/inventory')),
    ).toEqual([]);
  });

  it.each(['/admin/orders', '/admin/users'])('sends a visitor from %s to the login page, without asking for any order or user', async (path) => {
    const fetchMock = mockFetch(adminRoutes());
    renderAt(<AppRoutes />, path);

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    const asked = fetchMock.mock.calls.map(([url]) => String(url));
    expect(asked.filter((url) => url.startsWith('/api/users?') || url.startsWith('/api/orders'))).toEqual([]);
  });

  it.each(['/admin/inventory', '/admin/orders', '/admin/users'])('does the same for %s', async (path) => {
    mockFetch(adminRoutes());
    renderAt(<AppRoutes />, path, { auth: 'USER' });
    expect(await screen.findByText('This area is for administrators.')).toBeInTheDocument();
  });

  it('lets an administrator in and shows the menu', async () => {
    mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/admin', { auth: 'ADMIN' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument();
    expect(screen.queryByText('This area is for administrators.')).not.toBeInTheDocument();
  });

  it('sends the person to the login page, with the reason, when the login ends while they are in the admin area', async () => {
    mockFetch({ ...adminRoutes(), 'GET /api/inventory': () => problem(401, 'Unauthorized', 'The token has expired') });
    renderAt(<AppRoutes />, '/admin/inventory', { auth: 'ADMIN' });

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');
  });
});

describe('the admin menu', () => {
  it('has Overview, Inventory, Orders and Users, and marks the page that is open', async () => {
    mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/admin/inventory', { auth: 'ADMIN' });
    await screen.findByRole('heading', { level: 1, name: 'Inventory' });

    const links = within(sidebar()).getAllByRole('link');
    expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Overview', '/admin'],
      ['Inventory', '/admin/inventory'],
      ['Orders', '/admin/orders'],
      ['Users', '/admin/users'],
    ]);
    expect(within(sidebar()).getByRole('link', { name: 'Inventory' })).toHaveAttribute('aria-current', 'page');
    expect(within(sidebar()).getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });

  it('marks Overview only on /admin itself, not on the pages below it', async () => {
    mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/admin', { auth: 'ADMIN' });
    await screen.findByRole('heading', { level: 1, name: 'Overview' });

    expect(within(sidebar()).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
    expect(within(sidebar()).getByRole('link', { name: 'Inventory' })).not.toHaveAttribute('aria-current');
  });

  it('moves between the pages without leaving the area', async () => {
    mockFetch(adminRoutes());
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/admin', { auth: 'ADMIN' });
    await screen.findByRole('heading', { level: 1, name: 'Overview' });

    await user.click(within(sidebar()).getByRole('link', { name: 'Inventory' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Inventory' })).toBeInTheDocument();

    await user.click(within(sidebar()).getByRole('link', { name: 'Orders' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders' })).toBeInTheDocument();

    await user.click(within(sidebar()).getByRole('link', { name: 'Users' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Users' })).toBeInTheDocument();
    expect(sidebar()).toBeInTheDocument();
  });

  it('shows the real Orders and Users pages, with their tables', async () => {
    mockFetch(adminRoutes());
    const first = renderAt(<AppRoutes />, '/admin/orders', { auth: 'ADMIN' });
    expect(await screen.findByText('jane@example.com')).toBeInTheDocument();
    expect(screen.queryByText('This page is built next.')).not.toBeInTheDocument();
    first.unmount();

    renderAt(<AppRoutes />, '/admin/users', { auth: 'ADMIN' });
    expect(await screen.findByText('admin@example.com', { selector: 'td' })).toBeInTheDocument();
    expect(screen.queryByText('This page is built next.')).not.toBeInTheDocument();
  });

  it('shows the old dashboard (which server answered) on the overview and the old inventory on its own page', async () => {
    mockFetch(adminRoutes());
    const first = renderAt(<AppRoutes />, '/admin', { auth: 'ADMIN' });
    expect(await screen.findByRole('region', { name: 'Product service' })).toBeInTheDocument();
    expect(await screen.findByText('product-host')).toBeInTheDocument();
    first.unmount();

    renderAt(<AppRoutes />, '/admin/inventory', { auth: 'ADMIN' });
    expect(await screen.findByRole('button', { name: 'ALPHA-1' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Adjust stock' })).toBeInTheDocument();
  });
});

describe('the old public pages', () => {
  it('no longer have a link in the shop header or the footer', async () => {
    neverAnswers();
    renderAt(<AppRoutes />, '/login');

    for (const name of ['Dashboard', 'Inventory']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
  });

  it('send /inventory to the admin inventory page', async () => {
    mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/inventory', { auth: 'ADMIN' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Inventory' })).toBeInTheDocument();
    await waitFor(() =>
      expect(within(sidebar()).getByRole('link', { name: 'Inventory' })).toHaveAttribute('aria-current', 'page'),
    );
  });

  it('shows a visitor who opens /inventory the login page, not the stock', async () => {
    mockFetch(adminRoutes());
    renderAt(<AppRoutes />, '/inventory');
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });
});

describe('the Admin link in the shop header', () => {
  it('is there for an administrator, on a shop page, and opens the admin area', async () => {
    mockFetch(adminRoutes());
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/products', { auth: 'ADMIN' });

    await user.click(await screen.findByRole('link', { name: 'Admin' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument();
  });

  it('is not there for a user, and not for a visitor', async () => {
    mockFetch(adminRoutes());
    const first = renderAt(<AppRoutes />, '/products', { auth: 'USER' });
    await waitFor(() => expect(screen.getByRole('link', { name: 'Cart (0)' })).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    first.unmount();

    renderAt(<AppRoutes />, '/products');
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });
});
