import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { fetchMe } from '../api/auth';
import { json, mockFetch, neverAnswers, problem, renderAt, signInAs } from '../test/helpers';
import { useAuth } from './AuthContext';

const loginAnswer = { token: 'jwt-abc', expiresAt: '2099-01-01T00:00:00Z', email: 'admin@example.com', role: 'ADMIN' };

/** Shows what the context says and gives a button for each action. */
function Probe() {
  const { user, isAdmin, login, register, logout } = useAuth();
  const [failure, setFailure] = useState('');
  const [registered, setRegistered] = useState('');

  async function attempt(action: () => Promise<unknown>) {
    try {
      await action();
    } catch (error) {
      setFailure(errorMessage(error));
    }
  }

  return (
    <>
      <p data-testid="who">{user ? `${user.email} (${user.role})` : 'nobody'}</p>
      <p data-testid="admin">{String(isAdmin)}</p>
      <p data-testid="failure">{failure}</p>
      <p data-testid="registered">{registered}</p>
      <button type="button" onClick={() => attempt(() => login('admin@example.com', 'admin12345'))}>
        do login
      </button>
      <button type="button" onClick={() => attempt(async () => { await register('new@example.com', 'secret123'); setRegistered('done'); })}>
        do register
      </button>
      <button type="button" onClick={logout}>
        do logout
      </button>
    </>
  );
}

const who = () => screen.getByTestId('who');

describe('AuthProvider', () => {
  it('starts logged out when nothing is stored', () => {
    renderAt(<Probe />);
    expect(who()).toHaveTextContent('nobody');
    expect(screen.getByTestId('admin')).toHaveTextContent('false');
  });

  it('login keeps the token and expiry in localStorage and sets the user', async () => {
    const fetchMock = mockFetch({ 'POST /api/users/login': () => loginAnswer });
    const user = userEvent.setup();
    renderAt(<Probe />);

    await user.click(screen.getByRole('button', { name: 'do login' }));

    expect(await screen.findByText('admin@example.com (ADMIN)')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('shop.auth')!)).toMatchObject({ token: 'jwt-abc', expiresAt: '2099-01-01T00:00:00Z' });
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ email: 'admin@example.com', password: 'admin12345' });
  });

  it('login with a wrong password rejects, stores nothing and stays logged out', async () => {
    mockFetch({ 'POST /api/users/login': () => problem(401, 'Unauthorized', 'Invalid email or password') });
    const user = userEvent.setup();
    renderAt(<Probe />);

    await user.click(screen.getByRole('button', { name: 'do login' }));

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
    expect(who()).toHaveTextContent('nobody');
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('logout clears the stored login and the user', async () => {
    signInAs('ADMIN');
    const user = userEvent.setup();
    renderAt(<Probe />);
    expect(who()).toHaveTextContent('admin@example.com (ADMIN)');

    await user.click(screen.getByRole('button', { name: 'do logout' }));

    expect(who()).toHaveTextContent('nobody');
    expect(screen.getByTestId('admin')).toHaveTextContent('false');
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('picks up a login stored earlier, so a page reload keeps you logged in', () => {
    neverAnswers();
    signInAs('USER');
    renderAt(<Probe />);
    expect(who()).toHaveTextContent('jane@example.com (USER)');
  });

  it('ignores and removes a login that has expired', () => {
    signInAs('ADMIN', { expiresAt: new Date(Date.now() - 1000).toISOString() });
    renderAt(<Probe />);
    expect(who()).toHaveTextContent('nobody');
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it.each([
    ['text that is not JSON', '{oops'],
    ['JSON that is not an object', 'null'],
    ['a token that is not text', JSON.stringify({ token: 123, expiresAt: '2099-01-01T00:00:00Z', email: 'a@b.co', role: 'USER' })],
    ['an empty token', JSON.stringify({ token: '', expiresAt: '2099-01-01T00:00:00Z', email: 'a@b.co', role: 'USER' })],
    ['an expiry that is not a date', JSON.stringify({ token: 't', expiresAt: 'soon', email: 'a@b.co', role: 'USER' })],
    ['a missing expiry', JSON.stringify({ token: 't', email: 'a@b.co', role: 'USER' })],
    ['a role nobody knows', JSON.stringify({ token: 't', expiresAt: '2099-01-01T00:00:00Z', email: 'a@b.co', role: 'ROOT' })],
    ['a missing email', JSON.stringify({ token: 't', expiresAt: '2099-01-01T00:00:00Z', role: 'USER' })],
  ])('ignores and removes a stored value with %s', (_name, stored) => {
    localStorage.setItem('shop.auth', stored);
    renderAt(<Probe />);
    expect(who()).toHaveTextContent('nobody');
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  it('isAdmin is true only for the ADMIN role', async () => {
    neverAnswers();
    signInAs('USER');
    const { unmount } = renderAt(<Probe />);
    expect(screen.getByTestId('admin')).toHaveTextContent('false');
    unmount();

    localStorage.clear();
    signInAs('ADMIN');
    renderAt(<Probe />);
    expect(screen.getByTestId('admin')).toHaveTextContent('true');
  });

  it('register creates the account but does not log in', async () => {
    const fetchMock = mockFetch({ 'POST /api/users/register': () => json({ id: 9, email: 'new@example.com', role: 'USER' }, 201) });
    const user = userEvent.setup();
    renderAt(<Probe />);

    await user.click(screen.getByRole('button', { name: 'do register' }));

    expect(await screen.findByText('done')).toBeInTheDocument();
    expect(who()).toHaveTextContent('nobody');
    expect(localStorage.getItem('shop.auth')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('register passes a failure on, for example an email that is already used', async () => {
    mockFetch({ 'POST /api/users/register': () => problem(409, 'Conflict', 'Email already registered') });
    const user = userEvent.setup();
    renderAt(<Probe />);

    await user.click(screen.getByRole('button', { name: 'do register' }));

    expect(await screen.findByText('Email already registered')).toBeInTheDocument();
  });

  it('sessionEnded turns true when the server refuses the token, and false again at the next login or logout', async () => {
    function Ended() {
      const { sessionEnded, login, logout } = useAuth();
      return (
        <>
          <p data-testid="ended">{String(sessionEnded)}</p>
          <button type="button" onClick={() => fetchMe().catch(() => undefined)}>
            ask
          </button>
          <button type="button" onClick={() => login('admin@example.com', 'admin12345')}>
            log in again
          </button>
          <button type="button" onClick={logout}>
            leave
          </button>
        </>
      );
    }
    signInAs('USER');
    mockFetch({
      'GET /api/users/me': () => problem(401, 'Unauthorized', 'The token has expired'),
      'POST /api/users/login': () => loginAnswer,
    });
    const user = userEvent.setup();
    renderAt(<Ended />);
    expect(screen.getByTestId('ended')).toHaveTextContent('false');

    await user.click(screen.getByRole('button', { name: 'ask' }));
    await waitFor(() => expect(screen.getByTestId('ended')).toHaveTextContent('true'));

    await user.click(screen.getByRole('button', { name: 'log in again' }));
    await waitFor(() => expect(screen.getByTestId('ended')).toHaveTextContent('false'));

    signInAs('USER');
    await user.click(screen.getByRole('button', { name: 'ask' }));
    await waitFor(() => expect(screen.getByTestId('ended')).toHaveTextContent('true'));
    await user.click(screen.getByRole('button', { name: 'leave' }));
    expect(screen.getByTestId('ended')).toHaveTextContent('false');
  });

  it('useAuth outside the provider says what is wrong', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() =>
      render(
        <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <Probe />
        </MemoryRouter>,
      ),
    ).toThrow(/AuthProvider/);
    quiet.mockRestore();
  });
});
