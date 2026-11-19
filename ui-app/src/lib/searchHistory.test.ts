import {
  addRecentSearch,
  cleanSearchText,
  clearRecentSearches,
  MAX_RECENT_SEARCHES,
  readRecentSearches,
  RECENT_SEARCHES_KEY,
} from './searchHistory';

describe('cleanSearchText', () => {
  it('keeps one line of plain text', () => {
    expect(cleanSearchText('  red   shoes ')).toBe('red shoes');
    expect(cleanSearchText('line one\nline two\t!')).toBe('line one line two !');
    expect(cleanSearchText('a\u0000b')).toBe('a b');
  });

  it('shortens a very long text and ignores anything that is not text', () => {
    expect(cleanSearchText('x'.repeat(500)).length).toBeLessThanOrEqual(80);
    expect(cleanSearchText(42)).toBe('');
    expect(cleanSearchText(null)).toBe('');
    expect(cleanSearchText({})).toBe('');
  });

  it('does not change markup characters: they are shown as text, never parsed', () => {
    expect(cleanSearchText('<b>bold</b>')).toBe('<b>bold</b>');
  });
});

describe('recent searches', () => {
  it('starts empty', () => {
    expect(readRecentSearches()).toEqual([]);
  });

  it('keeps the newest first and moves a repeated search to the top', () => {
    addRecentSearch('phone');
    addRecentSearch('lamp');
    addRecentSearch('Phone');

    expect(readRecentSearches()).toEqual(['Phone', 'lamp']);
  });

  it(`keeps only the last ${MAX_RECENT_SEARCHES}`, () => {
    for (const word of ['a1', 'b2', 'c3', 'd4', 'e5', 'f6', 'g7']) addRecentSearch(word);

    expect(readRecentSearches()).toEqual(['g7', 'f6', 'e5', 'd4', 'c3']);
  });

  it('ignores a blank search', () => {
    addRecentSearch('   ');
    expect(readRecentSearches()).toEqual([]);
  });

  it('can be cleared', () => {
    addRecentSearch('phone');
    clearRecentSearches();
    expect(readRecentSearches()).toEqual([]);
    expect(localStorage.getItem(RECENT_SEARCHES_KEY)).toBeNull();
  });

  it.each(['not json', '{"a":1}', '"text"', '42', 'null'])('ignores stored junk %j', (junk) => {
    localStorage.setItem(RECENT_SEARCHES_KEY, junk);
    expect(readRecentSearches()).toEqual([]);
  });

  it('keeps only the texts from a mixed list, cleaned and without repeats', () => {
    localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(['phone', 7, null, '  lamp  ', 'PHONE', '', { x: 1 }]));
    expect(readRecentSearches()).toEqual(['phone', 'lamp']);
  });

  it('works when the browser blocks storage', () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readRecentSearches()).toEqual([]);
    expect(addRecentSearch('phone')).toEqual(['phone']);
    get.mockRestore();
    set.mockRestore();
  });
});
