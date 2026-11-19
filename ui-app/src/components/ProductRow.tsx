import { useId, useRef, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from './Icon';

interface Props {
  title: string;
  /** The "View all" link goes here. */
  to: string;
  /** "deals" gives the row a soft blue wash at the top. */
  variant?: 'deals';
  children: ReactNode;
}

/** A heading with a "View all" link and one row of cards that scrolls sideways (with arrows on a desktop). */
export function ProductRow({ title, to, variant, children }: Props) {
  const titleId = useId();
  const track = useRef<HTMLUListElement>(null);

  function scroll(direction: 1 | -1) {
    const element = track.current;
    if (!element || typeof element.scrollBy !== 'function') return;
    element.scrollBy({ left: direction * element.clientWidth * 0.8, behavior: 'smooth' });
  }

  return (
    <section className={variant === 'deals' ? 'row-section row-section--deals' : 'row-section'} aria-labelledby={titleId}>
      <header className="row-head">
        <h2 id={titleId}>{title}</h2>
        <Link className="row-all" to={to}>
          View all
          <span className="sr-only"> {title}</span>
        </Link>
      </header>
      <div className="row-frame">
        <button
          type="button"
          className="row-arrow row-arrow--prev"
          aria-label={`Scroll ${title} to the left`}
          onClick={() => scroll(-1)}
        >
          <Icon name="chevron-left" size={22} />
        </button>
        <ul className="row-track" ref={track}>
          {children}
        </ul>
        <button
          type="button"
          className="row-arrow row-arrow--next"
          aria-label={`Scroll ${title} to the right`}
          onClick={() => scroll(1)}
        >
          <Icon name="chevron-right" size={22} />
        </button>
      </div>
    </section>
  );
}

/** Grey boxes in the place of a row of cards, while the products load. */
export function ProductRowSkeleton({ title, variant }: { title: string; variant?: 'deals' }) {
  return (
    <section
      className={variant === 'deals' ? 'row-section row-section--deals' : 'row-section'}
      aria-busy="true"
      aria-label={`${title}, loading`}
    >
      <header className="row-head">
        <h2>{title}</h2>
      </header>
      <div className="row-frame">
        <ul className="row-track" aria-hidden="true">
          {Array.from({ length: 6 }, (_, position) => (
            <li key={position} className="product-card product-card--skeleton">
              <div className="skeleton skeleton--picture" />
              <div className="product-card-body">
                <div className="skeleton skeleton--line skeleton--short" />
                <div className="skeleton skeleton--line" />
                <div className="skeleton skeleton--line skeleton--short" />
              </div>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
