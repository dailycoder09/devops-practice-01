import { Link } from 'react-router-dom';

interface Action {
  to: string;
  label: string;
}

interface Props {
  /** The heading of the page. */
  title: string;
  /** One plain sentence under it. */
  text: string;
  /** The button to press first. */
  primary: Action;
  /** The quieter second button. */
  secondary: Action;
}

/**
 * The page for an address or a product that is not there: a big quiet "404", what happened, and two ways on.
 * The number is decoration (the heading says it), so screen readers skip it.
 */
export function NotFoundView({ title, text, primary, secondary }: Props) {
  return (
    <section className="not-found">
      <p className="not-found-code" aria-hidden="true">
        404
      </p>
      <h1>{title}</h1>
      <p className="muted">{text}</p>
      <div className="not-found-actions">
        <Link className="btn btn--buy" to={primary.to}>
          {primary.label}
        </Link>
        <Link className="btn" to={secondary.to}>
          {secondary.label}
        </Link>
      </div>
    </section>
  );
}
