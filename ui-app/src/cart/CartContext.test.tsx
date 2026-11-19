import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { deferred, fakeCart, makeCart, makeCartLine, mockFetch, problem, renderAt, requestedUrls } from '../test/helpers';
import { useCart } from './CartContext';

const catalog = {
  'ALPHA-1': { name: 'Alpha Phone', price: 799.99 },
  'BETA-2': { name: 'Beta Lamp', price: 20 },
};

/** Shows what the context says and gives a button for each action. */
function Probe() {
  const cart = useCart();
  const { login, logout } = useAuth();
  const [failure, setFailure] = useState('');

  async function attempt(action: () => Promise<unknown>) {
    try {
      await action();
    } catch (error) {
      setFailure(errorMessage(error));
    }
  }

  return (
    <>
      <p data-testid="count">{cart.itemCount}</p>
      <p data-testid="total">{cart.total}</p>
      <p data-testid="lines">{cart.items.map((line) => `${line.sku}x${line.quantity}`).join(',')}</p>
      <p data-testid="loading">{String(cart.loading)}</p>
      <p data-testid="problem">{cart.error ? errorMessage(cart.error) : ''}</p>
      <p data-testid="failure">{failure}</p>
      <button type="button" onClick={() => attempt(() => cart.setQuantity('ALPHA-1', 2))}>
        set alpha 2
      </button>
      <button type="button" onClick={() => attempt(() => cart.setQuantity('GONE-1', 1))}>
        set gone 1
      </button>
      <button type="button" onClick={() => attempt(() => cart.remove('ALPHA-1'))}>
        remove alpha
      </button>
      <button type="button" onClick={() => attempt(cart.clear)}>
        empty it
      </button>
      <button type="button" onClick={() => attempt(cart.refresh)}>
        reload it
      </button>
      <button type="button" onClick={() => attempt(() => login('jane@example.com', 'secret123'))}>
        do login
      </button>
      <button type="button" onClick={logout}>
        do logout
      </button>
    </>
  );
}

/** The pretend server, except that GET /api/cart fails with a 503 until `isUp()` says otherwise. */
function downUntil(server: ReturnType<typeof fakeCart>, isUp: () => boolean, detail: string) {
  const answer = server.routes['GET /api/cart'];
  return {
    ...server.routes,
    'GET /api/cart': (url: URL, init?: RequestInit) => (isUp() ? answer(url, init) : problem(503, 'Service Unavailable', detail)),
  };
}

const count = () => screen.getByTestId('count');
const lines = () => screen.getByTestId('lines');
const loading = () => screen.getByTestId('loading');

describe('CartProvider', () => {
  it('does not ask for a cart when nobody is logged in', () => {
    const fetchMock = mockFetch({});
    renderAt(<Probe />);
    expect(count()).toHaveTextContent('0');
    expect(lines()).toHaveTextContent('');
    expect(loading()).toHaveTextContent('false');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('loads the cart of a person who is logged in, with their token, and says it is loading until then', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2, 'BETA-2': 1 });
    const fetchMock = mockFetch(server.routes);
    renderAt(<Probe />, '/', { auth: 'USER' });
    expect(loading()).toHaveTextContent('true');

    await waitFor(() => expect(count()).toHaveTextContent('3'));

    expect(lines()).toHaveTextContent('ALPHA-1x2,BETA-2x1');
    expect(screen.getByTestId('total')).toHaveTextContent('1619.98');
    expect(loading()).toHaveTextContent('false');
    expect(requestedUrls(fetchMock)).toEqual(['/api/cart']);
    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({ Authorization: 'Bearer user-token' });
  });

  it('loads the cart after a person logs in', async () => {
    const server = fakeCart(catalog, { 'BETA-2': 4 });
    const fetchMock = mockFetch({
      ...server.routes,
      'POST /api/users/login': () => ({ token: 'jwt-abc', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' }),
    });
    const user = userEvent.setup();
    renderAt(<Probe />);
    expect(requestedUrls(fetchMock)).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'do login' }));

    await waitFor(() => expect(count()).toHaveTextContent('4'));
    const cartCall = fetchMock.mock.calls.find(([path]) => path === '/api/cart');
    expect(cartCall?.[1]?.headers).toMatchObject({ Authorization: 'Bearer jwt-abc' });
  });

  it('empties itself when the person logs out', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2 });
    const fetchMock = mockFetch(server.routes);
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('2'));

    await user.click(screen.getByRole('button', { name: 'do logout' }));

    expect(count()).toHaveTextContent('0');
    expect(screen.getByTestId('total')).toHaveTextContent('0');
    expect(lines()).toHaveTextContent('');
    expect(loading()).toHaveTextContent('false');
    expect(requestedUrls(fetchMock)).toEqual(['/api/cart']);
  });

  it('ignores a cart that arrives after the person has logged out', async () => {
    const gate = deferred();
    const server = fakeCart(catalog, { 'ALPHA-1': 2 });
    mockFetch({
      ...server.routes,
      'GET /api/cart': async () => {
        await gate.promise;
        return makeCart([makeCartLine('ALPHA-1', 2, 799.99)]);
      },
    });
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });

    await user.click(screen.getByRole('button', { name: 'do logout' }));
    gate.resolve();

    // Give the late answer time to land. It must not bring the cart back.
    await new Promise((done) => setTimeout(done, 20));
    expect(count()).toHaveTextContent('0');
    expect(loading()).toHaveTextContent('false');
  });

  it('ignores a late cart of the first login after someone has logged in again', async () => {
    const gate = deferred();
    let asked = 0;
    mockFetch({
      'GET /api/cart': async () => {
        asked += 1;
        if (asked === 1) {
          await gate.promise;
          return makeCart([makeCartLine('ALPHA-1', 2, 799.99)]);
        }
        return makeCart([makeCartLine('BETA-2', 5, 20)]);
      },
      'POST /api/users/login': () => ({ token: 'jwt-2', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' }),
    });
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });

    await user.click(screen.getByRole('button', { name: 'do logout' }));
    await user.click(screen.getByRole('button', { name: 'do login' }));
    await waitFor(() => expect(lines()).toHaveTextContent('BETA-2x5'));
    gate.resolve();

    // Give the late answer time to land. It must not replace the cart of the person who is logged in now.
    await new Promise((done) => setTimeout(done, 20));
    expect(lines()).toHaveTextContent('BETA-2x5');
    expect(count()).toHaveTextContent('5');
    expect(loading()).toHaveTextContent('false');
  });

  it('setQuantity sends the quantity and shows the cart the server answers with', async () => {
    const server = fakeCart(catalog, { 'BETA-2': 1 });
    const fetchMock = mockFetch(server.routes);
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('1'));

    await user.click(screen.getByRole('button', { name: 'set alpha 2' }));

    await waitFor(() => expect(count()).toHaveTextContent('3'));
    expect(lines()).toHaveTextContent('BETA-2x1,ALPHA-1x2');
    expect(screen.getByTestId('total')).toHaveTextContent('1619.98');
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    expect(put[0]).toBe('/api/cart/items/ALPHA-1');
    expect(JSON.parse(String(put[1]?.body))).toEqual({ quantity: 2 });
    expect(screen.getByTestId('failure')).toHaveTextContent('');
  });

  it('setQuantity passes a refusal on to the caller and leaves the cart as it was', async () => {
    const server = fakeCart(catalog, { 'BETA-2': 1 });
    mockFetch({
      ...server.routes,
      'PUT /api/cart/items/GONE-1': () => problem(422, 'Unprocessable Entity', 'Unknown product: GONE-1'),
    });
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('1'));

    await user.click(screen.getByRole('button', { name: 'set gone 1' }));

    expect(await screen.findByText('Unknown product: GONE-1')).toBeInTheDocument();
    expect(count()).toHaveTextContent('1');
    expect(lines()).toHaveTextContent('BETA-2x1');
  });

  it('remove deletes the line and updates the count and the total', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2, 'BETA-2': 1 });
    const fetchMock = mockFetch(server.routes);
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('3'));

    await user.click(screen.getByRole('button', { name: 'remove alpha' }));

    await waitFor(() => expect(count()).toHaveTextContent('1'));
    expect(lines()).toHaveTextContent('BETA-2x1');
    expect(screen.getByTestId('total')).toHaveTextContent('20');
    expect(server.quantities.has('ALPHA-1')).toBe(false);
    const del = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')!;
    expect(del[0]).toBe('/api/cart/items/ALPHA-1');
  });

  it('remove leaves out of the total a line that is not available', async () => {
    const server = fakeCart(catalog, { 'GONE-9': 2, 'ALPHA-1': 1, 'BETA-2': 3 });
    mockFetch(server.routes);
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('6'));
    expect(screen.getByTestId('total')).toHaveTextContent('859.99');

    await user.click(screen.getByRole('button', { name: 'remove alpha' }));

    await waitFor(() => expect(count()).toHaveTextContent('5'));
    expect(screen.getByTestId('total')).toHaveTextContent('60');
  });

  it('remove keeps the line when the server refuses, and tells the caller', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2 });
    mockFetch({ ...server.routes, 'DELETE /api/cart/items/ALPHA-1': () => problem(503, 'Service Unavailable', 'Try later') });
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('2'));

    await user.click(screen.getByRole('button', { name: 'remove alpha' }));

    expect(await screen.findByText('Try later')).toBeInTheDocument();
    expect(count()).toHaveTextContent('2');
  });

  it('clear deletes the whole cart and shows it empty without asking again', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2, 'BETA-2': 1 });
    const fetchMock = mockFetch(server.routes);
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('3'));

    await user.click(screen.getByRole('button', { name: 'empty it' }));

    await waitFor(() => expect(count()).toHaveTextContent('0'));
    expect(lines()).toHaveTextContent('');
    expect(server.quantities.size).toBe(0);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([path]) => path)).toEqual(['/api/cart']);
    expect(requestedUrls(fetchMock).filter((path) => path === '/api/cart')).toHaveLength(2); // the load and the delete
  });

  it('keeps the error when the cart cannot be loaded, shows an empty cart, and refresh tries again', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2 });
    let up = false;
    mockFetch(downUntil(server, () => up, 'The product service did not answer. Please try again.'));
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });

    await waitFor(() => expect(screen.getByTestId('problem')).toHaveTextContent('The product service did not answer. Please try again.'));
    expect(count()).toHaveTextContent('0');
    expect(loading()).toHaveTextContent('false');

    up = true;
    await user.click(screen.getByRole('button', { name: 'reload it' }));

    await waitFor(() => expect(count()).toHaveTextContent('2'));
    expect(screen.getByTestId('problem')).toHaveTextContent('');
  });

  it('says the server could not be reached when the network fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    );
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(screen.getByTestId('problem')).toHaveTextContent('Could not reach the server'));
  });

  it('a successful change after a failed load shows the new cart and clears the error', async () => {
    const server = fakeCart(catalog, {});
    let up = false;
    mockFetch(downUntil(server, () => up, 'down'));
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(screen.getByTestId('problem')).toHaveTextContent('down'));

    up = true;
    await user.click(screen.getByRole('button', { name: 'set alpha 2' }));

    await waitFor(() => expect(count()).toHaveTextContent('2'));
    expect(screen.getByTestId('problem')).toHaveTextContent('');
  });

  it('shows the cart of the next person who logs in, not the one before', async () => {
    const server = fakeCart(catalog, { 'ALPHA-1': 2 });
    mockFetch(server.routes);
    const user = userEvent.setup();
    renderAt(<Probe />, '/', { auth: 'USER' });
    await waitFor(() => expect(count()).toHaveTextContent('2'));

    await user.click(screen.getByRole('button', { name: 'do logout' }));
    expect(count()).toHaveTextContent('0');
    server.quantities.clear();
    server.quantities.set('BETA-2', 5);
    mockFetch({
      ...server.routes,
      'POST /api/users/login': () => ({ token: 'jwt-2', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' }),
    });
    await user.click(screen.getByRole('button', { name: 'do login' }));

    await waitFor(() => expect(lines()).toHaveTextContent('BETA-2x5'));
    expect(count()).toHaveTextContent('5');
  });

  it('useCart outside the provider says what is wrong', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() =>
      render(
        <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <Probe />
        </MemoryRouter>,
      ),
    ).toThrow(/CartProvider/);
    quiet.mockRestore();
  });
});
