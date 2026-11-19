import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchUsers } from '../api/users';
import { ErrorMessage } from '../components/ErrorMessage';
import { Icon } from '../components/Icon';
import { Pager } from '../components/Pager';
import { useAsync } from '../hooks/useAsync';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { formatDateTime, formatNumber } from '../lib/format';
import { TableSkeleton } from './TableSkeleton';

const PAGE_SIZE = 20;
const COLUMNS = 4;
/** How long the typing has to pause before the list is asked for. */
export const SEARCH_DELAY_MS = 300;
/** The server refuses a longer search. */
const MAX_SEARCH = 100;

/** Every user, newest first, with a search on the email. The search is sent a moment after the typing stops. */
export function AdminUsers() {
  useDocumentTitle('Users (admin)');
  const [params, setParams] = useSearchParams();
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1);
  const q = (params.get('q') ?? '').trim().slice(0, MAX_SEARCH);

  // The box shows what was typed. The address (and so the list) follows after a pause.
  const [text, setText] = useState(q);
  const settled = useDebouncedValue(text, SEARCH_DELAY_MS);
  // The search the box and the address last agreed on, so that neither one overwrites the other while typing.
  const agreed = useRef(q);

  useEffect(() => {
    const next = settled.trim().slice(0, MAX_SEARCH);
    if (next === agreed.current) return;
    agreed.current = next;
    // A new search starts on the first page, and typing should not fill the Back button.
    setParams(next ? { q: next } : {}, { replace: true });
  }, [settled, setParams]);

  useEffect(() => {
    // The address changed by itself (the Back button): the box follows it.
    if (q !== agreed.current) {
      agreed.current = q;
      setText(q);
    }
  }, [q]);

  const { data, error, loading, reload } = useAsync(
    (signal) => fetchUsers(page - 1, PAGE_SIZE, q || undefined, { signal }),
    [page, q],
  );

  function goTo(nextPage: number) {
    const next = new URLSearchParams();
    if (q) next.set('q', q);
    if (nextPage > 1) next.set('page', String(nextPage));
    setParams(next);
  }

  function clearSearch() {
    agreed.current = '';
    setText('');
    setParams({});
  }

  let summary = '';
  if (data && data.numberOfElements > 0) {
    const from = data.number * data.size + 1;
    const to = data.number * data.size + data.numberOfElements;
    summary = `Showing ${formatNumber(from)} to ${formatNumber(to)} of ${formatNumber(data.totalElements)} users${q ? ` matching “${q}”` : ''}`;
  }

  return (
    <>
      <header className="page-head">
        <h1>Users</h1>
        <p className="muted" aria-live="polite">
          {summary || (loading ? 'Loading users…' : ' ')}
        </p>
      </header>

      <div className="toolbar">
        <form className="admin-search" role="search" onSubmit={(event) => event.preventDefault()}>
          <Icon name="search" size={18} className="admin-search-icon" />
          <input
            className="field admin-search-input"
            type="search"
            value={text}
            maxLength={MAX_SEARCH}
            placeholder="Search by email"
            aria-label="Search users by email"
            autoComplete="off"
            onChange={(event) => setText(event.target.value)}
          />
        </form>
      </div>

      {error && (
        <ErrorMessage
          what="Could not load users"
          error={error}
          hint="Check that the user service is running, then try again."
          onRetry={reload}
        />
      )}

      {!error && loading && !data && <TableSkeleton columns={COLUMNS} label="Loading users…" />}

      {!error && data && data.content.length === 0 && !loading && (
        <div className="empty">
          <p>{q ? `No users match “${q}”.` : page > 1 ? 'No users on this page.' : 'There are no users yet.'}</p>
          {q ? (
            <button type="button" className="btn" onClick={clearSearch}>
              Clear the search
            </button>
          ) : (
            page > 1 && (
              <button type="button" className="btn" onClick={() => goTo(1)}>
                Go to the first page
              </button>
            )
          )}
        </div>
      )}

      {!error && data && data.content.length > 0 && (
        <div className="table-wrap">
          <table className="catalog admin-table users-table" aria-busy={loading} data-loading={loading}>
            <thead>
              <tr>
                <th scope="col" className="num">
                  ID
                </th>
                <th scope="col">Email</th>
                <th scope="col">Role</th>
                <th scope="col">Created</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((user) => (
                <tr key={user.id}>
                  <td className="num mono col-id">{user.id}</td>
                  <td className="cell-wrap col-email">{user.email}</td>
                  <td className="col-role">
                    <span className={`role-badge role-badge--${user.role.toLowerCase()}`}>
                      {user.role === 'ADMIN' ? 'Admin' : 'User'}
                    </span>
                  </td>
                  <td className="col-created">
                    <time dateTime={user.createdAt}>{formatDateTime(user.createdAt)}</time>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalPages > 0 && !error && (
        <Pager
          page={data.number}
          totalPages={data.totalPages}
          first={data.first}
          last={data.last}
          disabled={loading}
          onPrevious={() => goTo(page - 1)}
          onNext={() => goTo(page + 1)}
        />
      )}
    </>
  );
}
