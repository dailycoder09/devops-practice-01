import { makeItem, mockFetch, pageOf } from '../test/helpers';
import { fetchLowStock, fetchLowStockCount } from './inventory';

describe('low stock', () => {
  it('asks the low stock endpoint for one page and returns it as it is', async () => {
    const page = pageOf([makeItem(2, 'BRAVO-2', 0, 5), makeItem(1, 'ALPHA-1', 5, 10)]);
    const fetchMock = mockFetch({ 'GET /api/inventory/low-stock': () => page });

    await expect(fetchLowStock(0, 20)).resolves.toEqual(page);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/inventory/low-stock?page=0&size=20');
  });

  it('counts the low stock items from the total of a page of one', async () => {
    const fetchMock = mockFetch({
      'GET /api/inventory/low-stock': () => pageOf([makeItem(2, 'BRAVO-2', 0, 5)], { size: 1, totalElements: 331 }),
    });

    await expect(fetchLowStockCount()).resolves.toBe(331);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/inventory/low-stock?size=1');
  });
});
