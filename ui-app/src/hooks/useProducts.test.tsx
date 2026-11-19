import { act, render, screen, waitFor } from '@testing-library/react';
import { makeProduct, mockFetch, problem } from '../test/helpers';
import { clearProductLookupCache, useProductsBySku } from './useProducts';

function Probe({ skus, label = 'probe' }: { skus: string[]; label?: string }) {
  const products = useProductsBySku(skus);
  return (
    <ul aria-label={label}>
      {skus.map((sku) => (
        <li key={sku}>{`${sku}: ${products[sku]?.name ?? 'unknown'}`}</li>
      ))}
    </ul>
  );
}

const asked = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.map(([path]) => String(path));

describe('useProductsBySku', () => {
  it('looks each SKU up once and fills it in when the answer arrives', async () => {
    const fetchMock = mockFetch({
      'GET /api/products/sku/A-1': () => makeProduct(1, 'Alpha', 'A-1'),
      'GET /api/products/sku/B-2': () => makeProduct(2, 'Beta', 'B-2'),
    });
    render(<Probe skus={['A-1', 'B-2']} />);

    expect(screen.getByText('A-1: unknown')).toBeInTheDocument();
    expect(await screen.findByText('A-1: Alpha')).toBeInTheDocument();
    expect(await screen.findByText('B-2: Beta')).toBeInTheDocument();
    expect(asked(fetchMock).sort()).toEqual(['/api/products/sku/A-1', '/api/products/sku/B-2']);
  });

  it('shares what it knows between components, so a SKU is asked for once', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/sku/A-1': () => makeProduct(1, 'Alpha', 'A-1') });
    render(
      <>
        <Probe skus={['A-1']} label="one" />
        <Probe skus={['A-1']} label="two" />
      </>,
    );

    await waitFor(() => expect(screen.getAllByText('A-1: Alpha')).toHaveLength(2));
    expect(asked(fetchMock)).toEqual(['/api/products/sku/A-1']);
  });

  it('does not ask again for a SKU it already knows, and only asks for the new ones', async () => {
    const fetchMock = mockFetch({
      'GET /api/products/sku/A-1': () => makeProduct(1, 'Alpha', 'A-1'),
      'GET /api/products/sku/B-2': () => makeProduct(2, 'Beta', 'B-2'),
    });
    const { rerender } = render(<Probe skus={['A-1']} />);
    await screen.findByText('A-1: Alpha');

    rerender(<Probe skus={['A-1', 'B-2']} />);

    expect(await screen.findByText('B-2: Beta')).toBeInTheDocument();
    expect(asked(fetchMock)).toEqual(['/api/products/sku/A-1', '/api/products/sku/B-2']);
  });

  it('leaves a product it could not find as unknown, without failing, and tries again on a later visit', async () => {
    let found = false;
    const fetchMock = mockFetch({
      'GET /api/products/sku/A-1': () => (found ? makeProduct(1, 'Alpha', 'A-1') : problem(404, 'Not Found')),
    });
    const first = render(<Probe skus={['A-1']} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    expect(screen.getByText('A-1: unknown')).toBeInTheDocument();
    first.unmount();

    found = true;
    render(<Probe skus={['A-1']} />);
    expect(await screen.findByText('A-1: Alpha')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('asks for nothing when there are no SKUs', () => {
    const fetchMock = mockFetch({});
    render(<Probe skus={[]} />);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('forgets everything when the cache is cleared', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/sku/A-1': () => makeProduct(1, 'Alpha', 'A-1') });
    const first = render(<Probe skus={['A-1']} />);
    await screen.findByText('A-1: Alpha');
    first.unmount();

    clearProductLookupCache();
    render(<Probe skus={['A-1']} />);

    expect(screen.getByText('A-1: unknown')).toBeInTheDocument();
    await screen.findByText('A-1: Alpha');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
