import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ServiceStatus } from './ServiceStatus';
import { deferred, makeItem, makeProduct, makeStats, makeUser, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

/** What each of the five cheap checks asks for, all answering. */
function allUp() {
  return {
    'GET /api/products': () => pageOf([makeProduct(1, 'Alpha', 'A-1')], { size: 1, totalElements: 5010 }),
    'GET /api/inventory': () => pageOf([makeItem(1, 'A-1', 5, 1)], { size: 1, totalElements: 4980 }),
    'GET /api/users': () => pageOf([makeUser(1, 'admin@example.com', 'ADMIN')], { size: 1 }),
    'GET /api/cart': () => ({ items: [], itemCount: 0, total: 0 }),
    'GET /api/orders/stats': () => makeStats(),
  };
}

const rowOf = (name: string) => screen.getByText(name).closest('li')!;

describe('Service status panel', () => {
  it('asks each of the five services one cheap question', async () => {
    const fetchMock = mockFetch(allUp());
    renderAt(<ServiceStatus />, '/admin');

    expect(await screen.findByText('All 5 services answered.')).toBeInTheDocument();
    const urls = requestedUrls(fetchMock);
    for (const url of [
      '/api/products?size=1',
      '/api/inventory?size=1',
      '/api/users?size=1',
      '/api/cart',
      '/api/orders/stats',
    ]) {
      expect(urls).toContain(url);
    }
    expect(urls).toHaveLength(5);
  });

  it('shows a green dot, the word Answering, the time it took and when it was checked, for each service', async () => {
    mockFetch(allUp());
    renderAt(<ServiceStatus />, '/admin', { auth: 'ADMIN' });
    await screen.findByText('All 5 services answered.');

    for (const name of ['Product service', 'Inventory service', 'User service', 'Cart service', 'Order service']) {
      const row = rowOf(name);
      expect(within(row).getByText('Answering')).toBeInTheDocument();
      expect(within(row).getByText(/^\d+ ms$/)).toBeInTheDocument();
      expect(within(row).getByText(/^Checked at /)).toBeInTheDocument();
      expect(row.querySelector('.led')).toHaveClass('led--up');
    }
  });

  it('shows a red dot and the reason for a service that does not answer, and keeps the others green', async () => {
    mockFetch({ ...allUp(), 'GET /api/cart': () => problem(503, 'Service Unavailable', 'no healthy targets') });
    renderAt(<ServiceStatus />, '/admin', { auth: 'ADMIN' });

    expect(await screen.findByText('1 service did not answer.')).toBeInTheDocument();
    const down = rowOf('Cart service');
    expect(within(down).getByText('Not answering')).toBeInTheDocument();
    expect(within(down).getByText('no healthy targets.')).toBeInTheDocument();
    expect(down.querySelector('.led')).toHaveClass('led--down');
    expect(rowOf('Product service').querySelector('.led')).toHaveClass('led--up');
  });

  it('ends the reason with the reference of the server when it sent one', async () => {
    mockFetch({
      ...allUp(),
      'GET /api/cart': () =>
        new Response(JSON.stringify({ title: 'Service Unavailable', status: 503, detail: 'no healthy targets' }), {
          status: 503,
          headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'abc123def456' },
        }),
    });
    renderAt(<ServiceStatus />, '/admin');

    expect(await screen.findByText('no healthy targets. Reference: abc123def456')).toBeInTheDocument();
  });

  it('counts a service that cannot be reached as not answering', async () => {
    mockFetch({
      ...allUp(),
      'GET /api/users': () => {
        throw new TypeError('Failed to fetch');
      },
      'GET /api/orders/stats': () => problem(500, 'Internal Server Error'),
    });
    renderAt(<ServiceStatus />, '/admin', { auth: 'ADMIN' });

    expect(await screen.findByText('2 services did not answer.')).toBeInTheDocument();
    expect(within(rowOf('User service')).getByText('Could not reach the server.')).toBeInTheDocument();
    expect(within(rowOf('Order service')).getByText('Internal Server Error.')).toBeInTheDocument();
  });

  it('says it is checking while the answers are on their way', async () => {
    const hold = deferred<unknown>();
    mockFetch({ ...allUp(), 'GET /api/cart': () => hold.promise });
    renderAt(<ServiceStatus />, '/admin', { auth: 'ADMIN' });

    expect(screen.getByText('Checking the services…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Check again' })).toBeDisabled();
    await within(rowOf('Product service')).findByText('Answering');
    expect(within(rowOf('Cart service')).getByText('Checking…')).toBeInTheDocument();

    hold.resolve({ items: [], itemCount: 0, total: 0 });
    expect(await screen.findByText('All 5 services answered.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Check again' })).toBeEnabled();
  });

  it('asks all five again when "Check again" is pressed, and shows a service that has come back', async () => {
    let cartCalls = 0;
    const fetchMock = mockFetch({
      ...allUp(),
      'GET /api/cart': () =>
        ++cartCalls === 1 ? problem(503, 'Service Unavailable') : { items: [], itemCount: 0, total: 0 },
    });
    const user = userEvent.setup();
    renderAt(<ServiceStatus />, '/admin');
    expect(await screen.findByText('1 service did not answer.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Check again' }));

    expect(await screen.findByText('All 5 services answered.')).toBeInTheDocument();
    expect(within(rowOf('Cart service')).getByText('Answering')).toBeInTheDocument();
    expect(requestedUrls(fetchMock)).toHaveLength(10);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Check again' })).toBeEnabled());
  });

  it('sends the admin token with every check', async () => {
    const fetchMock = mockFetch(allUp());
    renderAt(<ServiceStatus />, '/admin', { auth: 'ADMIN' }); // the cart provider asks for the cart too
    await screen.findByText('All 5 services answered.');

    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(5);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init?.headers).toMatchObject({ Authorization: 'Bearer admin-token' });
    }
  });
});
