import { ApiError } from './client';
import { json, makeAdminOrder, makeOrder, makeStats, mockFetch, pageOf, problem, signInAs } from '../test/helpers';
import { fetchAllOrders, fetchOrderStats, fetchOrders, placeOrder } from './orders';

describe('placeOrder', () => {
  it('posts with no body, with the stored token, and returns the order', async () => {
    signInAs('USER', { token: 'tok-user' });
    const order = makeOrder(12, { cartCleared: true });
    const fetchMock = mockFetch({ 'POST /api/orders': () => json(order, 201) });

    await expect(placeOrder()).resolves.toEqual(order);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/orders');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBeUndefined();
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-user' });
    expect(init?.headers).not.toHaveProperty('Content-Type');
  });

  it('reports a stock problem as a 409 with the reason', async () => {
    signInAs('USER');
    mockFetch({
      'POST /api/orders': () => problem(409, 'Conflict', 'Not enough stock for APPLE-IP15P-128: wanted 3, available 1'),
    });
    const failure = (await placeOrder().catch((error: unknown) => error)) as ApiError;
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 409, detail: 'Not enough stock for APPLE-IP15P-128: wanted 3, available 1' });
  });

  it('reports a stock service that did not answer as a 503 with the reason', async () => {
    signInAs('USER');
    mockFetch({
      'POST /api/orders': () => problem(503, 'Service Unavailable', 'The stock service did not answer. Please try again.'),
    });
    await expect(placeOrder()).rejects.toMatchObject({ status: 503, detail: 'The stock service did not answer. Please try again.' });
  });
});

describe('fetchOrders', () => {
  it('asks for one page of the caller\'s orders with the stored token', async () => {
    signInAs('USER', { token: 'tok-user' });
    const page = pageOf([makeOrder(2), makeOrder(1)]);
    const fetchMock = mockFetch({ 'GET /api/orders': () => page });

    await expect(fetchOrders(0, 20)).resolves.toEqual(page);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/orders?page=0&size=20');
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-user' });
  });

  it('asks for a later page', async () => {
    signInAs('USER');
    const fetchMock = mockFetch({ 'GET /api/orders': () => pageOf([], { number: 2, totalElements: 41 }) });
    await fetchOrders(2, 20);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/orders?page=2&size=20');
  });
});

describe('fetchAllOrders (administrators)', () => {
  it('asks for one page of every order, with the admin token, and returns the customer fields', async () => {
    signInAs('ADMIN', { token: 'tok-admin' });
    const page = pageOf([makeAdminOrder(2, 'jane@example.com'), makeAdminOrder(1, null)]);
    const fetchMock = mockFetch({ 'GET /api/orders/all': () => page });

    const result = await fetchAllOrders(0, 20);

    expect(result).toEqual(page);
    expect(result.content[0]).toMatchObject({ userId: 7, userEmail: 'jane@example.com' });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/orders/all?page=0&size=20');
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-admin' });
  });

  it('adds the status only when one is given', async () => {
    signInAs('ADMIN');
    const fetchMock = mockFetch({ 'GET /api/orders/all': () => pageOf([]) });

    await fetchAllOrders(2, 20, 'REJECTED');
    await fetchAllOrders(0, 20, undefined);

    expect(fetchMock.mock.calls[0][0]).toBe('/api/orders/all?page=2&size=20&status=REJECTED');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/orders/all?page=0&size=20');
  });

  it('reports a refusal for a user who is not an administrator', async () => {
    signInAs('USER');
    mockFetch({ 'GET /api/orders/all': () => problem(403, 'Forbidden') });
    await expect(fetchAllOrders(0, 20)).rejects.toMatchObject({ status: 403 });
  });
});

describe('fetchOrderStats (administrators)', () => {
  it('reads the numbers with the admin token', async () => {
    signInAs('ADMIN', { token: 'tok-admin' });
    const fetchMock = mockFetch({ 'GET /api/orders/stats': () => makeStats() });

    await expect(fetchOrderStats()).resolves.toEqual({
      totalOrders: 11,
      pending: 0,
      confirmed: 7,
      rejected: 4,
      revenue: 11195.66,
    });

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/orders/stats');
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-admin' });
  });
});
