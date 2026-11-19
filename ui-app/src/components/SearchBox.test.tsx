import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import type { Product } from '../api/types';
import { addRecentSearch, readRecentSearches } from '../lib/searchHistory';
import { deferred, makeProduct, mockFetch, pageOf, problem, renderAt, requestedUrls } from '../test/helpers';
import { SearchBox } from './SearchBox';

function Where() {
  const location = useLocation();
  return <p data-testid="where">{`${location.pathname}${location.search}`}</p>;
}

function renderBox(path = '/') {
  return renderAt(
    <>
      <SearchBox />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </>,
    path,
  );
}

const box = () => screen.getByRole('combobox', { name: 'Search products' });
const searchCalls = (fetchMock: ReturnType<typeof mockFetch>) =>
  requestedUrls(fetchMock).filter((url) => url.startsWith('/api/products/search'));
const slow = { timeout: 3000 };

const phones: Product[] = Array.from({ length: 8 }, (_, index) =>
  makeProduct(index + 1, `Phone model ${index + 1}`, `PH-${index + 1}`, {
    imageUrl: `https://placehold.co/800x800/png?text=P${index + 1}`,
  }),
);

describe('SearchBox suggestions', () => {
  it('asks for nothing until two characters are typed, and then only after a pause', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/search': () => pageOf(phones.slice(0, 3)) });
    const user = userEvent.setup();
    renderBox();

    await user.type(box(), 'p');
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(searchCalls(fetchMock)).toHaveLength(0);

    await user.type(box(), 'h');
    // The pause is 300 ms: right after the last key nothing has been sent yet.
    expect(searchCalls(fetchMock)).toHaveLength(0);
    await screen.findByRole('listbox', { name: 'Suggested products' }, slow);
    expect(searchCalls(fetchMock)).toEqual(['/api/products/search?searchTerm=ph&page=0&size=6']);
  });

  it('sends one request for a word typed without pauses', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/search': () => pageOf(phones.slice(0, 2)) });
    const user = userEvent.setup({ delay: null });
    renderBox();

    await user.type(box(), 'phone');
    await screen.findByRole('listbox', { name: 'Suggested products' }, slow);

    expect(searchCalls(fetchMock)).toEqual(['/api/products/search?searchTerm=phone&page=0&size=6']);
  });

  it('lists at most six product names, each with a picture, and goes to the product page on a click', async () => {
    mockFetch({ 'GET /api/products/search': () => pageOf(phones) });
    const user = userEvent.setup();
    renderBox();
    await user.type(box(), 'phone');

    const list = await screen.findByRole('listbox', { name: 'Suggested products' }, slow);
    const options = within(list).getAllByRole('option');
    expect(options).toHaveLength(6);
    expect(options[0]).toHaveTextContent('Phone model 1');
    expect(options[0].querySelector('img')).toHaveAttribute('src', 'https://placehold.co/800x800/png?text=P1');

    await user.click(options[2]);

    expect(screen.getByTestId('where')).toHaveTextContent('/products/3');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('shows initials instead of a picture for a product without an image', async () => {
    mockFetch({ 'GET /api/products/search': () => pageOf([makeProduct(9, 'Plain Lamp', 'PL-9')]) });
    const user = userEvent.setup();
    renderBox();
    await user.type(box(), 'lamp');

    const option = await screen.findByRole('option', { name: /Plain Lamp/ }, slow);
    expect(option.querySelector('img')).toBeNull();
    expect(option).toHaveTextContent('PL');
  });

  it('moves with Up and Down, wraps round, and opens the chosen product with Enter', async () => {
    mockFetch({ 'GET /api/products/search': () => pageOf(phones.slice(0, 3)) });
    const user = userEvent.setup();
    renderBox();
    await user.type(box(), 'phone');
    await screen.findByRole('listbox', {}, slow);

    await user.keyboard('{ArrowDown}');
    expect(box()).toHaveAttribute('aria-activedescendant', screen.getAllByRole('option')[0].id);
    expect(screen.getAllByRole('option')[0]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(screen.getAllByRole('option')[2]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowDown}');
    expect(screen.getAllByRole('option')[0]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowUp}');
    expect(screen.getAllByRole('option')[2]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent('/products/3');
  });

  it('searches for the typed text when Enter is pressed and no row is chosen', async () => {
    mockFetch({ 'GET /api/products/search': () => pageOf(phones.slice(0, 3)) });
    const user = userEvent.setup();
    renderBox();
    await user.type(box(), 'phone');
    await screen.findByRole('listbox', {}, slow);

    await user.keyboard('{Enter}');

    expect(screen.getByTestId('where')).toHaveTextContent('/products?search=phone');
  });

  it('closes the list with Escape', async () => {
    mockFetch({ 'GET /api/products/search': () => pageOf(phones.slice(0, 3)) });
    const user = userEvent.setup();
    renderBox();
    await user.type(box(), 'phone');
    await screen.findByRole('listbox', {}, slow);
    expect(box()).toHaveAttribute('aria-expanded', 'true');

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(box()).toHaveAttribute('aria-expanded', 'false');
  });

  it('closes the list when focus leaves the box', async () => {
    mockFetch({ 'GET /api/products/search': () => pageOf(phones.slice(0, 3)) });
    const user = userEvent.setup();
    renderBox();
    await user.type(box(), 'phone');
    await screen.findByRole('listbox', {}, slow);

    await user.tab();
    await user.tab();

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('ignores an answer to a request that is no longer the newest one', async () => {
    const first = deferred<ReturnType<typeof pageOf<Product>>>();
    const second = deferred<ReturnType<typeof pageOf<Product>>>();
    const fetchMock = mockFetch({
      'GET /api/products/search': (url) =>
        url.searchParams.get('searchTerm') === 'ph' ? first.promise : second.promise,
    });
    const user = userEvent.setup();
    renderBox();

    await user.type(box(), 'ph');
    await waitFor(() => expect(searchCalls(fetchMock)).toHaveLength(1), slow);
    await user.type(box(), 'o');
    await waitFor(() => expect(searchCalls(fetchMock)).toHaveLength(2), slow);

    second.resolve(pageOf([makeProduct(2, 'Newer answer', 'N-2')]));
    expect(await screen.findByRole('option', { name: /Newer answer/ })).toBeInTheDocument();

    first.resolve(pageOf([makeProduct(1, 'Older answer', 'O-1')]));
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(screen.queryByRole('option', { name: /Older answer/ })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: /Newer answer/ })).toBeInTheDocument();
  });

  it('shows nothing when the suggestion request fails, and Enter still searches', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/search': () => problem(503, 'Service Unavailable', 'down') });
    const user = userEvent.setup();
    renderBox();

    await user.type(box(), 'phone');
    await waitFor(() => expect(searchCalls(fetchMock)).toHaveLength(1), slow);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await user.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent('/products?search=phone');
  });

  it('shows nothing when the search finds no product', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/search': () => pageOf([]) });
    const user = userEvent.setup();
    renderBox();

    await user.type(box(), 'zzzz');
    await waitFor(() => expect(searchCalls(fetchMock)).toHaveLength(1), slow);

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});

describe('SearchBox recent searches', () => {
  it('remembers searches, newest first, and offers them when the box is empty and has focus', async () => {
    mockFetch({});
    const user = userEvent.setup();
    renderBox();

    await user.type(box(), 'lamp{Enter}');
    await user.click(box());
    await user.clear(box());
    await user.type(box(), 'desk{Enter}');
    await user.click(box());
    await user.clear(box());

    const list = await screen.findByRole('listbox', { name: 'Recent searches' });
    expect(
      within(list)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['desk', 'lamp']);
    expect(readRecentSearches()).toEqual(['desk', 'lamp']);
  });

  it('shows at most the last five', async () => {
    for (const word of ['a1', 'b2', 'c3', 'd4', 'e5', 'f6']) addRecentSearch(word);
    mockFetch({});
    const user = userEvent.setup();
    renderBox();

    await user.click(box());

    const options = within(await screen.findByRole('listbox', { name: 'Recent searches' })).getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual(['f6', 'e5', 'd4', 'c3', 'b2']);
  });

  it('searches again when a recent search is clicked', async () => {
    addRecentSearch('lamp');
    mockFetch({});
    const user = userEvent.setup();
    renderBox();
    await user.click(box());

    await user.click(await screen.findByRole('option', { name: 'lamp' }));

    expect(screen.getByTestId('where')).toHaveTextContent('/products?search=lamp');
  });

  it('forgets them all with Clear', async () => {
    addRecentSearch('lamp');
    addRecentSearch('desk');
    mockFetch({});
    const user = userEvent.setup();
    renderBox();
    await user.click(box());

    await user.click(await screen.findByRole('button', { name: 'Clear' }));

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(readRecentSearches()).toEqual([]);
    expect(localStorage.getItem('shop.recentSearches')).toBeNull();
  });

  it('offers nothing when there are no recent searches', async () => {
    mockFetch({});
    const user = userEvent.setup();
    renderBox();

    await user.click(box());

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('keeps the text plain: markup in a stored search is shown as text', async () => {
    localStorage.setItem('shop.recentSearches', JSON.stringify(['<img src=x onerror=alert(1)>']));
    mockFetch({});
    const user = userEvent.setup();
    renderBox();

    await user.click(box());

    expect(await screen.findByRole('option', { name: '<img src=x onerror=alert(1)>' })).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });
});

describe('SearchBox and the address', () => {
  it('goes to the product list when Enter is pressed on an empty box', async () => {
    mockFetch({});
    const user = userEvent.setup();
    renderBox('/cart');

    await user.click(box());
    await user.keyboard('{Enter}');

    expect(screen.getByTestId('where')).toHaveTextContent('/products');
  });

  it('shows the search text of the results page, accepts the older "q" name, and is empty elsewhere', async () => {
    mockFetch({});
    const first = renderBox('/products?q=old+name');
    await waitFor(() => expect(box()).toHaveValue('old name'));
    first.unmount();

    renderBox('/cart?search=ignored');
    expect(box()).toHaveValue('');
  });
});
