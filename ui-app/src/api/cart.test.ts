import { setUnauthorizedHandler } from '../auth/session';
import { json, makeCart, makeCartLine, mockFetch, noContent, problem, signInAs } from '../test/helpers';
import { clearCart, deleteCartItem, fetchCart, putCartItem } from './cart';

const cart = makeCart([makeCartLine('ALPHA-1', 2, 799.99, { name: 'Alpha Phone' })]);

describe('fetchCart', () => {
  it('asks for the cart with the stored token and returns it', async () => {
    signInAs('USER', { token: 'tok-user' });
    const fetchMock = mockFetch({ 'GET /api/cart': () => cart });

    await expect(fetchCart()).resolves.toEqual(cart);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/cart');
    expect(init?.method).toBe('GET');
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-user' });
  });

  it('sends the person to the login page when the token is no longer accepted', async () => {
    signInAs('USER');
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    mockFetch({ 'GET /api/cart': () => problem(401, 'Unauthorized') });

    await expect(fetchCart()).rejects.toMatchObject({ status: 401 });

    expect(goToLogin).toHaveBeenCalledTimes(1);
  });

  it('reports a product service that did not answer as a 503 with the reason', async () => {
    signInAs('USER');
    mockFetch({ 'GET /api/cart': () => problem(503, 'Service Unavailable', 'The product service did not answer. Please try again.') });
    await expect(fetchCart()).rejects.toMatchObject({ status: 503, detail: 'The product service did not answer. Please try again.' });
  });
});

describe('putCartItem', () => {
  it('puts the quantity for the SKU and returns the whole cart', async () => {
    signInAs('USER', { token: 'tok-user' });
    const fetchMock = mockFetch({ 'PUT /api/cart/items/ALPHA-1': () => cart });

    await expect(putCartItem('ALPHA-1', 2)).resolves.toEqual(cart);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/cart/items/ALPHA-1');
    expect(init?.method).toBe('PUT');
    expect(JSON.parse(String(init?.body))).toEqual({ quantity: 2 });
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-user', 'Content-Type': 'application/json' });
  });

  it('encodes a SKU that has odd characters', async () => {
    signInAs('USER');
    const fetchMock = mockFetch({ 'PUT /api/cart/items/A%20B%2FC': () => cart });
    await putCartItem('A B/C', 1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/cart/items/A%20B%2FC');
  });

  it('reports an unknown product as a 422 with the reason', async () => {
    signInAs('USER');
    mockFetch({ 'PUT /api/cart/items/GONE-1': () => problem(422, 'Unprocessable Entity', 'Unknown product: GONE-1') });
    await expect(putCartItem('GONE-1', 1)).rejects.toMatchObject({ status: 422, detail: 'Unknown product: GONE-1' });
  });

  it('reports a quantity the server refuses as a 400', async () => {
    signInAs('USER');
    mockFetch({ 'PUT /api/cart/items/ALPHA-1': () => problem(400, 'Bad Request', 'Quantity must be from 1 to 99') });
    await expect(putCartItem('ALPHA-1', 100)).rejects.toMatchObject({ status: 400 });
  });
});

describe('deleteCartItem', () => {
  it('deletes one SKU and resolves with nothing', async () => {
    signInAs('USER', { token: 'tok-user' });
    const fetchMock = mockFetch({ 'DELETE /api/cart/items/ALPHA-1': () => noContent() });

    await expect(deleteCartItem('ALPHA-1')).resolves.toBeUndefined();

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/cart/items/ALPHA-1');
    expect(init?.method).toBe('DELETE');
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-user' });
  });

  it('encodes a SKU that has odd characters', async () => {
    signInAs('USER');
    const fetchMock = mockFetch({ 'DELETE /api/cart/items/A%20B%2FC': () => noContent() });
    await deleteCartItem('A B/C');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/cart/items/A%20B%2FC');
  });
});

describe('clearCart', () => {
  it('deletes the whole cart and resolves with nothing', async () => {
    signInAs('USER');
    const fetchMock = mockFetch({ 'DELETE /api/cart': () => noContent() });

    await expect(clearCart()).resolves.toBeUndefined();

    expect(fetchMock.mock.calls[0][0]).toBe('/api/cart');
    expect(fetchMock.mock.calls[0][1]?.method).toBe('DELETE');
  });

  it('reports a failure', async () => {
    signInAs('USER');
    mockFetch({ 'DELETE /api/cart': () => json({ title: 'Forbidden', status: 403, detail: 'No access' }, 403) });
    await expect(clearCart()).rejects.toMatchObject({ status: 403 });
  });
});
