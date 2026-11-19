import { screen, waitFor, within } from '@testing-library/react';
import { Link } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { AdminUsers } from './Users';
import { deferred, makeUser, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';

const users = [
  makeUser(12, 'newest@example.com'),
  makeUser(2, 'jane@example.com'),
  makeUser(1, 'admin@example.com', 'ADMIN'),
];

const userUrls = (fetchMock: ReturnType<typeof mockFetch>) =>
  requestedUrls(fetchMock).filter((url) => url.startsWith('/api/users'));

const searchBox = () => screen.getByRole('searchbox', { name: 'Search users by email' });

describe('Admin users page', () => {
  it('lists the users with id, email, role and created date', async () => {
    const fetchMock = mockFetch({ 'GET /api/users': () => pageOf(users) });
    renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });

    expect(await screen.findByText('newest@example.com')).toBeInTheDocument();
    expect(userUrls(fetchMock)).toEqual(['/api/users?page=0&size=20']);
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['ID', 'Email', 'Role', 'Created']);

    const first = screen.getByText('newest@example.com').closest('tr')!;
    expect(within(first).getByText('12')).toBeInTheDocument();
    expect(within(first).getByText('User')).toHaveClass('role-badge');
    expect(within(first).getByRole('time')).toHaveAttribute('datetime', '2026-11-19T10:15:00');

    const admin = screen.getByText('admin@example.com').closest('tr')!;
    expect(within(admin).getByText('Admin')).toHaveClass('role-badge--admin');
    expect(screen.getByText('Showing 1 to 3 of 3 users')).toBeInTheDocument();
  });

  it('sends the admin token', async () => {
    const fetchMock = mockFetch({ 'GET /api/users': () => pageOf(users) });
    renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });
    await screen.findByText('newest@example.com');

    const call = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/api/users'));
    expect(call?.[1]?.headers).toMatchObject({ Authorization: 'Bearer admin-token' });
  });

  it('shows a loading line, then the table', async () => {
    const hold = deferred<unknown>();
    mockFetch({ 'GET /api/users': () => hold.promise });
    renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });

    expect(screen.getByText('Loading users…', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading users…' })).toBeInTheDocument();

    hold.resolve(pageOf(users));
    expect(await screen.findByText('newest@example.com')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Loading users…' })).not.toBeInTheDocument();
  });

  describe('search', () => {
    it('asks for the search only after the typing pauses, not for every letter', async () => {
      const fetchMock = mockFetch({
        'GET /api/users': (url) =>
          url.searchParams.get('q') ? pageOf([users[1]], { totalElements: 1 }) : pageOf(users),
      });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');

      await user.type(searchBox(), 'jane');

      await waitFor(() => expect(userUrls(fetchMock)).toContain('/api/users?page=0&size=20&q=jane'));
      expect(await screen.findByText('Showing 1 to 1 of 1 users matching “jane”')).toBeInTheDocument();
      // One request for the first load and one for the finished search. No request for "j", "ja" or "jan".
      expect(userUrls(fetchMock)).toEqual(['/api/users?page=0&size=20', '/api/users?page=0&size=20&q=jane']);
      expect(screen.queryByText('newest@example.com')).not.toBeInTheDocument();
      expect(screen.getByText('jane@example.com')).toBeInTheDocument();
    });

    it('does not send spaces around the text', async () => {
      const fetchMock = mockFetch({ 'GET /api/users': () => pageOf(users) });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');

      await user.type(searchBox(), '  jane  ');

      await waitFor(() => expect(userUrls(fetchMock)).toContain('/api/users?page=0&size=20&q=jane'));
      expect(userUrls(fetchMock).some((url) => url.includes('%20') || url.includes('+'))).toBe(false);
    });

    it('goes back to all users when the box is emptied', async () => {
      const fetchMock = mockFetch({ 'GET /api/users': () => pageOf(users) });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users?q=jane', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');
      expect(searchBox()).toHaveValue('jane');
      expect(userUrls(fetchMock)[0]).toBe('/api/users?page=0&size=20&q=jane');

      await user.clear(searchBox());

      await waitFor(() => expect(userUrls(fetchMock).slice(-1)[0]).toBe('/api/users?page=0&size=20'));
    });

    it('starts again on the first page for a new search', async () => {
      const fetchMock = mockFetch({
        'GET /api/users': (url) => pageOf(users, { number: Number(url.searchParams.get('page')), totalElements: 45 }),
      });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users?page=3', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');
      expect(userUrls(fetchMock)[0]).toBe('/api/users?page=2&size=20');

      await user.type(searchBox(), 'x');

      await waitFor(() => expect(userUrls(fetchMock).slice(-1)[0]).toBe('/api/users?page=0&size=20&q=x'));
    });

    it('keeps the search text when it moves to the next page', async () => {
      const fetchMock = mockFetch({
        'GET /api/users': (url) => pageOf(users, { number: Number(url.searchParams.get('page')), totalElements: 45 }),
      });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users?q=example', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');

      await user.click(screen.getByRole('button', { name: 'Next' }));

      await waitFor(() => expect(userUrls(fetchMock)).toContain('/api/users?page=1&size=20&q=example'));
      expect(searchBox()).toHaveValue('example');
    });

    it('follows the address when it changes by itself (the Back button), without asking twice', async () => {
      const fetchMock = mockFetch({ 'GET /api/users': () => pageOf(users) });
      const user = userEvent.setup();
      renderAt(
        <>
          <Link to="/admin/users?q=bob">Go to bob</Link>
          <AdminUsers />
        </>,
        '/admin/users',
        { auth: 'ADMIN' },
      );
      await screen.findByText('newest@example.com');

      await user.click(screen.getByRole('link', { name: 'Go to bob' }));

      await waitFor(() => expect(searchBox()).toHaveValue('bob'));
      await waitFor(() => expect(userUrls(fetchMock)).toContain('/api/users?page=0&size=20&q=bob'));
      // Let the pause of the box run out: it must not send the same search a second time.
      await new Promise((resolve) => setTimeout(resolve, 450));
      expect(userUrls(fetchMock).filter((url) => url.includes('q=bob'))).toHaveLength(1);
    });

    it('limits the text to what the server accepts', async () => {
      mockFetch({ 'GET /api/users': () => pageOf(users) });
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');

      expect(searchBox()).toHaveAttribute('maxlength', '100');
    });

    it('says when nobody matches, and the Clear button shows everyone again', async () => {
      const fetchMock = mockFetch({
        'GET /api/users': (url) => (url.searchParams.get('q') ? pageOf([]) : pageOf(users)),
      });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users?q=zzz', { auth: 'ADMIN' });

      expect(await screen.findByText('No users match “zzz”.')).toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Clear the search' }));

      expect(await screen.findByText('newest@example.com')).toBeInTheDocument();
      expect(searchBox()).toHaveValue('');
      expect(userUrls(fetchMock).slice(-1)[0]).toBe('/api/users?page=0&size=20');
    });
  });

  describe('pages and states', () => {
    it('asks for the next page and shows the right range', async () => {
      const fetchMock = mockFetch({
        'GET /api/users': (url) =>
          pageOf(users, { number: Number(url.searchParams.get('page')), size: 20, totalElements: 45 }),
      });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });
      await screen.findByText('newest@example.com');
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Next' }));

      expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
      expect(userUrls(fetchMock)).toContain('/api/users?page=1&size=20');
      expect(screen.getByText('Showing 21 to 23 of 45 users')).toBeInTheDocument();
    });

    it('says there are no users yet', async () => {
      mockFetch({ 'GET /api/users': () => pageOf([]) });
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });

      expect(await screen.findByText('There are no users yet.')).toBeInTheDocument();
    });

    it('shows the problem with a Try again button, and the users after the retry works', async () => {
      let calls = 0;
      mockFetch({ 'GET /api/users': () => (++calls === 1 ? problem(503, 'Service Unavailable') : pageOf(users)) });
      const user = userEvent.setup();
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Could not load users.');
      expect(alert).toHaveTextContent('Service Unavailable');
      expect(screen.queryByRole('table')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByText('newest@example.com')).toBeInTheDocument();
    });

    it('shows the server reason when the request is refused', async () => {
      mockFetch({ 'GET /api/users': () => problem(403, 'Forbidden', 'Administrators only') });
      renderAt(<AdminUsers />, '/admin/users', { auth: 'ADMIN' });

      expect(await screen.findByRole('alert')).toHaveTextContent('Administrators only');
    });
  });
});
