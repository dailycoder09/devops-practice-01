import type { ReactNode } from 'react';
import { Icon } from './Icon';

interface Props {
  /** Short welcome line in the blue panel. */
  title: string;
  /** One or two plain sentences under it. */
  text: string;
  children: ReactNode;
}

/** The card around the login and register forms: a blue welcome panel on the left, the form on the right. */
export function AuthCard({ title, text, children }: Props) {
  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-aside">
          {/* Not a heading: the page's own heading (Log in, Create an account) is the one in the form. */}
          <p className="auth-aside-title">{title}</p>
          <p className="auth-aside-text">{text}</p>
          <Icon name="bag" size={140} className="auth-aside-art" />
        </div>
        <div className="auth-main">{children}</div>
      </div>
    </div>
  );
}
