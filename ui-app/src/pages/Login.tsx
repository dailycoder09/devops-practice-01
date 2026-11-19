import { useId, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { AuthCard } from '../components/AuthCard';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { withPeriod } from '../lib/format';
import { withReference } from '../lib/reference';

interface LoginState {
  /** The page the person was on before being sent here. */
  from?: unknown;
  /** Set when the server ended the session. */
  expired?: boolean;
  /** Set right after a successful registration. */
  registered?: boolean;
}

/** Only addresses inside this site, and never the login pages themselves. */
function returnPath(from: unknown): string {
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//') || from.startsWith('/\\')) return '/';
  if (/^\/(login|register)([/?#]|$)/.test(from)) return '/';
  return from;
}

function problemText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Wrong email or password.';
    if (error.status === 400) return 'Check your email and password, then try again.';
    if (error.status === 0) return 'Could not reach the server. Please try again.';
  }
  return withPeriod(errorMessage(error));
}

export function Login() {
  useDocumentTitle('Log in');
  const { login } = useAuth();
  const navigate = useNavigate();
  const state = (useLocation().state ?? {}) as LoginState;
  const emailId = useId();
  const passwordId = useId();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const address = email.trim();
    if (!address || !password) {
      setProblem('Enter your email and your password.');
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await login(address, password);
      navigate(returnPath(state.from), { replace: true });
    } catch (failure) {
      setProblem(withReference(problemText(failure), failure));
      setBusy(false);
    }
  }

  return (
    <AuthCard
      title="Welcome back"
      text="Log in to see your cart and your orders. You can look at products without an account."
    >
      <header className="auth-head">
        <h1>Log in</h1>
        <p className="muted">Use the email and password of your account.</p>
      </header>

      {state.registered && (
        <div className="notice" role="status">
          Your account is ready. Log in to continue.
        </div>
      )}
      {state.expired && (
        <div className="notice" role="status">
          Your session has ended. Please log in again.
        </div>
      )}
      {problem && (
        <div className="problem auth-message" role="alert">
          <p>{problem}</p>
        </div>
      )}

      <form className="auth-form" onSubmit={submit} noValidate>
        <div className="form-row">
          <label htmlFor={emailId}>Email</label>
          <input
            id={emailId}
            className="field"
            type="email"
            autoComplete="username"
            value={email}
            disabled={busy}
            onChange={(event) => setEmail(event.target.value)}
          />
        </div>
        <div className="form-row">
          <label htmlFor={passwordId}>Password</label>
          <input
            id={passwordId}
            className="field"
            type="password"
            autoComplete="current-password"
            value={password}
            disabled={busy}
            onChange={(event) => setPassword(event.target.value)}
          />
        </div>
        <div>
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Logging in…' : 'Log in'}
          </button>
        </div>
      </form>

      <p className="auth-alt muted">
        New here? <Link to="/register">Register</Link>
      </p>
    </AuthCard>
  );
}
