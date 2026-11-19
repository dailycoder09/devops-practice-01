import { makeUser, mockFetch, pageOf, problem, signInAs } from '../test/helpers';
import { fetchUserCount, fetchUsers } from './users';

describe('fetchUsers (administrators)', () => {
  it('asks for one page with the admin token and returns what the server sent', async () => {
    signInAs('ADMIN', { token: 'tok-admin' });
    const page = pageOf([makeUser(2, 'jane@example.com'), makeUser(1, 'admin@example.com', 'ADMIN')]);
    const fetchMock = mockFetch({ 'GET /api/users': () => page });

    await expect(fetchUsers(0, 20)).resolves.toEqual(page);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/users?page=0&size=20');
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer tok-admin' });
  });

  it('adds the search only when there is one, and encodes it', async () => {
    signInAs('ADMIN');
    const fetchMock = mockFetch({ 'GET /api/users': () => pageOf([]) });

    await fetchUsers(1, 20, 'a+b@x.com');
    await fetchUsers(0, 20, '');
    await fetchUsers(0, 20, undefined);

    expect(fetchMock.mock.calls[0][0]).toBe('/api/users?page=1&size=20&q=a%2Bb%40x.com');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/users?page=0&size=20');
    expect(fetchMock.mock.calls[2][0]).toBe('/api/users?page=0&size=20');
  });

  it('reports a refusal for a user who is not an administrator', async () => {
    signInAs('USER');
    mockFetch({ 'GET /api/users': () => problem(403, 'Forbidden') });
    await expect(fetchUsers(0, 20)).rejects.toMatchObject({ status: 403 });
  });
});

describe('fetchUserCount', () => {
  it('reads the total from a page of one', async () => {
    signInAs('ADMIN');
    const fetchMock = mockFetch({ 'GET /api/users': () => pageOf([makeUser(1, 'a@b.c')], { size: 1, totalElements: 12 }) });

    await expect(fetchUserCount()).resolves.toBe(12);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/users?size=1');
  });
});
