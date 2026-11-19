import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/** What a logged-in person without the ADMIN role sees at an /admin address. */
function NotForYou() {
  useDocumentTitle('Administrators only');
  return (
    <div className="admin-denied">
      <h1>Administrators only</h1>
      <p>This area is for administrators.</p>
      <Link className="btn btn--buy" to="/">
        Go to the home page
      </Link>
    </div>
  );
}

/**
 * Guards the admin area. A visitor is sent to the login page and brought back afterwards. A logged-in
 * person who is not an administrator is told so. The server checks the role again on every change; this
 * only decides what the page shows.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user, isAdmin, sessionEnded } = useAuth();
  const location = useLocation();
  if (!user) {
    const state = { from: `${location.pathname}${location.search}`, ...(sessionEnded ? { expired: true } : {}) };
    return <Navigate to="/login" replace state={state} />;
  }
  if (!isAdmin) return <NotForYou />;
  return <>{children}</>;
}
