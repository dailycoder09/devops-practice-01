import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { login as apiLogin, register as apiRegister } from '../api/auth';
import type { Role } from '../api/types';
import { logger } from '../lib/logger';
import { clearSession, readSession, setUnauthorizedHandler, writeSession } from './session';

export interface AuthUser {
  email: string;
  role: Role;
}

export interface AuthValue {
  /** Null when nobody is logged in. */
  user: AuthUser | null;
  /** Only for the ADMIN role. The server checks this again on every change. */
  isAdmin: boolean;
  /**
   * True from the moment the server refuses the token (it ran out, or the
   * secret changed) until the next login or logout. A page that sends a
   * logged-out person to the login page uses it to say why.
   */
  sessionEnded: boolean;
  /** Rejects with an ApiError (for example 401 for a wrong password). */
  login: (email: string, password: string) => Promise<void>;
  /** Creates the account. It does not log in. */
  register: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

function userFromStorage(): AuthUser | null {
  const stored = readSession();
  return stored ? { email: stored.email, role: stored.role } : null;
}

/** Must sit inside the router, because a 401 sends the person to /login. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(userFromStorage);
  const [sessionEnded, setSessionEnded] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const whereWeAre = useRef(location);
  whereWeAre.current = location;

  // The server refused our token (it ran out, or the secret changed).
  useEffect(() => {
    setUnauthorizedHandler(() => {
      logger.info('Session ended: the server no longer accepts the login');
      setUser(null);
      setSessionEnded(true);
      const here = whereWeAre.current;
      navigate('/login', {
        replace: true,
        state: { expired: true, from: `${here.pathname}${here.search}` },
      });
    });
    return () => setUnauthorizedHandler(null);
  }, [navigate]);

  const login = useCallback(async (email: string, password: string) => {
    const result = await apiLogin(email, password);
    writeSession({ token: result.token, expiresAt: result.expiresAt, email: result.email, role: result.role });
    setUser({ email: result.email, role: result.role });
    setSessionEnded(false);
    // Never the token or the email: only that it happened and which role it was.
    logger.info('Logged in', { role: result.role });
  }, []);

  const register = useCallback(async (email: string, password: string) => {
    await apiRegister(email, password);
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setUser(null);
    setSessionEnded(false);
    logger.info('Logged out');
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ user, isAdmin: user?.role === 'ADMIN', sessionEnded, login, register, logout }),
    [user, sessionEnded, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside an AuthProvider');
  return value;
}
