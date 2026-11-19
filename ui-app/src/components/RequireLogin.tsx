import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** For pages that need a login. A visitor is sent to the login page, and brought back here afterwards. */
export function RequireLogin({ children }: { children: ReactNode }) {
  const { user, sessionEnded } = useAuth();
  const location = useLocation();
  if (!user) {
    // When the server ended the login, the login handler has already gone to the login page with this
    // same reason. Say it again here, or this redirect would replace that page's state and lose the reason.
    const state = { from: `${location.pathname}${location.search}`, ...(sessionEnded ? { expired: true } : {}) };
    return <Navigate to="/login" replace state={state} />;
  }
  return <>{children}</>;
}
