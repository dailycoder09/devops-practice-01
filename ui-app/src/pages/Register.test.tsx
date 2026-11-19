import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { Login } from './Login';
import { Register } from './Register';
import { json, mockFetch, problem, renderAt, requestedUrls } from '../test/helpers';

function renderRegister() {
  return renderAt(
    <Routes>
      <Route path="/register" element={<Register />} />
      <Route path="/login" element={<Login />} />
    </Routes>,
    '/register',
  );
}

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  if (email) await user.type(screen.getByLabelText('Email'), email);
  if (password) await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Register' }));
}

describe('Register page', () => {
  it('creates the account, then asks the person to log in', async () => {
    const fetchMock = mockFetch({ 'POST /api/users/register': () => json({ id: 3, email: 'new@example.com', role: 'USER' }, 201) });
    renderRegister();

    await fillAndSubmit('new@example.com', 'secret123');

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your account is ready. Log in to continue.');
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ email: 'new@example.com', password: 'secret123' });
    // Registering does not log in by itself.
    expect(requestedUrls(fetchMock)).toEqual(['/api/users/register']);
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('says when the email is already used', async () => {
    mockFetch({ 'POST /api/users/register': () => problem(409, 'Conflict', 'Email already registered') });
    renderRegister();

    await fillAndSubmit('new@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('That email is already used. Try logging in instead.');
    expect(screen.getByRole('heading', { name: 'Create an account' })).toBeInTheDocument();
  });

  it('ends the message with the reference when the server sent a trace id', async () => {
    mockFetch({
      'POST /api/users/register': () =>
        new Response(JSON.stringify({ title: 'Conflict', status: 409, detail: 'Email already registered' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'trace-reg-1' },
        }),
    });
    renderRegister();

    await fillAndSubmit('new@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('That email is already used. Try logging in instead. Reference: trace-reg-1');
  });

  it('says the password is too short, without sending anything', async () => {
    const fetchMock = mockFetch({});
    renderRegister();

    await fillAndSubmit('new@example.com', 'short');

    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be at least 8 characters.');
    expect(requestedUrls(fetchMock)).toEqual([]);
  });

  it('says the password is too long, without sending anything', async () => {
    const fetchMock = mockFetch({});
    renderRegister();

    await fillAndSubmit('new@example.com', 'x'.repeat(73));

    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be at most 72 characters.');
    expect(requestedUrls(fetchMock)).toEqual([]);
  });

  it.each([
    ['an empty email', ''],
    ['an email with no @', 'not-an-email'],
    ['an email with two @', 'two@@example.com'],
    ['an email over 255 characters', `${'a'.repeat(250)}@example.com`],
  ])('asks for a valid email instead of sending %s', async (_name, email) => {
    const fetchMock = mockFetch({});
    renderRegister();

    await fillAndSubmit(email, 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid email address.');
    expect(requestedUrls(fetchMock)).toEqual([]);
  });

  it('explains a rejection by the server in plain words', async () => {
    mockFetch({ 'POST /api/users/register': () => problem(400, 'Bad Request', 'must be a well-formed email address') });
    renderRegister();

    await fillAndSubmit('new@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The server did not accept those details. Use a valid email and a password of 8 to 72 characters.',
    );
  });

  it('says when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    renderRegister();

    await fillAndSubmit('new@example.com', 'secret123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server.');
  });

  it('tells the browser this is a new password and states the rule up front', () => {
    renderRegister();
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByText('8 to 72 characters.')).toBeInTheDocument();
    // The rule is said once, by the hint tied to the password box, not again under the heading.
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription('8 to 72 characters.');
    expect(screen.getAllByText(/8 to 72/)).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
  });
});
