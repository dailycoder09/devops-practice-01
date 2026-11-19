import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { MemoryRouterProps } from 'react-router-dom';
import { Route, Routes } from 'react-router-dom';
import { Login } from './Login';
import { mockFetch, problem, renderAt, requestedUrls } from '../test/helpers';

type Entry = NonNullable<MemoryRouterProps['initialEntries']>[number];

const loginAnswer = { token: 'jwt-abc', expiresAt: '2099-01-01T00:00:00Z', email: 'jane@example.com', role: 'USER' };

function renderLogin(entry: Entry = '/login') {
  return renderAt(
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<p>Register page</p>} />
      <Route path="/" element={<p>Home page</p>} />
      <Route path="/inventory" element={<p>Inventory page</p>} />
    </Routes>,
    entry,
  );
}

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  if (email) await user.type(screen.getByLabelText('Email'), email);
  if (password) await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Log in' }));
}

describe('Login page', () => {
  it('logs in, keeps the token and goes to the home page', async () => {
    const fetchMock = mockFetch({ 'POST /api/users/login': () => loginAnswer });
    renderLogin();

    await fillAndSubmit('jane@example.com', 'secret123');

    expect(await screen.findByText('Home page')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('shop.auth')!)).toMatchObject({ token: 'jwt-abc', email: 'jane@example.com', role: 'USER' });
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ email: 'jane@example.com', password: 'secret123' });
  });

  it('goes back to the page the person came from', async () => {
    mockFetch({ 'POST /api/users/login': () => loginAnswer });
    renderLogin({ pathname: '/login', state: { from: '/inventory' } });

    await fillAndSubmit('jane@example.com', 'secret123');

    expect(await screen.findByText('Inventory page')).toBeInTheDocument();
  });

  it('ignores a "came from" address that leads off this site', async () => {
    mockFetch({ 'POST /api/users/login': () => loginAnswer });
    renderLogin({ pathname: '/login', state: { from: '//evil.example.com/steal' } });

    await fillAndSubmit('jane@example.com', 'secret123');

    expect(await screen.findByText('Home page')).toBeInTheDocument();
  });

  it('says the email or password is wrong, and stays on the page', async () => {
    mockFetch({ 'POST /api/users/login': () => problem(401, 'Unauthorized', 'Invalid email or password') });
    renderLogin();

    await fillAndSubmit('jane@example.com', 'wrong-password');

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.');
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(localStorage.getItem('shop.auth')).toBeNull();
    // The person can try again straight away.
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
    expect(screen.getByLabelText('Email')).toHaveValue('jane@example.com');
  });

  it('asks for both fields instead of sending an empty form', async () => {
    const fetchMock = mockFetch({});
    renderLogin();

    await fillAndSubmit('', '');

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter your email and your password.');
    expect(requestedUrls(fetchMock)).toEqual([]);
  });

  it('says when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    renderLogin();

    await fillAndSubmit('jane@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server.');
  });

  it('shows a short message when another problem happens', async () => {
    mockFetch({ 'POST /api/users/login': () => problem(500, 'Internal Server Error', 'Database is down') });
    renderLogin();

    await fillAndSubmit('jane@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Database is down');
  });

  it('ends the message with the reference when the server sent a trace id', async () => {
    mockFetch({
      'POST /api/users/login': () =>
        new Response(JSON.stringify({ title: 'Internal Server Error', status: 500, detail: 'Database is down' }), {
          status: 500,
          headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'trace-login-1' },
        }),
    });
    renderLogin();

    await fillAndSubmit('jane@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Database is down. Reference: trace-login-1');
  });

  it('welcomes a person who has just registered', () => {
    renderLogin({ pathname: '/login', state: { registered: true } });
    expect(screen.getByRole('status')).toHaveTextContent('Your account is ready. Log in to continue.');
  });

  it('explains why a person was sent here after the session ended', () => {
    renderLogin({ pathname: '/login', state: { expired: true, from: '/inventory' } });
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Please log in again.');
  });

  it('shows no notice on a plain visit and links to the Register page', () => {
    renderLogin();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register');
  });

  it('keeps the password hidden and tells the browser what the fields are', () => {
    renderLogin();
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'username');
  });
});
