import { useEffect, useId, useRef, useState, type FocusEvent, type KeyboardEvent } from 'react';
import { createSearchParams, useLocation, useNavigate } from 'react-router-dom';
import { fetchProducts } from '../api/products';
import type { Product } from '../api/types';
import { addRecentSearch, clearRecentSearches, cleanSearchText, readRecentSearches } from '../lib/searchHistory';
import { Icon } from './Icon';
import { ProductPicture } from './ProductPicture';

export const SUGGESTION_DELAY_MS = 300;
export const SUGGESTION_MIN_CHARACTERS = 2;
export const SUGGESTION_COUNT = 6;

/** What the box shows for the page we are on: the search text on the results page, nothing anywhere else. */
function searchTextOn(pathname: string, search: string): string {
  if (pathname !== '/products') return '';
  const params = new URLSearchParams(search);
  return params.get('search') ?? params.get('q') ?? '';
}

type Row = { kind: 'product'; product: Product } | { kind: 'recent'; text: string };

/**
 * The search box in the page header. Enter or the button goes to the results page. While typing it
 * suggests up to six products by name, and when it is empty and has focus it offers the last searches.
 *
 * Up and Down move through the list, Enter opens the chosen row, Escape closes it. A suggestion request
 * that is no longer the newest one is ignored, and a request that fails shows nothing.
 */
export function SearchBox() {
  const navigate = useNavigate();
  const location = useLocation();
  const listId = useId();
  const root = useRef<HTMLFormElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const timer = useRef<number | undefined>(undefined);
  const newest = useRef(0);
  const inFlight = useRef<AbortController | null>(null);

  const [text, setText] = useState(() => searchTextOn(location.pathname, location.search));
  const [open, setOpen] = useState(false);
  const [suggestions, setSuggestions] = useState<Product[]>([]);
  const [recent, setRecent] = useState<string[]>([]);
  const [active, setActive] = useState(-1);

  const trimmed = text.trim();
  const mode: 'recent' | 'suggest' | 'none' =
    trimmed === ''
      ? recent.length > 0
        ? 'recent'
        : 'none'
      : trimmed.length >= SUGGESTION_MIN_CHARACTERS && suggestions.length > 0
        ? 'suggest'
        : 'none';
  const visible = open && mode !== 'none';
  const rows: Row[] =
    mode === 'recent'
      ? recent.map((entry) => ({ kind: 'recent', text: entry }))
      : mode === 'suggest'
        ? suggestions.map((product) => ({ kind: 'product', product }))
        : [];

  function stopPending() {
    window.clearTimeout(timer.current);
    inFlight.current?.abort();
    inFlight.current = null;
    // Anything that arrives from a request started before this moment is stale.
    newest.current += 1;
  }

  // The address changed (a search was made, or the person went to another page): show what it says.
  useEffect(() => {
    stopPending();
    setText(searchTextOn(location.pathname, location.search));
    setSuggestions([]);
    setOpen(false);
    setActive(-1);
  }, [location.pathname, location.search]);

  useEffect(() => stopPending, []);

  function onChange(value: string) {
    setText(value);
    setActive(-1);
    stopPending();
    const term = value.trim();
    if (term.length < SUGGESTION_MIN_CHARACTERS) {
      setSuggestions([]);
      setOpen(true);
      return;
    }
    const mine = newest.current;
    timer.current = window.setTimeout(() => {
      const controller = new AbortController();
      inFlight.current = controller;
      fetchProducts({ q: term, page: 0, size: SUGGESTION_COUNT }, { signal: controller.signal }).then(
        (page) => {
          if (mine !== newest.current) return;
          setSuggestions(page.content.slice(0, SUGGESTION_COUNT));
          setOpen(true);
        },
        () => {
          // A failed suggestion request shows nothing. Searching with Enter still works.
          if (mine === newest.current) setSuggestions([]);
        },
      );
    }, SUGGESTION_DELAY_MS);
  }

  function search(value: string) {
    stopPending();
    const term = cleanSearchText(value);
    setOpen(false);
    setActive(-1);
    input.current?.blur();
    if (term === '') {
      navigate('/products');
      return;
    }
    setRecent(addRecentSearch(term));
    navigate({ pathname: '/products', search: createSearchParams({ search: term }).toString() });
  }

  function choose(row: Row) {
    if (row.kind === 'recent') {
      setText(row.text);
      search(row.text);
      return;
    }
    stopPending();
    setOpen(false);
    setActive(-1);
    input.current?.blur();
    navigate(`/products/${row.product.id}`);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      if (open) {
        event.preventDefault();
        setOpen(false);
        setActive(-1);
      }
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (rows.length === 0) return;
      event.preventDefault();
      setOpen(true);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setActive((current) =>
        current === -1 ? (step === 1 ? 0 : rows.length - 1) : (current + step + rows.length) % rows.length,
      );
      return;
    }
    if (event.key === 'Enter' && visible && active >= 0 && rows[active]) {
      event.preventDefault();
      choose(rows[active]);
    }
  }

  function onFocus() {
    setRecent(readRecentSearches());
    setOpen(true);
  }

  // Close when focus leaves the whole box (the list and the Clear link are part of it).
  function onBlur(event: FocusEvent<HTMLFormElement>) {
    if (root.current && event.relatedTarget instanceof Node && root.current.contains(event.relatedTarget)) return;
    setOpen(false);
    setActive(-1);
  }

  const activeId = visible && active >= 0 ? `${listId}-${active}` : undefined;

  return (
    <form
      ref={root}
      className="search"
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        search(text);
      }}
      onBlur={onBlur}
    >
      <div className="search-field">
        <input
          ref={input}
          className="search-input"
          type="search"
          role="combobox"
          aria-label="Search products"
          aria-autocomplete="list"
          aria-expanded={visible}
          aria-controls={listId}
          aria-activedescendant={activeId}
          placeholder="Search for products"
          autoComplete="off"
          enterKeyHint="search"
          value={text}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={onKeyDown}
          onFocus={onFocus}
        />
        <button type="submit" className="search-button" aria-label="Search">
          <Icon name="search" size={20} />
        </button>
      </div>

      {visible && (
        <div className="search-panel">
          {mode === 'recent' && (
            <div className="search-panel-head">
              <span>Recent searches</span>
              <button
                type="button"
                className="link-button"
                onClick={() => {
                  clearRecentSearches();
                  setRecent([]);
                  setActive(-1);
                  input.current?.focus();
                }}
              >
                Clear
              </button>
            </div>
          )}
          <ul
            id={listId}
            role="listbox"
            aria-label={mode === 'recent' ? 'Recent searches' : 'Suggested products'}
            className="search-list"
          >
            {rows.map((row, position) => (
              <li
                key={row.kind === 'product' ? `p-${row.product.id}` : `r-${row.text}`}
                id={`${listId}-${position}`}
                role="option"
                aria-selected={position === active}
                className={position === active ? 'search-row is-active' : 'search-row'}
                // Keep the focus in the box: a click on a row must not close the list before it is handled.
                onMouseDown={(event) => event.preventDefault()}
                onMouseMove={() => setActive(position)}
                onClick={() => choose(row)}
              >
                {row.kind === 'product' ? (
                  <>
                    <ProductPicture
                      url={row.product.imageUrl}
                      alt=""
                      name={row.product.name}
                      className="search-thumb"
                    />
                    <span className="search-name">{row.product.name}</span>
                  </>
                ) : (
                  <>
                    <Icon name="search" size={16} className="search-clock" />
                    <span className="search-name">{row.text}</span>
                  </>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      <span className="sr-only" aria-live="polite">
        {visible && mode === 'suggest' ? `${rows.length} suggestions` : ''}
      </span>
    </form>
  );
}
