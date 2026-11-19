import { useId, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { AuthCard } from '../components/AuthCard';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { withPeriod } from '../lib/format';
import { withReference } from '../lib/reference';

const MAX_EMAIL = 255;
const MIN_PASSWORD = 8;
// BCrypt only looks at the first 72 bytes, so the server rejects more.
const MAX_PASSWORD = 72;

/** The same rules the server applies, so most mistakes are caught before sending. */
function checkDetails(email: string, password: string): string | null {
  if (email.length > MAX_EMAIL || !/^[^\s@]+@[^\s@]+$/.test(email)) return 'Enter a valid email address.';
  if (password.length < MIN_PASSWORD) return `Password must be at least ${MIN_PASSWORD} characters.`;
  if (password.length > MAX_PASSWORD) return `Password must be at most ${MAX_PASSWORD} characters.`;
  return null;
}

function problemText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return 'That email is already used. Try logging in instead.';
    if (error.status === 400) {
      return `The server did not accept those details. Use a valid email and a password of ${MIN_PASSWORD} to ${MAX_PASSWORD} characters.`;
    }
    if (error.status === 0) return 'Could not reach the server. Please try again.';
  }
  return withPeriod(errorMessage(error));
}

export function Register() {
  useDocumentTitle('Create an account');
  const { register } = useAuth();
  const navigate = useNavigate();
  const emailId = useId();
  const passwordId = useId();
  const hintId = useId();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const address = email.trim();
    const invalid = checkDetails(address, password);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await register(address, password);
      navigate('/login', { replace: true, state: { registered: true } });
    } catch (failure) {
      setProblem(withReference(problemText(failure), failure));
      setBusy(false);
    }
  }

  return (
    <AuthCard
      title="Join MicroShop"
      text="An account lets you keep a cart and place orders. It only needs an email and a password."
    >
      <header className="auth-head">
        <h1>Create an account</h1>
        <p className="muted">Choose an email and a password.</p>
      </header>

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
            autoComplete="new-password"
            aria-describedby={hintId}
            value={password}
            disabled={busy}
            onChange={(event) => setPassword(event.target.value)}
          />
          <p id={hintId} className="form-hint">
            {MIN_PASSWORD} to {MAX_PASSWORD} characters.
          </p>
        </div>
        <div>
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Registering…' : 'Register'}
          </button>
        </div>
      </form>

      <p className="auth-alt muted">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </AuthCard>
  );
}
