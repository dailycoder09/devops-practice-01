import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LowStockPanel } from './LowStockPanel';
import { makeItem, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

describe('Low stock panel on the overview', () => {
  it('lists the lowest five items with their quantity coloured, and links to the whole list', async () => {
    const fetchMock = mockFetch({
      'GET /api/inventory/low-stock': () =>
        pageOf([makeItem(1, 'ALPHA-1', 0, 10), makeItem(2, 'BRAVO-2', 4, 10)], { size: 5, totalElements: 331 }),
    });
    renderAt(<LowStockPanel />, '/admin');

    const alpha = (await screen.findByText('ALPHA-1')).closest('tr')!;
    expect(within(alpha).getByText('0')).toHaveClass('stock--out');
    expect(within(alpha).getByText('10')).toBeInTheDocument();
    expect(within(screen.getByText('BRAVO-2').closest('tr')!).getByText('4')).toHaveClass('stock--low');
    expect(requestedUrls(fetchMock)).toEqual(['/api/inventory/low-stock?page=0&size=5']);
    expect(screen.getByRole('link', { name: 'See all low stock' })).toHaveAttribute('href', '/admin/inventory?low=1');
  });

  it('says so when nothing is low on stock', async () => {
    mockFetch({ 'GET /api/inventory/low-stock': () => pageOf([]) });
    renderAt(<LowStockPanel />, '/admin');

    expect(await screen.findByText('Nothing is low on stock.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows a plain loading line first', async () => {
    mockFetch({ 'GET /api/inventory/low-stock': () => pageOf([]) });
    renderAt(<LowStockPanel />, '/admin');

    expect(screen.getByText('Loading low stock…')).toBeInTheDocument();
    await screen.findByText('Nothing is low on stock.');
  });

  it('shows the problem with a Try again button, and the list after the retry works', async () => {
    let calls = 0;
    mockFetch({
      'GET /api/inventory/low-stock': () =>
        ++calls === 1 ? problem(503, 'Service Unavailable') : pageOf([makeItem(1, 'ALPHA-1', 2, 10)]),
    });
    const user = userEvent.setup();
    renderAt(<LowStockPanel />, '/admin');

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the low stock list.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('ALPHA-1')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
