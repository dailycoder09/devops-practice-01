import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppRoutes } from './App';
import { fakeCart, json, makeDetail, makeItem, makeOrder, makeProduct, mockFetch, pageOf, problem, renderAt } from './test/helpers';

const items = [makeItem(1, 'ALPHA-1', 5, 10)];
const products = [makeProduct(1, 'Alpha Phone', 'ALPHA-1')];

/** The site header. Pages have their own headers, so this finds it through the search box that only it has. */
const siteHeader = () => screen.getByRole('search').closest('header')!;

describe('An address that is not in the shop', () => {
  it('says so with a big 404, a heading, and two ways on: the home page and the product list', async () => {
    renderAt(<AppRoutes />, '/nothing-here');

    expect(screen.getByRole('heading', { level: 1, name: "We can't find that page." })).toBeInTheDocument();
    expect(screen.getByText('There is nothing at this address. Check it for mistakes, or go back to the shop.')).toBeInTheDocument();
    const home = screen.getByRole('link', { name: 'Go to home page' });
    expect(home).toHaveAttribute('href', '/');
    expect(home).toHaveClass('btn', 'btn--buy');
    const browse = screen.getByRole('link', { name: 'Browse products' });
    expect(browse).toHaveAttribute('href', '/products');
    expect(browse).toHaveClass('btn');
    expect(browse).not.toHaveClass('btn--buy');
    await waitFor(() => expect(document.title).toBe('Page not found · MicroShop'));
  });

  it('keeps the number out of the reading order: only the heading says what happened', () => {
    renderAt(<AppRoutes />, '/nothing-here');

    const code = screen.getByText('404');
    expect(code).toHaveAttribute('aria-hidden', 'true');
    expect(screen.queryByRole('heading', { name: '404' })).not.toBeInTheDocument();
    // The page has one heading, and the shop header and category strip are still there around it.
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(siteHeader()).toBeInTheDocument();
  });

  it('opens the product list from the second button', async () => {
    mockFetch({ 'GET /api/products': () => pageOf(products) });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/nothing-here');

    await user.click(screen.getByRole('link', { name: 'Browse products' }));

    expect(await screen.findByRole('link', { name: 'Alpha Phone' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: "We can't find that page." })).not.toBeInTheDocument();
  });
});

describe('App routes for login', () => {
  it('serves the Login page at /login', () => {
    renderAt(<AppRoutes />, '/login');
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });

  it('serves the Register page at /register', () => {
    renderAt(<AppRoutes />, '/register');
    expect(screen.getByRole('heading', { name: 'Create an account' })).toBeInTheDocument();
  });

  it('puts the shop name, the search box and the account links in the page header', () => {
    renderAt(<AppRoutes />, '/login');
    const header = siteHeader();
    expect(within(header).getByRole('link', { name: 'MicroShop' })).toHaveAttribute('href', '/');
    expect(within(header).getByRole('search')).toBeInTheDocument();
    expect(within(header).getByRole('navigation', { name: 'Account' })).toBeInTheDocument();
  });

  it('shows who is logged in in the header on every page', async () => {
    mockFetch({ 'GET /api/products': () => pageOf(products) });
    renderAt(<AppRoutes />, '/products', { auth: 'USER' });
    const header = siteHeader();
    expect(within(header).getByText('jane@example.com')).toBeInTheDocument();
    await screen.findByRole('link', { name: 'Alpha Phone' });
  });

  it('after a 401 on a change: forgets the login, shows the login page, then returns to the page after logging in again', async () => {
    mockFetch({
      'GET /api/inventory': () => pageOf(items),
      'PATCH /api/inventory/sku/ALPHA-1/adjust': () => problem(401, 'Unauthorized', 'The token has expired'),
      'POST /api/users/login': () => ({ token: 'fresh', expiresAt: '2099-01-01T00:00:00Z', email: 'admin@example.com', role: 'ADMIN' }),
    });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/admin/inventory', { auth: 'ADMIN' });
    const button = await screen.findByRole('button', { name: 'ALPHA-1' });
    const row = button.closest('tr')!;

    await user.click(within(row).getByRole('button', { name: /add/i }));

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');
    expect(localStorage.getItem('shop.auth')).toBeNull();
    const header = siteHeader();
    expect(within(header).getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(within(header).queryByText('admin@example.com')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Email'), 'admin@example.com');
    await user.type(screen.getByLabelText('Password'), 'admin12345');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('heading', { name: 'Inventory' })).toBeInTheDocument();
    expect(within(siteHeader()).getByText('admin@example.com')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('shop.auth')!)).toMatchObject({ token: 'fresh' });
  });

  it('a wrong password does not send the person anywhere else', async () => {
    mockFetch({ 'POST /api/users/login': () => problem(401, 'Unauthorized', 'Invalid email or password') });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/login');

    await user.type(screen.getByLabelText('Email'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

describe('App routes for the cart and the orders', () => {
  const catalog = { 'ALPHA-1': { name: 'Alpha Phone', price: 799.99 } };

  it('serves the cart page at /cart', async () => {
    mockFetch(fakeCart(catalog, { 'ALPHA-1': 1 }).routes);
    renderAt(<AppRoutes />, '/cart', { auth: 'USER' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
    expect(await screen.findByText('Alpha Phone')).toBeInTheDocument();
  });

  it('serves the orders page at /orders', async () => {
    mockFetch({ ...fakeCart(catalog).routes, 'GET /api/orders': () => pageOf([makeOrder(3)]) });
    renderAt(<AppRoutes />, '/orders', { auth: 'USER' });
    expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: 'Order #3' })).toBeInTheDocument();
  });

  it('sends a visitor from /cart and from /orders to the login page', async () => {
    mockFetch({});
    const { unmount } = renderAt(<AppRoutes />, '/cart');
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    unmount();

    renderAt(<AppRoutes />, '/orders');
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });

  it('shows the cart count in the header on every page, and takes it away after logging out', async () => {
    const user = userEvent.setup();
    mockFetch({ ...fakeCart(catalog, { 'ALPHA-1': 3 }).routes, 'GET /api/products': () => pageOf(products) });
    renderAt(<AppRoutes />, '/products', { auth: 'USER' });

    const link = await within(siteHeader()).findByRole('link', { name: 'Cart (3)' });
    expect(link).toHaveAttribute('href', '/cart');
    expect(within(siteHeader()).getByRole('link', { name: 'My orders' })).toHaveAttribute('href', '/orders');

    await user.click(within(siteHeader()).getByRole('button', { name: 'Log out' }));

    expect(within(siteHeader()).queryByRole('link', { name: /^Cart/ })).not.toBeInTheDocument();
  });

  it('a 401 while loading the cart forgets the login and shows the login page with the reason', async () => {
    mockFetch({ 'GET /api/cart': () => problem(401, 'Unauthorized', 'The token has expired') });
    renderAt(<AppRoutes />, '/cart', { auth: 'USER' });

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('a 401 on the cart page after it loaded shows the login page with the reason, then comes back to the cart', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 1 });
    mockFetch({
      ...server.routes,
      'POST /api/orders': () => problem(401, 'Unauthorized', 'The token has expired'),
      'POST /api/users/login': () => ({ token: 'fresh', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' }),
    });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/cart', { auth: 'USER' });
    await user.click(await screen.findByRole('button', { name: 'Place order' }));

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');

    await user.type(screen.getByLabelText('Email'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
  });

  it('works from the product page to a placed order: add, header count, cart, place order, orders', async () => {
    const server = fakeCart(catalog);
    mockFetch({
      ...server.routes,
      'GET /api/products/1/details': () => makeDetail({ id: 1, name: 'Alpha Phone', skuCode: 'ALPHA-1' }),
      'POST /api/orders': () => {
        server.quantities.clear();
        return json(makeOrder(21, { cartCleared: true }), 201);
      },
      'GET /api/orders': () => pageOf([makeOrder(21)]),
    });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/products/1', { auth: 'USER' });

    await screen.findByRole('heading', { level: 1, name: 'Alpha Phone' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add to cart' })).toBeEnabled());
    await user.clear(screen.getByRole('spinbutton', { name: 'Quantity' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '2');
    await user.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByText('Added 2 to your cart.')).toBeInTheDocument();
    await user.click(await within(siteHeader()).findByRole('link', { name: 'Cart (2)' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Place order' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument();
    expect(await screen.findByText('Thank you. Order number 21 has been placed.')).toBeInTheDocument();
    expect(await within(siteHeader()).findByRole('link', { name: 'Cart (0)' })).toBeInTheDocument();
  });
});
