import { setUnauthorizedHandler } from '../auth/session';
import { ApiError, deleteNoContent, errorMessage, getJson, patchJson, postJson, putJson, withQuery } from './client';

function stubFetch(result: Response) {
  const fetchMock = vi.fn(async () => result);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function stubFetchFailure(reason: unknown) {
  const fetchMock = vi.fn(async () => {
    throw reason;
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function jsonResponse(body: unknown, status = 200, type = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
}

describe('getJson', () => {
  it('returns the parsed body and bypasses the HTTP cache', async () => {
    const fetchMock = stubFetch(jsonResponse({ hello: 'world' }));
    await expect(getJson('/api/thing')).resolves.toEqual({ hello: 'world' });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/thing',
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    );
  });

  it('turns a problem+json body into an ApiError with the detail', async () => {
    stubFetch(jsonResponse({ title: 'Bad Request', status: 400, detail: 'Stock cannot go below zero' }, 400, 'application/problem+json'));
    const failure = await getJson('/api/thing').catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 400, title: 'Bad Request', detail: 'Stock cannot go below zero' });
    expect((failure as ApiError).message).toBe('Stock cannot go below zero');
  });

  it('uses the title alone when a problem body has no detail', async () => {
    stubFetch(jsonResponse({ title: 'Not Found', status: 404 }, 404, 'application/problem+json'));
    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;
    expect(failure.status).toBe(404);
    expect(failure.detail).toBeUndefined();
    expect(errorMessage(failure)).toBe('Not Found');
  });

  it('falls back to "HTTP <status>" when the error body is not JSON', async () => {
    stubFetch(new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }));
    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(502);
    expect(failure.title).toBe('HTTP 502');
    expect(failure.detail).toBeUndefined();
  });

  it('says the server could not be reached when the network fails', async () => {
    stubFetchFailure(new TypeError('Failed to fetch'));
    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(0);
    expect(failure.title).toBe('Could not reach the server');
    expect(errorMessage(failure)).toBe('Could not reach the server');
  });

  it('lets an abort through untouched so callers can ignore it', async () => {
    const abort = new DOMException('Aborted', 'AbortError');
    stubFetchFailure(abort);
    await expect(getJson('/api/thing')).rejects.toBe(abort);
  });

  it('explains a successful response that is not JSON', async () => {
    stubFetch(new Response('<!doctype html><title>app</title>', { status: 200, headers: { 'Content-Type': 'text/html' } }));
    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.title).toBe('Unexpected response');
    expect(failure.detail).toMatch(/load balancer/);
  });
});

describe('patchJson', () => {
  it('sends a PATCH', async () => {
    const fetchMock = stubFetch(jsonResponse({ quantityOnHand: 8 }));
    await expect(patchJson('/api/inventory/sku/A/adjust?delta=3')).resolves.toEqual({ quantityOnHand: 8 });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/inventory/sku/A/adjust?delta=3',
      expect.objectContaining({ method: 'PATCH', cache: 'no-store' }),
    );
  });
});

describe('withQuery', () => {
  it('skips empty values and encodes the rest', () => {
    expect(withQuery('/p', { a: 'x y', b: '', c: undefined, d: null, e: 0 })).toBe('/p?a=x+y&e=0');
  });

  it('returns the bare path when nothing is left', () => {
    expect(withQuery('/p', { a: '' })).toBe('/p');
  });
});

describe('postJson', () => {
  it('sends the body as JSON with a POST', async () => {
    const fetchMock = stubFetch(jsonResponse({ id: 1 }, 201));
    await expect(postJson('/api/thing', { name: 'x' })).resolves.toEqual({ id: 1 });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/thing',
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        body: JSON.stringify({ name: 'x' }),
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
  });
});

describe('postJson without a body', () => {
  it('sends a POST with no body and no content type, as placing an order does', async () => {
    const fetchMock = stubFetch(jsonResponse({ id: 1 }, 201));
    await expect(postJson('/api/orders', undefined)).resolves.toEqual({ id: 1 });
    const init = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init[1].method).toBe('POST');
    expect(init[1].body).toBeUndefined();
    expect(init[1].headers).not.toHaveProperty('Content-Type');
  });
});

describe('putJson', () => {
  it('sends the body as JSON with a PUT', async () => {
    const fetchMock = stubFetch(jsonResponse({ itemCount: 3 }));
    await expect(putJson('/api/cart/items/A', { quantity: 3 })).resolves.toEqual({ itemCount: 3 });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/cart/items/A',
      expect.objectContaining({
        method: 'PUT',
        cache: 'no-store',
        body: JSON.stringify({ quantity: 3 }),
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
  });

  it('turns a problem+json answer into an ApiError with the detail', async () => {
    stubFetch(jsonResponse({ title: 'Unprocessable Entity', status: 422, detail: 'Unknown product: A' }, 422, 'application/problem+json'));
    await expect(putJson('/api/cart/items/A', { quantity: 1 })).rejects.toMatchObject({ status: 422, detail: 'Unknown product: A' });
  });
});

describe('deleteNoContent', () => {
  it('sends a DELETE and resolves with nothing when the answer is 204', async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    await expect(deleteNoContent('/api/cart')).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith('/api/cart', expect.objectContaining({ method: 'DELETE', cache: 'no-store' }));
  });

  it('does not need a JSON body to count as done', async () => {
    stubFetch(new Response('', { status: 200 }));
    await expect(deleteNoContent('/api/cart')).resolves.toBeUndefined();
  });

  it('turns an error answer into an ApiError', async () => {
    stubFetch(jsonResponse({ title: 'Service Unavailable', status: 503, detail: 'Try again' }, 503, 'application/problem+json'));
    await expect(deleteNoContent('/api/cart')).rejects.toMatchObject({ status: 503, detail: 'Try again' });
  });

  it('says the server could not be reached when the network fails', async () => {
    stubFetchFailure(new TypeError('Failed to fetch'));
    await expect(deleteNoContent('/api/cart')).rejects.toMatchObject({ status: 0, title: 'Could not reach the server' });
  });

  it('lets an abort through untouched', async () => {
    const abort = new DOMException('Aborted', 'AbortError');
    stubFetchFailure(abort);
    await expect(deleteNoContent('/api/cart')).rejects.toBe(abort);
  });
});

describe('login token', () => {
  function storeLogin(overrides: Record<string, unknown> = {}) {
    localStorage.setItem(
      'shop.auth',
      JSON.stringify({
        token: 'tok-123',
        expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
        email: 'admin@example.com',
        role: 'ADMIN',
        ...overrides,
      }),
    );
  }

  /** A fresh Response for every call, because a body can be read only once. */
  function stubFetchWith(makeResponse: () => Response) {
    const fetchMock = vi.fn(async (_path: string, _init?: RequestInit) => makeResponse());
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  function headersOf(fetchMock: ReturnType<typeof stubFetchWith>, call = 0): Record<string, string> {
    return (fetchMock.mock.calls[call][1]?.headers ?? {}) as Record<string, string>;
  }

  it('sends the token as a Bearer header on reads and on changes', async () => {
    storeLogin();
    const fetchMock = stubFetchWith(() => jsonResponse({ ok: true }));
    await getJson('/api/thing');
    await patchJson('/api/thing');
    await postJson('/api/thing', {});
    await putJson('/api/thing', {});
    await deleteNoContent('/api/thing');
    for (const call of [0, 1, 2, 3, 4]) {
      expect(headersOf(fetchMock, call).Authorization).toBe('Bearer tok-123');
    }
  });

  it('sends no Authorization header when nobody is logged in', async () => {
    const fetchMock = stubFetchWith(() => jsonResponse({ ok: true }));
    await getJson('/api/thing');
    expect(headersOf(fetchMock)).not.toHaveProperty('Authorization');
  });

  it('does not send a token that has run out, and removes it', async () => {
    storeLogin({ expiresAt: new Date(Date.now() - 1000).toISOString() });
    const fetchMock = stubFetchWith(() => jsonResponse({ ok: true }));
    await getJson('/api/thing');
    expect(headersOf(fetchMock)).not.toHaveProperty('Authorization');
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('on a 401 forgets the token, goes to the login page and still reports the failure', async () => {
    storeLogin();
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    stubFetchWith(() => jsonResponse({ title: 'Unauthorized', status: 401, detail: 'Token has expired' }, 401, 'application/problem+json'));

    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 401, detail: 'Token has expired' });
    expect(localStorage.getItem('shop.auth')).toBeNull();
    expect(goToLogin).toHaveBeenCalledTimes(1);
  });

  it('treats a 401 on a change the same way', async () => {
    storeLogin();
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    stubFetchWith(() => jsonResponse({ title: 'Unauthorized', status: 401 }, 401, 'application/problem+json'));

    await expect(patchJson('/api/thing')).rejects.toBeInstanceOf(ApiError);

    expect(localStorage.getItem('shop.auth')).toBeNull();
    expect(goToLogin).toHaveBeenCalledTimes(1);
  });

  it('treats a 401 on a delete the same way', async () => {
    storeLogin();
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    stubFetchWith(() => jsonResponse({ title: 'Unauthorized', status: 401 }, 401, 'application/problem+json'));

    await expect(deleteNoContent('/api/cart')).rejects.toMatchObject({ status: 401 });

    expect(localStorage.getItem('shop.auth')).toBeNull();
    expect(goToLogin).toHaveBeenCalledTimes(1);
  });

  it('does not crash when nothing is listening for a 401', async () => {
    storeLogin();
    stubFetchWith(() => jsonResponse({ title: 'Unauthorized', status: 401 }, 401, 'application/problem+json'));
    await expect(getJson('/api/thing')).rejects.toMatchObject({ status: 401 });
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('keeps the token and stays on the page for other errors, including 403', async () => {
    storeLogin();
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    for (const status of [400, 403, 404, 500]) {
      stubFetchWith(() => jsonResponse({ title: 'Nope', status }, status, 'application/problem+json'));
      await expect(patchJson('/api/thing')).rejects.toMatchObject({ status });
    }
    expect(localStorage.getItem('shop.auth')).not.toBeNull();
    expect(goToLogin).not.toHaveBeenCalled();
  });

  it('sends no token for an anonymous request such as login, and a 401 from it changes nothing', async () => {
    storeLogin();
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    const fetchMock = stubFetchWith(() => jsonResponse({ title: 'Unauthorized', status: 401 }, 401, 'application/problem+json'));

    await expect(postJson('/api/users/login', {}, { anonymous: true })).rejects.toMatchObject({ status: 401 });

    expect(headersOf(fetchMock)).not.toHaveProperty('Authorization');
    expect(localStorage.getItem('shop.auth')).not.toBeNull();
    expect(goToLogin).not.toHaveBeenCalled();
  });
});

describe('trace id', () => {
  function withTrace(body: unknown, status: number, traceId?: string) {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/problem+json', ...(traceId ? { 'X-Trace-Id': traceId } : {}) },
    });
  }

  it('is read from the X-Trace-Id header of an error answer', async () => {
    stubFetch(withTrace({ title: 'Internal Server Error', status: 500, detail: 'Boom' }, 500, '4bf92f3577b34da6'));
    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;
    expect(failure.traceId).toBe('4bf92f3577b34da6');
    expect(failure.detail).toBe('Boom');
  });

  it('is read when the error body is not JSON either', async () => {
    stubFetch(new Response('Bad gateway', { status: 502, headers: { 'X-Trace-Id': 'abc' } }));
    const failure = (await getJson('/api/thing').catch((error: unknown) => error)) as ApiError;
    expect(failure.traceId).toBe('abc');
  });

  it('is left out when the server sent none, or only blanks', async () => {
    stubFetch(withTrace({ title: 'Not Found', status: 404 }, 404));
    expect(((await getJson('/api/thing').catch((error: unknown) => error)) as ApiError).traceId).toBeUndefined();

    stubFetch(new Response('{}', { status: 404, headers: { 'X-Trace-Id': '   ' } }));
    expect(((await getJson('/api/thing').catch((error: unknown) => error)) as ApiError).traceId).toBeUndefined();
  });

  it('is read for a success answer that is not JSON, and for a delete that fails', async () => {
    stubFetch(new Response('<html></html>', { status: 200, headers: { 'X-Trace-Id': 'page-1' } }));
    expect(((await getJson('/api/thing').catch((error: unknown) => error)) as ApiError).traceId).toBe('page-1');

    stubFetch(withTrace({ title: 'Service Unavailable', status: 503 }, 503, 'del-1'));
    expect(((await deleteNoContent('/api/cart').catch((error: unknown) => error)) as ApiError).traceId).toBe('del-1');
  });

  it('is part of every ApiError, and a network failure has none', async () => {
    stubFetchFailure(new TypeError('Failed to fetch'));
    expect(((await getJson('/api/thing').catch((error: unknown) => error)) as ApiError).traceId).toBeUndefined();
    expect(new ApiError(500, 'x', 'y', 'abc').traceId).toBe('abc');
  });
});

describe('logging of failed requests', () => {
  const lines = (method: 'error' | 'warn') => vi.mocked(console[method]).mock.calls;

  it('logs a 4xx at warn with method, path, status and trace id', async () => {
    stubFetch(new Response('{}', { status: 409, headers: { 'X-Trace-Id': 'abc123' } }));
    await postJson('/api/orders', undefined).catch(() => undefined);

    expect(lines('warn')).toHaveLength(1);
    expect(String(lines('warn')[0][0])).toMatch(/^\[WARN \d\d:\d\d:\d\d\.\d{3}\] Request failed$/);
    expect(lines('warn')[0][1]).toEqual({ method: 'POST', path: '/api/orders', status: 409, traceId: 'abc123' });
    expect(lines('error')).toHaveLength(0);
  });

  it('logs a 5xx at error', async () => {
    stubFetch(new Response('{}', { status: 503, headers: { 'X-Trace-Id': 'zzz' } }));
    await getJson('/api/cart').catch(() => undefined);

    expect(lines('error')).toHaveLength(1);
    expect(lines('error')[0][1]).toMatchObject({ method: 'GET', path: '/api/cart', status: 503, traceId: 'zzz' });
    expect(lines('warn')).toHaveLength(0);
  });

  it('logs a network failure at error, with status 0', async () => {
    stubFetchFailure(new TypeError('Failed to fetch'));
    await getJson('/api/cart').catch(() => undefined);

    expect(lines('error')).toHaveLength(1);
    expect(lines('error')[0][1]).toMatchObject({ method: 'GET', path: '/api/cart', status: 0 });
  });

  it('logs only the path: no query string, so a search text never reaches the console', async () => {
    stubFetch(new Response('{}', { status: 404 }));
    await getJson('/api/products/search?searchTerm=my%20secret%20thing&page=0').catch(() => undefined);

    expect(lines('warn')[0][1]).toMatchObject({ path: '/api/products/search' });
    expect(JSON.stringify(lines('warn'))).not.toContain('secret');
  });

  it('never logs the token or the request body', async () => {
    localStorage.setItem(
      'shop.auth',
      JSON.stringify({ token: 'super-secret-jwt', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'jane@example.com', role: 'USER' }),
    );
    stubFetch(new Response('{}', { status: 400 }));
    await postJson('/api/users/login', { email: 'jane@example.com', password: 'hunter2hunter2' }).catch(() => undefined);

    const everything = JSON.stringify([lines('warn'), lines('error')]);
    expect(everything).not.toContain('super-secret-jwt');
    expect(everything).not.toContain('hunter2');
    expect(everything).not.toContain('jane@example.com');
  });

  it('does not log an aborted request: nobody failed', async () => {
    stubFetchFailure(new DOMException('Aborted', 'AbortError'));
    await getJson('/api/thing').catch(() => undefined);

    expect(lines('warn')).toHaveLength(0);
    expect(lines('error')).toHaveLength(0);
  });

  it('does not log a request that worked', async () => {
    stubFetch(jsonResponse({ ok: true }));
    await getJson('/api/thing');

    expect(lines('warn')).toHaveLength(0);
    expect(lines('error')).toHaveLength(0);
  });
});
