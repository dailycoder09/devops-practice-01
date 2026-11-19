import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Inventory } from './Inventory';
import { makeItem, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

const items = [makeItem(1, 'ALPHA-1', 5, 10), makeItem(2, 'BRAVO-2', 0, 5), makeItem(3, 'CHARLIE-3', 300, 50)];

function rowFor(sku: string): HTMLElement {
  return screen.getByRole('button', { name: sku }).closest('tr')!;
}

describe('Inventory page', () => {
  it('colours the quantity by the item own reorder threshold', async () => {
    mockFetch({ 'GET /api/inventory': () => pageOf(items) });
    renderAt(<Inventory />, '/admin/inventory');

    expect(await screen.findByRole('button', { name: 'ALPHA-1' })).toBeInTheDocument();
    expect(within(rowFor('ALPHA-1')).getByText('5')).toHaveClass('stock--low');
    expect(within(rowFor('BRAVO-2')).getByText('0')).toHaveClass('stock--out');
    expect(within(rowFor('CHARLIE-3')).getByText('300')).toHaveClass('stock--ok');
    expect(screen.getByText('Showing 1 to 3 of 3 records')).toBeInTheDocument();
  });

  it('shows the problem detail inline on the row when the adjustment is rejected', async () => {
    const fetchMock = mockFetch({
      'GET /api/inventory': () => pageOf(items),
      'PATCH /api/inventory/sku/ALPHA-1/adjust': () =>
        problem(400, 'Bad Request', 'Cannot remove 50 units of ALPHA-1: only 5 on hand'),
    });
    const user = userEvent.setup();
    renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
    await screen.findByRole('button', { name: 'ALPHA-1' });

    const row = rowFor('ALPHA-1');
    const amount = within(row).getByRole('spinbutton');
    await user.clear(amount);
    await user.type(amount, '50');
    await user.click(within(row).getByRole('button', { name: /remove/i }));

    expect(await within(row).findByRole('alert')).toHaveTextContent('Cannot remove 50 units of ALPHA-1: only 5 on hand');
    // Nothing changed, the buttons are usable again, and other rows are untouched.
    expect(within(row).getByText('5')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: /remove/i })).toBeEnabled();
    expect(within(rowFor('BRAVO-2')).queryByRole('alert')).not.toBeInTheDocument();
    expect(requestedUrls(fetchMock)).toContain('/api/inventory/sku/ALPHA-1/adjust?delta=-50');
  });

  it('updates the row in place after a successful add', async () => {
    const fetchMock = mockFetch({
      'GET /api/inventory': () => pageOf(items),
      'PATCH /api/inventory/sku/ALPHA-1/adjust': () => makeItem(1, 'ALPHA-1', 8, 10),
    });
    const user = userEvent.setup();
    renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
    await screen.findByRole('button', { name: 'ALPHA-1' });

    const row = rowFor('ALPHA-1');
    const amount = within(row).getByRole('spinbutton');
    await user.clear(amount);
    await user.type(amount, '3');
    await user.click(within(row).getByRole('button', { name: /add/i }));

    expect(await within(row).findByText('8')).toHaveClass('stock--low');
    expect(row).toHaveClass('flash-a');
    expect(requestedUrls(fetchMock)).toContain('/api/inventory/sku/ALPHA-1/adjust?delta=3');
    // The list was not reloaded to show the change.
    expect(requestedUrls(fetchMock).filter((url) => url.startsWith('/api/inventory?'))).toHaveLength(1);
  });

  it('asks for a whole number instead of sending a bad amount', async () => {
    const fetchMock = mockFetch({ 'GET /api/inventory': () => pageOf(items) });
    const user = userEvent.setup();
    renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
    await screen.findByRole('button', { name: 'ALPHA-1' });

    const row = rowFor('ALPHA-1');
    await user.clear(within(row).getByRole('spinbutton'));
    await user.click(within(row).getByRole('button', { name: /add/i }));

    expect(await within(row).findByRole('alert')).toHaveTextContent('Enter a whole number of 1 or more.');
    expect(requestedUrls(fetchMock).some((url) => url.includes('/adjust'))).toBe(false);
  });

  it('says what went wrong when the list cannot load', async () => {
    mockFetch({ 'GET /api/inventory': () => problem(503, 'Service Unavailable') });
    renderAt(<Inventory />, '/admin/inventory');

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load inventory.');
    expect(screen.getByRole('alert')).toHaveTextContent('Service Unavailable');
  });

  describe('Add stock', () => {
    it('is available to an administrator', async () => {
      mockFetch({ 'GET /api/inventory': () => pageOf(items) });
      renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'ALPHA-1' });

      expect(screen.getByRole('columnheader', { name: 'Adjust stock' })).toBeInTheDocument();
      const row = rowFor('ALPHA-1');
      expect(within(row).getByRole('spinbutton')).toBeInTheDocument();
      expect(within(row).getByRole('button', { name: /add/i })).toBeInTheDocument();
      expect(within(row).getByRole('button', { name: /remove/i })).toBeInTheDocument();
      expect(screen.queryByText('Only an administrator can change stock.')).not.toBeInTheDocument();
    });

    it.each([
      ['a visitor who is not logged in', undefined],
      ['a logged-in user without the admin role', 'USER' as const],
    ])('is hidden from %s, and the list still shows', async (_who, auth) => {
      mockFetch({ 'GET /api/inventory': () => pageOf(items) });
      renderAt(<Inventory />, '/admin/inventory', { auth });
      await screen.findByRole('button', { name: 'ALPHA-1' });

      expect(within(rowFor('ALPHA-1')).getByText('5')).toHaveClass('stock--low');
      expect(screen.queryByRole('columnheader', { name: 'Adjust stock' })).not.toBeInTheDocument();
      expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^add$/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^remove$/i })).not.toBeInTheDocument();
      expect(screen.getByText('Only an administrator can change stock.')).toBeInTheDocument();
    });

    it('sends the admin token with the change', async () => {
      const fetchMock = mockFetch({
        'GET /api/inventory': () => pageOf(items),
        'PATCH /api/inventory/sku/ALPHA-1/adjust': () => makeItem(1, 'ALPHA-1', 6, 10),
      });
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'ALPHA-1' });

      await user.click(within(rowFor('ALPHA-1')).getByRole('button', { name: /add/i }));

      expect(await within(rowFor('ALPHA-1')).findByText('6')).toBeInTheDocument();
      const patch = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH');
      expect(patch?.[1]?.headers).toMatchObject({ Authorization: 'Bearer admin-token' });
    });
  });

  describe('Low stock only', () => {
    const lowItems = [makeItem(2, 'BRAVO-2', 0, 5), makeItem(1, 'ALPHA-1', 5, 10)];
    const urls = (fetchMock: ReturnType<typeof mockFetch>) =>
      requestedUrls(fetchMock).filter((url) => url.startsWith('/api/inventory'));

    function routes() {
      return {
        'GET /api/inventory': () => pageOf(items),
        'GET /api/inventory/low-stock': () => pageOf(lowItems, { totalElements: 2 }),
      };
    }

    it('is off at first, and the whole inventory is listed', async () => {
      const fetchMock = mockFetch(routes());
      renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'CHARLIE-3' });

      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).not.toBeChecked();
      expect(urls(fetchMock)).toEqual(['/api/inventory?page=0&size=20']);
    });

    it('lists only the low stock items from the low stock endpoint when it is turned on', async () => {
      const fetchMock = mockFetch(routes());
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'CHARLIE-3' });

      await user.click(screen.getByRole('checkbox', { name: 'Low stock only' }));

      expect(await screen.findByText('Showing 1 to 2 of 2 low stock records')).toBeInTheDocument();
      expect(urls(fetchMock)).toContain('/api/inventory/low-stock?page=0&size=20');
      expect(screen.queryByRole('button', { name: 'CHARLIE-3' })).not.toBeInTheDocument();
      // In the order the server gave: the lowest quantity first.
      expect(screen.getAllByRole('button', { name: /^(ALPHA|BRAVO)-/ }).map((button) => button.textContent)).toEqual([
        'BRAVO-2',
        'ALPHA-1',
      ]);
      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).toBeChecked();
    });

    it('shows the whole inventory again when it is turned off', async () => {
      mockFetch(routes());
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory?low=1', { auth: 'ADMIN' });
      await screen.findByText('Showing 1 to 2 of 2 low stock records');

      await user.click(screen.getByRole('checkbox', { name: 'Low stock only' }));

      expect(await screen.findByRole('button', { name: 'CHARLIE-3' })).toBeInTheDocument();
      expect(screen.getByText('Showing 1 to 3 of 3 records')).toBeInTheDocument();
      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).not.toBeChecked();
    });

    it('opens already turned on when the address says low=1 (the link from the overview)', async () => {
      const fetchMock = mockFetch(routes());
      renderAt(<Inventory />, '/admin/inventory?low=1', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'BRAVO-2' });

      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).toBeChecked();
      expect(urls(fetchMock)).toEqual(['/api/inventory/low-stock?page=0&size=20']);
    });

    it('goes back to the first page when it is turned on', async () => {
      const fetchMock = mockFetch({
        'GET /api/inventory': () => pageOf(items, { number: 2, size: 20, totalElements: 60 }),
        'GET /api/inventory/low-stock': () => pageOf(lowItems),
      });
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory?page=3', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'ALPHA-1' });
      expect(urls(fetchMock)[0]).toBe('/api/inventory?page=2&size=20');

      await user.click(screen.getByRole('checkbox', { name: 'Low stock only' }));

      expect(await screen.findByRole('button', { name: 'BRAVO-2' })).toBeInTheDocument();
      expect(urls(fetchMock).slice(-1)[0]).toBe('/api/inventory/low-stock?page=0&size=20');
    });

    it('keeps the low stock list when it moves to the next page', async () => {
      const fetchMock = mockFetch({
        'GET /api/inventory': () => pageOf(items),
        'GET /api/inventory/low-stock': (url) =>
          pageOf(lowItems, { number: Number(url.searchParams.get('page')), size: 20, totalElements: 45 }),
      });
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory?low=1', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'BRAVO-2' });

      await user.click(screen.getByRole('button', { name: 'Next' }));

      expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
      expect(urls(fetchMock).slice(-1)[0]).toBe('/api/inventory/low-stock?page=1&size=20');
      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).toBeChecked();
    });

    it('says so when nothing is low on stock', async () => {
      mockFetch({ ...routes(), 'GET /api/inventory/low-stock': () => pageOf([]) });
      renderAt(<Inventory />, '/admin/inventory?low=1', { auth: 'ADMIN' });

      expect(await screen.findByText('Nothing is low on stock.')).toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });

    it('still lets an administrator add stock on the low stock list', async () => {
      const fetchMock = mockFetch({
        ...routes(),
        'PATCH /api/inventory/sku/BRAVO-2/adjust': () => makeItem(2, 'BRAVO-2', 10, 5),
      });
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory?low=1', { auth: 'ADMIN' });
      await screen.findByRole('button', { name: 'BRAVO-2' });

      const row = rowFor('BRAVO-2');
      await user.clear(within(row).getByRole('spinbutton'));
      await user.type(within(row).getByRole('spinbutton'), '10');
      await user.click(within(row).getByRole('button', { name: /add/i }));

      expect(await within(row).findByText('10', { selector: '.stock' })).toHaveClass('stock--ok');
      expect(requestedUrls(fetchMock)).toContain('/api/inventory/sku/BRAVO-2/adjust?delta=10');
    });

    it('shows the problem and a Try again button when the low stock list cannot load', async () => {
      let calls = 0;
      mockFetch({
        ...routes(),
        'GET /api/inventory/low-stock': () => (++calls === 1 ? problem(503, 'Service Unavailable') : pageOf(lowItems)),
      });
      const user = userEvent.setup();
      renderAt(<Inventory />, '/admin/inventory?low=1', { auth: 'ADMIN' });

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not load inventory.');
      await user.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByRole('button', { name: 'BRAVO-2' })).toBeInTheDocument();
      // The toggle is still there to go back to the whole list.
      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).toBeChecked();
    });

    it('is there for a visitor too (the reads are public), without the stock controls', async () => {
      mockFetch(routes());
      renderAt(<Inventory />, '/admin/inventory?low=1');
      await screen.findByRole('button', { name: 'BRAVO-2' });

      expect(screen.getByRole('checkbox', { name: 'Low stock only' })).toBeInTheDocument();
      expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
    });
  });
});
