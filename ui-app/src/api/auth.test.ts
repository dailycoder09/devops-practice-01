import { setUnauthorizedHandler } from '../auth/session';
import { ApiError } from './client';
import { fetchMe, login, register } from './auth';
import { json, mockFetch, problem, signInAs } from '../test/helpers';

describe('login', () => {
  it('posts the email and password and returns the token details', async () => {
    const answer = { token: 'jwt-abc', expiresAt: '2026-11-20T10:00:00Z', email: 'jane@example.com', role: 'USER' };
    const fetchMock = mockFetch({ 'POST /api/users/login': () => answer });

    await expect(login('jane@example.com', 'secret123')).resolves.toEqual(answer);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/users/login');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'jane@example.com', password: 'secret123' });
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' });
  });

  it('never sends an old token, even when one is stored', async () => {
    signInAs('USER');
    const fetchMock = mockFetch({ 'POST /api/users/login': () => ({ token: 't', expiresAt: 'x', email: 'a@b.co', role: 'USER' }) });
    await login('a@b.co', 'secret123');
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty('Authorization');
  });

  it('reports a wrong password as a 401 without leaving the page or touching the stored login', async () => {
    signInAs('USER');
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    mockFetch({ 'POST /api/users/login': () => problem(401, 'Unauthorized', 'Invalid email or password') });

    const failure = (await login('jane@example.com', 'wrong-password').catch((error: unknown) => error)) as ApiError;

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(401);
    expect(goToLogin).not.toHaveBeenCalled();
    expect(localStorage.getItem('shop.auth')).not.toBeNull();
  });
});

describe('register', () => {
  it('posts the email and password and returns the new account', async () => {
    const fetchMock = mockFetch({ 'POST /api/users/register': () => json({ id: 7, email: 'new@example.com', role: 'USER' }, 201) });

    await expect(register('new@example.com', 'secret123')).resolves.toEqual({ id: 7, email: 'new@example.com', role: 'USER' });

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/users/register');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'new@example.com', password: 'secret123' });
  });

  it('reports an email that is already used as a 409', async () => {
    mockFetch({ 'POST /api/users/register': () => problem(409, 'Conflict', 'Email already registered') });
    await expect(register('new@example.com', 'secret123')).rejects.toMatchObject({ status: 409 });
  });

  it('does not treat a 401 as an ended session', async () => {
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    mockFetch({ 'POST /api/users/register': () => problem(401, 'Unauthorized') });
    await expect(register('new@example.com', 'secret123')).rejects.toMatchObject({ status: 401 });
    expect(goToLogin).not.toHaveBeenCalled();
  });
});

describe('fetchMe', () => {
  it('asks for the profile with the stored token', async () => {
    signInAs('ADMIN', { token: 'tok-admin' });
    const fetchMock = mockFetch({ 'GET /api/users/me': () => ({ id: 1, email: 'admin@example.com', role: 'ADMIN' }) });

    await expect(fetchMe()).resolves.toEqual({ id: 1, email: 'admin@example.com', role: 'ADMIN' });

    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({ Authorization: 'Bearer tok-admin' });
  });

  it('sends the person to the login page when the token is no longer accepted', async () => {
    signInAs('ADMIN');
    const goToLogin = vi.fn();
    setUnauthorizedHandler(goToLogin);
    mockFetch({ 'GET /api/users/me': () => problem(401, 'Unauthorized') });

    await expect(fetchMe()).rejects.toMatchObject({ status: 401 });

    expect(goToLogin).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });
});
