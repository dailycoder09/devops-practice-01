// The last few searches, kept in this browser only (localStorage), so the search box can offer them again
// when it is empty. Plain text only: it is cleaned when it is saved and again when it is read, and it is
// only ever shown as text, never as markup.

export const RECENT_SEARCHES_KEY = 'shop.recentSearches';
export const MAX_RECENT_SEARCHES = 5;
const MAX_LENGTH = 80;

/** One line of plain text: no control characters, one space between words, a sensible length. */
export function cleanSearchText(value: unknown): string {
  if (typeof value !== 'string') return '';
  return value
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_LENGTH)
    .trim();
}

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** Newest first. Anything stored that is not a list of texts is ignored. */
export function readRecentSearches(): string[] {
  let raw: string | null = null;
  try {
    raw = storage()?.getItem(RECENT_SEARCHES_KEY) ?? null;
  } catch {
    return [];
  }
  if (raw === null) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const seen = new Set<string>();
    const result: string[] = [];
    for (const item of parsed) {
      const text = cleanSearchText(item);
      const key = text.toLowerCase();
      if (text === '' || seen.has(key)) continue;
      seen.add(key);
      result.push(text);
      if (result.length === MAX_RECENT_SEARCHES) break;
    }
    return result;
  } catch {
    return [];
  }
}

/** Puts a search first (moving it up when it was already there) and keeps the newest five. Returns the new list. */
export function addRecentSearch(value: string): string[] {
  const text = cleanSearchText(value);
  if (text === '') return readRecentSearches();
  const rest = readRecentSearches().filter((item) => item.toLowerCase() !== text.toLowerCase());
  const next = [text, ...rest].slice(0, MAX_RECENT_SEARCHES);
  try {
    storage()?.setItem(RECENT_SEARCHES_KEY, JSON.stringify(next));
  } catch {
    // Without storage the list only lasts until the page is reloaded.
  }
  return next;
}

export function clearRecentSearches(): void {
  try {
    storage()?.removeItem(RECENT_SEARCHES_KEY);
  } catch {
    // Nothing more can be done if the browser refuses.
  }
}
