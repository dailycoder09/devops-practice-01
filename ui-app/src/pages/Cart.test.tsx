import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import type { Role } from '../api/types';
import { Cart, priceDetails } from './Cart';
import { Login } from './Login';
import { Orders } from './Orders';
import { deferred, fakeCart, json, makeCartLine, makeOrder, makeProduct, mockFetch, pageOf, problem, renderAt } from '../test/helpers';

type Extra = Parameters<typeof mockFetch>[0];

const CLEAR_WARNING = 'Your order is confirmed. We could not empty your cart, please remove the items yourself before ordering again.';

/** Logged in as a user unless `null` is passed (a visitor). */
function renderCart(auth: Role | null = 'USER') {
  return renderAt(
    <Routes>
      <Route path="/cart" element={<Cart />} />
      <Route path="/orders" element={<Orders />} />
      <Route path="/login" element={<Login />} />
      <Route path="/products" element={<p>The product list</p>} />
    </Routes>,
    '/cart',
    { auth: auth ?? undefined },
  );
}

/** A pretend cart-service holding `initial` (SKU to quantity), plus any extra routes. */
function setup(initial: Record<string, number>, extra: Extra = {}, catalog = defaultCatalog()) {
  const server = fakeCart(catalog, initial);
  const fetchMock = mockFetch({ ...server.routes, 'GET /api/orders': () => pageOf([makeOrder(12)]), ...extra });
  return { server, fetchMock, catalog };
}

function defaultCatalog() {
  return {
    'ALPHA-1': { name: 'Alpha Phone', price: 799.99 },
    'BETA-2': { name: 'Beta Lamp', price: 20 },
  };
}

const rowOf = (name: string) => screen.getByText(name).closest('li')!;
const puts = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT');
const posts = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([path, init]) => path === '/api/orders' && init?.method === 'POST');
const cartLoads = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([path, init]) => path === '/api/cart' && (init?.method ?? 'GET') === 'GET');

async function loaded() {
  await screen.findByRole('heading', { level: 1, name: 'Your cart' });
  await waitFor(() => expect(screen.queryByText('Loading your cart…')).not.toBeInTheDocument());
}

describe('Cart page', () => {
  describe('for a visitor', () => {
    it('goes to the login page without asking for a cart, and comes back to the cart after logging in', async () => {
      const { fetchMock } = setup(
        { 'ALPHA-1': 1 },
        { 'POST /api/users/login': () => ({ token: 'jwt-abc', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' }) },
      );
      const user = userEvent.setup();
      renderCart(null);

      expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'Your cart' })).not.toBeInTheDocument();
      expect(fetchMock.mock.calls.filter(([path]) => path === '/api/cart')).toHaveLength(0);

      await user.type(screen.getByLabelText('Email'), 'jane@example.com');
      await user.type(screen.getByLabelText('Password'), 'secret123');
      await user.click(screen.getByRole('button', { name: 'Log in' }));

      expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
      expect(await screen.findByText('Alpha Phone')).toBeInTheDocument();
    });
  });

  describe('loading', () => {
    it('shows a loading message, then the cart', async () => {
      const gate = deferred();
      const server = fakeCart(defaultCatalog(), { 'ALPHA-1': 1 });
      const cart = server.routes['GET /api/cart'];
      mockFetch({
        ...server.routes,
        'GET /api/cart': async (url, init) => {
          await gate.promise;
          return cart(url, init);
        },
      });
      renderCart();

      expect(screen.getByRole('status')).toHaveTextContent('Loading your cart…');
      expect(screen.queryByText('Your cart is empty.')).not.toBeInTheDocument();
      gate.resolve();
      expect(await screen.findByText('Alpha Phone')).toBeInTheDocument();
      expect(screen.queryByText('Loading your cart…')).not.toBeInTheDocument();
    });

    it('titles the page', async () => {
      setup({});
      renderCart();
      await loaded();
      await waitFor(() => expect(document.title).toBe('Your cart · MicroShop'));
    });

    it('says what went wrong when the cart cannot be loaded, and loads it on Try again', async () => {
      const server = fakeCart(defaultCatalog(), { 'ALPHA-1': 1 });
      let up = false;
      const cart = server.routes['GET /api/cart'];
      mockFetch({
        ...server.routes,
        'GET /api/cart': (url, init) => (up ? cart(url, init) : problem(503, 'Service Unavailable', 'The product service did not answer. Please try again.')),
      });
      const user = userEvent.setup();
      renderCart();

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your cart. The product service did not answer. Please try again.');
      expect(screen.queryByText('Your cart is empty.')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Place order' })).not.toBeInTheDocument();

      up = true;
      await user.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByText('Alpha Phone')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });

  describe('an empty cart', () => {
    it('says so in plain words and links to the products, with nothing to order', async () => {
      setup({});
      renderCart();

      expect(await screen.findByText('Your cart is empty.')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/products');
      expect(screen.queryByRole('button', { name: 'Place order' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Empty cart' })).not.toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });
  });

  describe('the lines', () => {
    it('shows name, SKU, unit price, quantity and line total for each line, then the item count and the total', async () => {
      setup({ 'ALPHA-1': 2, 'BETA-2': 1 });
      renderCart();
      await screen.findByText('Alpha Phone');

      const alpha = within(rowOf('Alpha Phone'));
      expect(alpha.getByText('ALPHA-1')).toBeInTheDocument();
      // The SKU is plain text in the shop's own font, not a code font.
      expect(alpha.getByText('ALPHA-1')).not.toHaveClass('mono');
      expect(alpha.getByText('$799.99')).toBeInTheDocument();
      expect(alpha.getByRole('spinbutton', { name: 'Quantity of Alpha Phone' })).toHaveValue(2);
      expect(alpha.getByText('$1,599.98')).toBeInTheDocument();
      const beta = within(rowOf('Beta Lamp'));
      expect(beta.getByText('BETA-2')).toBeInTheDocument();
      expect(beta.getByRole('spinbutton', { name: 'Quantity of Beta Lamp' })).toHaveValue(1);

      expect(screen.getByText('Price (3 items)')).toBeInTheDocument();
      expect(screen.getByText('Total amount').nextElementSibling).toHaveTextContent('$1,619.98');
      expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Empty cart' })).toBeEnabled();
    });

    it('keeps the lines in the order they were added', async () => {
      setup({ 'BETA-2': 1, 'ALPHA-1': 1 });
      renderCart();
      await screen.findByText('Alpha Phone');
      const names = screen.getAllByRole('listitem').map((row) => within(row).getByText(/Lamp|Phone/).textContent);
      expect(names).toEqual(['Beta Lamp', 'Alpha Phone']);
    });
  });

  describe('changing a quantity', () => {
    it('sends the new quantity and shows the new line total, count and total', async () => {
      const { fetchMock } = setup({ 'ALPHA-1': 2, 'BETA-2': 1 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      const alpha = within(rowOf('Alpha Phone'));
      await user.clear(alpha.getByRole('spinbutton'));
      await user.type(alpha.getByRole('spinbutton'), '4');
      await user.click(alpha.getByRole('button', { name: 'Update quantity of Alpha Phone' }));

      await waitFor(() => expect(within(rowOf('Alpha Phone')).getByText('$3,199.96')).toBeInTheDocument());
      expect(screen.getByText('Price (5 items)')).toBeInTheDocument();
      expect(screen.getByText('Total amount').nextElementSibling).toHaveTextContent('$3,219.96');
      // The total in the bar above Place order follows the cart too.
      expect(screen.getByText('Total').nextElementSibling).toHaveTextContent('$3,219.96');
      const [path, init] = puts(fetchMock)[0];
      expect(path).toBe('/api/cart/items/ALPHA-1');
      expect(JSON.parse(String(init?.body))).toEqual({ quantity: 4 });
      expect(init?.headers).toMatchObject({ Authorization: 'Bearer user-token' });
    });

    it('also saves when Enter is pressed in the box', async () => {
      const { server } = setup({ 'BETA-2': 1 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      const box = within(rowOf('Beta Lamp')).getByRole('spinbutton');
      await user.clear(box);
      await user.type(box, '7{Enter}');

      await waitFor(() => expect(server.quantities.get('BETA-2')).toBe(7));
    });

    it('keeps Update off until the number is different', async () => {
      setup({ 'BETA-2': 3 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      const row = within(rowOf('Beta Lamp'));
      expect(row.getByRole('button', { name: 'Update quantity of Beta Lamp' })).toBeDisabled();
      await user.type(row.getByRole('spinbutton'), '1');
      expect(row.getByRole('button', { name: 'Update quantity of Beta Lamp' })).toBeEnabled();
    });

    it.each([
      ['0', '0'],
      ['100', '100'],
      ['-2', '-2'],
      ['2.5', '2.5'],
      ['nothing', ''],
    ])('does not send a quantity of %s, and says what is allowed on that line', async (_name, typed) => {
      const { fetchMock } = setup({ 'BETA-2': 3 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      const row = within(rowOf('Beta Lamp'));
      await user.clear(row.getByRole('spinbutton'));
      if (typed) await user.type(row.getByRole('spinbutton'), typed);
      await user.type(row.getByRole('spinbutton'), '{Enter}');

      expect(await within(rowOf('Beta Lamp')).findByRole('alert')).toHaveTextContent('Enter a whole number from 1 to 99.');
      expect(puts(fetchMock)).toHaveLength(0);
    });

    it('shows the reason on that line when the server refuses, and keeps the old line total', async () => {
      setup(
        { 'ALPHA-1': 2 },
        { 'PUT /api/cart/items/ALPHA-1': () => problem(503, 'Service Unavailable', 'The product service did not answer. Please try again.') },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      const box = within(rowOf('Alpha Phone')).getByRole('spinbutton');
      await user.clear(box);
      await user.type(box, '5{Enter}');

      expect(await within(rowOf('Alpha Phone')).findByRole('alert')).toHaveTextContent('The product service did not answer. Please try again.');
      expect(within(rowOf('Alpha Phone')).getByText('$1,599.98')).toBeInTheDocument();
      expect(within(rowOf('Alpha Phone')).getByRole('spinbutton')).toHaveValue(5);
    });
  });

  describe('removing and emptying', () => {
    it('Remove deletes that line and updates the count and the total', async () => {
      const { server, fetchMock } = setup({ 'ALPHA-1': 2, 'BETA-2': 1 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Remove Alpha Phone' }));

      await waitFor(() => expect(screen.queryByText('Alpha Phone')).not.toBeInTheDocument());
      expect(screen.getByText('Beta Lamp')).toBeInTheDocument();
      expect(screen.getByText('Price (1 item)')).toBeInTheDocument();
      expect(screen.getByText('Total amount').nextElementSibling).toHaveTextContent('$20.00');
      expect(server.quantities.has('ALPHA-1')).toBe(false);
      const del = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')!;
      expect(del[0]).toBe('/api/cart/items/ALPHA-1');
    });

    it('removing the last line shows the empty message', async () => {
      setup({ 'BETA-2': 1 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(screen.getByRole('button', { name: 'Remove Beta Lamp' }));

      expect(await screen.findByText('Your cart is empty.')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Browse products' })).toBeInTheDocument();
    });

    it('shows the reason on that line when Remove fails, and keeps the line', async () => {
      setup({ 'BETA-2': 1 }, { 'DELETE /api/cart/items/BETA-2': () => problem(503, 'Service Unavailable', 'Try again later') });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(screen.getByRole('button', { name: 'Remove Beta Lamp' }));

      expect(await within(rowOf('Beta Lamp')).findByRole('alert')).toHaveTextContent('Try again later');
    });

    it('Empty cart deletes everything and shows the empty message', async () => {
      const { server, fetchMock } = setup({ 'ALPHA-1': 2, 'BETA-2': 1 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Empty cart' }));

      expect(await screen.findByText('Your cart is empty.')).toBeInTheDocument();
      expect(server.quantities.size).toBe(0);
      const del = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')!;
      expect(del[0]).toBe('/api/cart');
    });

    it('shows the reason when Empty cart fails', async () => {
      setup({ 'BETA-2': 1 }, { 'DELETE /api/cart': () => problem(503, 'Service Unavailable', 'Try again later') });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(screen.getByRole('button', { name: 'Empty cart' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Try again later');
      expect(screen.getByText('Beta Lamp')).toBeInTheDocument();
    });
  });

  describe('an item that is no longer available', () => {
    it('is marked, has no quantity box, and stops Place order until it is removed', async () => {
      const user = userEvent.setup();
      setup({ 'GONE-9': 2, 'ALPHA-1': 1 });
      renderCart();
      await screen.findByText('Alpha Phone');

      const gone = within(rowOf('GONE-9'));
      expect(gone.getByText('No longer available')).toBeInTheDocument();
      expect(gone.queryByRole('spinbutton')).not.toBeInTheDocument();
      expect(gone.getByRole('button', { name: 'Remove GONE-9' })).toBeEnabled();
      expect(screen.getByRole('status')).toHaveTextContent('Some items are no longer available. Remove them to place your order.');
      expect(screen.getByRole('button', { name: 'Place order' })).toBeDisabled();
      // The total only counts what can be bought.
      expect(screen.getByText('Total amount').nextElementSibling).toHaveTextContent('$799.99');

      await user.click(gone.getByRole('button', { name: 'Remove GONE-9' }));

      await waitFor(() => expect(screen.queryByText('GONE-9')).not.toBeInTheDocument());
      expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
      expect(screen.queryByText(/no longer available/)).not.toBeInTheDocument();
    });

    it('does not send an order when Place order is off', async () => {
      const { fetchMock } = setup({ 'GONE-9': 1 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('GONE-9');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(posts(fetchMock)).toHaveLength(0);
    });
  });

  describe('while a change is running', () => {
    it('turns the other buttons off until it is done', async () => {
      const gate = deferred();
      const server = fakeCart(defaultCatalog(), { 'ALPHA-1': 1, 'BETA-2': 1 });
      const put = server.routes['PUT /api/cart/items/ALPHA-1'];
      mockFetch({
        ...server.routes,
        'PUT /api/cart/items/ALPHA-1': async (url, init) => {
          await gate.promise;
          return put(url, init);
        },
      });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      const box = within(rowOf('Alpha Phone')).getByRole('spinbutton');
      await user.clear(box);
      await user.type(box, '3{Enter}');

      expect(screen.getByRole('button', { name: 'Place order' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Empty cart' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Remove Beta Lamp' })).toBeDisabled();
      gate.resolve();

      await waitFor(() => expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled());
      expect(screen.getByRole('button', { name: 'Remove Beta Lamp' })).toBeEnabled();
      expect(within(rowOf('Alpha Phone')).getByText('$2,399.97')).toBeInTheDocument();
    });
  });

  describe('Place order', () => {
    it('is disabled while it runs, sends one order with no body, then goes to the orders page with a message', async () => {
      const gate = deferred();
      const placed = makeOrder(12, { cartCleared: true });
      const { server, fetchMock } = setup(
        { 'ALPHA-1': 2 },
        {
          'POST /api/orders': async () => {
            await gate.promise;
            server.quantities.clear();
            return json(placed, 201);
          },
        },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(screen.getByRole('button', { name: 'Placing order…' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Empty cart' })).toBeDisabled();
      await user.click(screen.getByRole('button', { name: 'Placing order…' }));
      gate.resolve();

      expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent('Thank you. Order number 12 has been placed.');
      expect(screen.queryByText(CLEAR_WARNING)).not.toBeInTheDocument();
      expect(posts(fetchMock)).toHaveLength(1);
      const [, init] = posts(fetchMock)[0];
      expect(init?.body).toBeUndefined();
      expect(init?.headers).toMatchObject({ Authorization: 'Bearer user-token' });
      // The cart is asked for again, so the page header count is right.
      await waitFor(() => expect(cartLoads(fetchMock).length).toBeGreaterThanOrEqual(2));
    });

    it('also warns when the order is confirmed but the cart could not be emptied', async () => {
      const { fetchMock } = setup(
        { 'ALPHA-1': 1 },
        { 'POST /api/orders': () => json(makeOrder(13, { cartCleared: false }), 201) },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument();
      expect(screen.getByText('Thank you. Order number 13 has been placed.')).toBeInTheDocument();
      expect(screen.getByText(CLEAR_WARNING)).toBeInTheDocument();
      expect(posts(fetchMock)).toHaveLength(1);
    });

    it('stays on the cart and shows the reason when there is not enough stock (409)', async () => {
      const reason = 'Not enough stock for APPLE-IP15P-128: wanted 3, available 1';
      setup({ 'ALPHA-1': 2 }, { 'POST /api/orders': () => problem(409, 'Conflict', reason) });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(reason);
      expect(screen.getByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'My orders' })).not.toBeInTheDocument();
      expect(screen.getByText('Alpha Phone')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
    });

    it('stays on the cart and shows the reason when the stock service did not answer (503)', async () => {
      const reason = 'The stock service did not answer. Please try again.';
      setup({ 'ALPHA-1': 2 }, { 'POST /api/orders': () => problem(503, 'Service Unavailable', reason) });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(reason);
      expect(screen.getByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
    });

    it('lets the person try again after a refusal, and removes the old message when the next try works', async () => {
      let attempts = 0;
      setup(
        { 'ALPHA-1': 1 },
        {
          'POST /api/orders': () => {
            attempts += 1;
            return attempts === 1 ? problem(503, 'Service Unavailable', 'The stock service did not answer. Please try again.') : json(makeOrder(14, { cartCleared: true }), 201);
          },
        },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');
      await user.click(screen.getByRole('button', { name: 'Place order' }));
      expect(await screen.findByRole('alert')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('shows the reason and reloads the cart when the cart turns out to be empty (400)', async () => {
      const { server, fetchMock } = setup(
        { 'ALPHA-1': 1 },
        {
          'POST /api/orders': () => {
            server.quantities.clear();
            return problem(400, 'Bad Request', 'Your cart is empty.');
          },
        },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByText('Your cart is empty.', { selector: '[role="alert"] *' })).toBeInTheDocument();
      await waitFor(() => expect(cartLoads(fetchMock)).toHaveLength(2));
    });

    it('shows the reason and reloads the cart when a product is gone (422), so the line is marked', async () => {
      const catalog = defaultCatalog();
      const { fetchMock } = setup(
        { 'ALPHA-1': 1 },
        {
          'POST /api/orders': () => {
            delete (catalog as Record<string, unknown>)['ALPHA-1'];
            return problem(422, 'Unprocessable Entity', 'Product ALPHA-1 is no longer available.');
          },
        },
        catalog,
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByText('Product ALPHA-1 is no longer available.')).toBeInTheDocument();
      expect(await screen.findByText('No longer available')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Place order' })).toBeDisabled();
      expect(cartLoads(fetchMock)).toHaveLength(2);
    });

    it('does not reload the cart after a 409 or 503, because the cart did not change', async () => {
      const { fetchMock } = setup({ 'ALPHA-1': 1 }, { 'POST /api/orders': () => problem(409, 'Conflict', 'Not enough stock for ALPHA-1: wanted 1, available 0') });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));
      await screen.findByRole('alert');

      expect(cartLoads(fetchMock)).toHaveLength(1);
    });

    it('says the order may not have been placed when the server cannot be reached', async () => {
      setup(
        { 'ALPHA-1': 1 },
        {
          'POST /api/orders': () => {
            throw new TypeError('Failed to fetch');
          },
        },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Could not reach the server. Your order may not have been placed, so check My orders before you try again.',
      );
      expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
    });

    it('sends the person to the login page when the token is no longer accepted (401)', async () => {
      setup({ 'ALPHA-1': 1 }, { 'POST /api/orders': () => problem(401, 'Unauthorized', 'The token has expired') });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');
      expect(localStorage.getItem('shop.auth')).toBeNull();
    });
  });
});

describe('Cart page: pictures and price details', () => {
  const alpha = makeProduct(11, 'Alpha Phone', 'ALPHA-1', {
    price: 799.99,
    brand: 'Alpha',
    mrp: 999.99,
    discountPercent: 20,
    imageUrl: 'https://placehold.co/800x800/png?text=Alpha',
  });
  const beta = makeProduct(12, 'Beta Lamp', 'BETA-2', { price: 20, brand: 'Beta Co', mrp: 25 });

  /** The product service answers for each SKU in `known`; any other SKU is not found. */
  function lookups(known: Record<string, ReturnType<typeof makeProduct>>) {
    const routes: Record<string, () => unknown> = {};
    for (const [sku, product] of Object.entries(known)) routes[`GET /api/products/sku/${sku}`] = () => product;
    return routes;
  }

  describe('each line', () => {
    it('shows the picture, the brand, the unit price with the list price struck through, and the discount', async () => {
      setup({ 'ALPHA-1': 2 }, lookups({ 'ALPHA-1': alpha }));
      renderCart();

      const line = within(rowOf(await screen.findByText('Alpha Phone').then((element) => element.textContent!)));
      await waitFor(() => expect(line.getByText('Alpha')).toBeInTheDocument());
      expect(line.getByText('$799.99')).toHaveClass('price');
      expect(line.getByText('$999.99').tagName).toBe('S');
      expect(line.getByText('20% off')).toHaveClass('discount');
      expect(rowOf('Alpha Phone').querySelector('img')).toHaveAttribute('src', 'https://placehold.co/800x800/png?text=Alpha');
    });

    it('links the name to the product page once the product is known', async () => {
      setup({ 'ALPHA-1': 1 }, lookups({ 'ALPHA-1': alpha }));
      renderCart();

      await waitFor(() => expect(screen.getByRole('link', { name: 'Alpha Phone' })).toHaveAttribute('href', '/products/11'));
    });

    it('asks the public product endpoint once for each SKU', async () => {
      const { fetchMock } = setup({ 'ALPHA-1': 1, 'BETA-2': 1 }, lookups({ 'ALPHA-1': alpha, 'BETA-2': beta }));
      renderCart();
      await screen.findByText('Alpha Phone');
      await waitFor(() => expect(screen.getByText('Beta Co')).toBeInTheDocument());

      const asked = fetchMock.mock.calls.map(([path]) => String(path)).filter((path) => path.startsWith('/api/products/sku/'));
      expect(asked.sort()).toEqual(['/api/products/sku/ALPHA-1', '/api/products/sku/BETA-2']);
    });

    it('shows the line without a picture when the product cannot be looked up', async () => {
      setup({ 'ALPHA-1': 2 });
      renderCart();

      await screen.findByText('Alpha Phone');
      const line = within(rowOf('Alpha Phone'));
      expect(rowOf('Alpha Phone').querySelector('img')).toBeNull();
      expect(rowOf('Alpha Phone').querySelector('.picture')).toBeNull();
      expect(line.getByText('$799.99')).toBeInTheDocument();
      expect(line.getByText('$1,599.98')).toBeInTheDocument();
      expect(line.queryByText(/% off/)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
    });

    it('shows no discount on a line whose list price is not above the price', async () => {
      setup({ 'BETA-2': 1 }, lookups({ 'BETA-2': makeProduct(12, 'Beta Lamp', 'BETA-2', { price: 20, mrp: 20 }) }));
      renderCart();

      await screen.findByText('Beta Lamp');
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(rowOf('Beta Lamp').querySelector('s')).toBeNull();
      expect(within(rowOf('Beta Lamp')).queryByText(/% off/)).not.toBeInTheDocument();
    });
  });

  describe('Price details', () => {
    it('shows the price, the discount (list prices minus what the cart costs), the total and what is saved', async () => {
      setup({ 'ALPHA-1': 2, 'BETA-2': 1 }, lookups({ 'ALPHA-1': alpha, 'BETA-2': beta }));
      renderCart();

      expect(await screen.findByRole('heading', { level: 2, name: 'Price details' })).toBeInTheDocument();
      // 2 x 999.99 + 1 x 25 = 2,024.98 list price; the cart costs 2 x 799.99 + 20 = 1,619.98; the difference is 405.00.
      expect(await screen.findByText('Price (3 items)')).toBeInTheDocument();
      await waitFor(() => expect(screen.getByText('Discount').nextElementSibling).toHaveTextContent('−$405.00'));
      expect(screen.getByText('Price (3 items)').nextElementSibling).toHaveTextContent('$2,024.98');
      expect(screen.getByText('Total amount').nextElementSibling).toHaveTextContent('$1,619.98');
      expect(screen.getByText('You will save $405.00 on this order')).toBeInTheDocument();
    });

    it('says "item" for a single item', async () => {
      setup({ 'BETA-2': 1 }, lookups({ 'BETA-2': beta }));
      renderCart();
      expect(await screen.findByText('Price (1 item)')).toBeInTheDocument();
    });

    it('has no Discount line and no saving text until the list price is known for every line', async () => {
      // BETA-2 cannot be looked up, so its list price is unknown.
      setup({ 'ALPHA-1': 2, 'BETA-2': 1 }, lookups({ 'ALPHA-1': alpha }));
      renderCart();

      await screen.findByText('Alpha Phone');
      await waitFor(() => expect(screen.getByRole('link', { name: 'Alpha Phone' })).toBeInTheDocument());
      expect(screen.queryByText('Discount')).not.toBeInTheDocument();
      expect(screen.queryByText(/You will save/)).not.toBeInTheDocument();
      expect(screen.getByText('Price (3 items)').nextElementSibling).toHaveTextContent('$1,619.98');
      expect(screen.getByText('Total amount').nextElementSibling).toHaveTextContent('$1,619.98');
    });

    it('has no Discount line when a product has no list price at all', async () => {
      setup({ 'BETA-2': 1 }, lookups({ 'BETA-2': makeProduct(12, 'Beta Lamp', 'BETA-2', { price: 20 }) }));
      renderCart();

      await screen.findByText('Beta Lamp');
      await waitFor(() => expect(screen.getByRole('link', { name: 'Beta Lamp' })).toBeInTheDocument());
      expect(screen.queryByText('Discount')).not.toBeInTheDocument();
    });

    it('updates when the quantity changes', async () => {
      setup({ 'ALPHA-1': 1 }, lookups({ 'ALPHA-1': alpha }));
      const user = userEvent.setup();
      renderCart();
      await waitFor(() => expect(screen.getByText('Discount').nextElementSibling).toHaveTextContent('−$200.00'));

      await user.click(screen.getByRole('button', { name: 'Increase quantity of Alpha Phone' }));

      await waitFor(() => expect(screen.getByText('Discount').nextElementSibling).toHaveTextContent('−$400.00'));
      expect(screen.getByText('You will save $400.00 on this order')).toBeInTheDocument();
    });
  });

  describe('the quantity stepper', () => {
    it('Plus adds one and saves straight away', async () => {
      const { server } = setup({ 'BETA-2': 3 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Increase quantity of Beta Lamp' }));

      await waitFor(() => expect(server.quantities.get('BETA-2')).toBe(4));
      expect(within(rowOf('Beta Lamp')).getByRole('spinbutton')).toHaveValue(4);
    });

    it('Minus takes one away and saves straight away', async () => {
      const { server } = setup({ 'BETA-2': 3 });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Decrease quantity of Beta Lamp' }));

      await waitFor(() => expect(server.quantities.get('BETA-2')).toBe(2));
    });

    it('cannot go below 1 with Minus (Remove is for that) or above 99 with Plus', async () => {
      setup({ 'BETA-2': 1, 'ALPHA-1': 99 });
      renderCart();
      await screen.findByText('Beta Lamp');

      expect(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Decrease quantity of Beta Lamp' })).toBeDisabled();
      expect(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Increase quantity of Beta Lamp' })).toBeEnabled();
      expect(within(rowOf('Alpha Phone')).getByRole('button', { name: 'Increase quantity of Alpha Phone' })).toBeDisabled();
      expect(within(rowOf('Alpha Phone')).getByRole('button', { name: 'Decrease quantity of Alpha Phone' })).toBeEnabled();
    });

    it('turns Minus and Plus off while a change runs', async () => {
      const gate = deferred();
      const server = fakeCart(defaultCatalog(), { 'BETA-2': 2 });
      const put = server.routes['PUT /api/cart/items/BETA-2'];
      mockFetch({
        ...server.routes,
        'PUT /api/cart/items/BETA-2': async (url, init) => {
          await gate.promise;
          return put(url, init);
        },
      });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Increase quantity of Beta Lamp' }));

      expect(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Increase quantity of Beta Lamp' })).toBeDisabled();
      expect(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Decrease quantity of Beta Lamp' })).toBeDisabled();
      gate.resolve();
      await waitFor(() => expect(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Increase quantity of Beta Lamp' })).toBeEnabled());
    });

    it('shows the reason on the line when the server refuses a Plus click', async () => {
      setup({ 'BETA-2': 2 }, { 'PUT /api/cart/items/BETA-2': () => problem(503, 'Service Unavailable', 'Try again later') });
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Beta Lamp');

      await user.click(within(rowOf('Beta Lamp')).getByRole('button', { name: 'Increase quantity of Beta Lamp' }));

      expect(await within(rowOf('Beta Lamp')).findByRole('alert')).toHaveTextContent('Try again later');
    });
  });

  describe('the bar at the bottom', () => {
    it('holds the total and Place order only, and Empty cart is a quiet button by the heading', async () => {
      setup({ 'ALPHA-1': 1 });
      renderCart();
      await screen.findByText('Alpha Phone');

      const bar = screen.getByRole('button', { name: 'Place order' }).closest('.cart-bar') as HTMLElement;
      expect(within(bar).getAllByRole('button')).toHaveLength(1);
      expect(within(bar).queryByRole('button', { name: 'Empty cart' })).not.toBeInTheDocument();
      expect(within(bar).getByText('Total').nextElementSibling).toHaveTextContent('$799.99');

      const head = screen.getByRole('heading', { level: 1, name: 'Your cart' }).closest('header') as HTMLElement;
      const empty = within(head).getByRole('button', { name: 'Empty cart' });
      expect(empty).toHaveClass('link-button', 'cart-empty');
      expect(empty).not.toHaveClass('btn');
      expect(empty).toBeEnabled();
    });

    it('shows a reference when the order fails with a trace id', async () => {
      setup(
        { 'ALPHA-1': 1 },
        {
          'POST /api/orders': () =>
            new Response(JSON.stringify({ title: 'Conflict', status: 409, detail: 'Not enough stock for ALPHA-1: wanted 1, available 0' }), {
              status: 409,
              headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'abc123' },
            }),
        },
      );
      const user = userEvent.setup();
      renderCart();
      await screen.findByText('Alpha Phone');

      await user.click(screen.getByRole('button', { name: 'Place order' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Not enough stock for ALPHA-1: wanted 1, available 0. Reference: abc123');
    });
  });
});

describe('priceDetails', () => {
  const lines = [makeCartLine('A', 2, 80), makeCartLine('B', 1, 20)];
  const product = (sku: string, mrp?: number) => makeProduct(1, sku, sku, { price: 1, mrp });

  it('adds up the list prices and subtracts what the cart costs', () => {
    expect(priceDetails(lines, { A: product('A', 100), B: product('B', 25) }, 180)).toEqual({ price: 225, discount: 45 });
  });

  it('has no discount when one list price is missing or the product is unknown', () => {
    expect(priceDetails(lines, { A: product('A', 100), B: product('B') }, 180)).toEqual({ price: 180, discount: null });
    expect(priceDetails(lines, { A: product('A', 100) }, 180)).toEqual({ price: 180, discount: null });
  });

  it('has no discount when the list prices are not above the prices', () => {
    expect(priceDetails(lines, { A: product('A', 80), B: product('B', 20) }, 180)).toEqual({ price: 180, discount: null });
    expect(priceDetails(lines, { A: product('A', 50), B: product('B', 10) }, 180)).toEqual({ price: 180, discount: null });
  });

  it('ignores lines that can no longer be bought, and has no discount for an empty cart', () => {
    const gone = [makeCartLine('A', 1, 80), { sku: 'X', quantity: 4, available: false, name: null, unitPrice: null, lineTotal: null }];
    expect(priceDetails(gone, { A: product('A', 100) }, 80)).toEqual({ price: 100, discount: 20 });
    expect(priceDetails([], {}, 0)).toEqual({ price: 0, discount: null });
  });
});
