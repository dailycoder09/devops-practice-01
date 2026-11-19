import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppRoutes } from './App';
import { LOG_LEVEL_KEY } from './lib/logger';
import { makeProduct, mockFetch, neverAnswers, pageOf, problem, renderAt } from './test/helpers';

const lines = (method: 'error' | 'warn' | 'info' | 'debug') =>
  vi.mocked(console[method]).mock.calls.map(([line]) => String(line));

describe('logging in the app', () => {
  it('logs a login and a logout at info, without the email or the token', async () => {
    mockFetch({
      'POST /api/users/login': () => ({
        token: 'jwt-secret-value',
        expiresAt: '2099-01-01T00:00:00Z',
        email: 'jane@example.com',
        role: 'USER',
      }),
      'GET /api/cart': () => ({ items: [], itemCount: 0, total: 0 }),
    });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/login');

    await user.type(screen.getByLabelText('Email'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await waitFor(() => expect(lines('info').some((line) => line.includes('Logged in'))).toBe(true));

    await user.click(await screen.findByRole('button', { name: /jane@example\.com/ }));
    await user.click(screen.getByRole('button', { name: 'Log out' }));
    expect(lines('info').some((line) => line.includes('Logged out'))).toBe(true);

    const everything = JSON.stringify([
      vi.mocked(console.info).mock.calls,
      vi.mocked(console.warn).mock.calls,
      vi.mocked(console.error).mock.calls,
    ]);
    expect(everything).not.toContain('jwt-secret-value');
    expect(everything).not.toContain('jane@example.com');
    expect(everything).not.toContain('secret123');
    expect(lines('info')[0]).toMatch(/^\[INFO \d\d:\d\d:\d\d\.\d{3}\] Logged in$/);
  }, 20000);

  it('logs the end of a session at info', async () => {
    mockFetch({
      'GET /api/orders': () => problem(401, 'Unauthorized', 'The token has expired'),
      'GET /api/cart': () => problem(401, 'Unauthorized'),
    });
    renderAt(<AppRoutes />, '/orders', { auth: 'USER' });

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(lines('info').filter((line) => line.includes('Session ended'))).not.toHaveLength(0);
  });

  it('logs a failed request at warn for a 4xx, at error for a 5xx, with the trace id', async () => {
    mockFetch({
      'GET /api/products/1/details': () => new Response('{}', { status: 404, headers: { 'X-Trace-Id': 'trace-404' } }),
      'GET /api/products/2/details': () => new Response('{}', { status: 502, headers: { 'X-Trace-Id': 'trace-502' } }),
    });
    const first = renderAt(<AppRoutes />, '/products/1');
    await screen.findByRole('heading', { level: 1, name: 'Product not found' });
    first.unmount();
    renderAt(<AppRoutes />, '/products/2');
    await screen.findByRole('alert');

    const failed = (method: 'warn' | 'error', path: string) =>
      vi
        .mocked(console[method])
        .mock.calls.find(
          ([line, context]) => String(line).includes('Request failed') && (context as { path?: string })?.path === path,
        );
    expect(failed('warn', '/api/products/1/details')![1]).toMatchObject({
      method: 'GET',
      status: 404,
      traceId: 'trace-404',
    });
    expect(failed('error', '/api/products/2/details')![1]).toMatchObject({
      method: 'GET',
      status: 502,
      traceId: 'trace-502',
    });
    // A 4xx is not an error, and a 5xx is not just a warning.
    expect(failed('error', '/api/products/1/details')).toBeUndefined();
    expect(failed('warn', '/api/products/2/details')).toBeUndefined();
  });

  it('shows the trace id of a failed page load to the person as "Reference: ..."', async () => {
    mockFetch({
      'GET /api/products/2/details': () =>
        new Response(
          JSON.stringify({ title: 'Bad Gateway', status: 502, detail: 'The product service did not answer' }),
          {
            status: 502,
            headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'trace-502' },
          },
        ),
    });
    renderAt(<AppRoutes />, '/products/2');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      'Could not load the product. The product service did not answer. The product service may be down. Reference: trace-502',
    );
    // The sentence ends with the reference (the Try again button comes after it).
    expect(alert.querySelector('p')!.textContent!.endsWith('Reference: trace-502')).toBe(true);
  });

  it('shows no reference when the server sent no trace id', async () => {
    mockFetch({
      'GET /api/products/2/details': () => problem(502, 'Bad Gateway', 'The product service did not answer'),
    });
    renderAt(<AppRoutes />, '/products/2');

    expect(await screen.findByRole('alert')).not.toHaveTextContent('Reference');
  });

  it('logs every route change at debug, only the path, and only when the level is debug', async () => {
    neverAnswers();
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/login');
    expect(lines('debug')).toHaveLength(0);
    // Without the debug level nothing is written, whatever the person does.
    await user.click(
      within(screen.getByRole('navigation', { name: 'Account' })).getByRole('link', { name: 'Register' }),
    );
    expect(lines('debug')).toHaveLength(0);
  });

  it('writes the route lines when shop.logLevel is "debug"', async () => {
    localStorage.setItem(LOG_LEVEL_KEY, 'debug');
    mockFetch({
      'GET /api/products': () => pageOf([makeProduct(1, 'Alpha', 'A-1')]),
      'GET /api/products/search': () => pageOf([]),
    });
    const user = userEvent.setup();
    renderAt(<AppRoutes />, '/login');
    await user.click(
      within(screen.getByRole('navigation', { name: 'Account' })).getByRole('link', { name: 'Register' }),
    );
    await user.type(screen.getByRole('combobox', { name: 'Search products' }), 'secret words{Enter}');

    const routes = vi.mocked(console.debug).mock.calls.filter(([line]) => String(line).includes('Route'));
    expect(routes.map(([, context]) => (context as { path: string }).path)).toEqual([
      '/login',
      '/register',
      '/products',
    ]);
    expect(JSON.stringify(routes)).not.toContain('secret');
    expect(String(routes[0][0])).toMatch(/^\[DEBUG \d\d:\d\d:\d\d\.\d{3}\] Route$/);
  });
});
