import { screen, waitFor, within } from '@testing-library/react';
import { categoryIcon } from './Icon';
import { mockFetch, problem, renderAt } from '../test/helpers';
import { CategoryStrip } from './CategoryStrip';

const categories = ['Electronics', 'Home Appliances', 'Beauty and Personal Care'];

describe('CategoryStrip', () => {
  it('shows one link per category, each to the product list filtered by that category', async () => {
    mockFetch({ 'GET /api/products/categories': () => categories });
    renderAt(<CategoryStrip />, '/');

    const strip = await screen.findByRole('navigation', { name: 'Categories' });
    expect(within(strip).getByRole('link', { name: 'Electronics' })).toHaveAttribute(
      'href',
      '/products?category=Electronics',
    );
    expect(within(strip).getByRole('link', { name: 'Home Appliances' })).toHaveAttribute(
      'href',
      '/products?category=Home%20Appliances',
    );
    expect(within(strip).getByRole('link', { name: 'Beauty and Personal Care' })).toHaveAttribute(
      'href',
      '/products?category=Beauty%20and%20Personal%20Care',
    );
    expect(within(strip).getAllByRole('link')).toHaveLength(categories.length + 1);
  });

  it('starts with a link to all products', async () => {
    mockFetch({ 'GET /api/products/categories': () => categories });
    renderAt(<CategoryStrip />, '/');

    const strip = await screen.findByRole('navigation', { name: 'Categories' });
    expect(within(strip).getAllByRole('link')[0]).toHaveAccessibleName('All products');
    expect(within(strip).getAllByRole('link')[0]).toHaveAttribute('href', '/products');
  });

  it('puts a small picture above each name that no screen reader reads out', async () => {
    mockFetch({ 'GET /api/products/categories': () => categories });
    renderAt(<CategoryStrip />, '/');

    const link = await screen.findByRole('link', { name: 'Electronics' });
    expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('marks the category whose products are shown', async () => {
    mockFetch({ 'GET /api/products/categories': () => categories });
    renderAt(<CategoryStrip />, '/products?category=Home%20Appliances');

    const current = await screen.findByRole('link', { name: 'Home Appliances' });
    expect(current).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Electronics' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: 'All products' })).not.toHaveAttribute('aria-current');
  });

  it('marks "All products" on the unfiltered list, but not on a search', async () => {
    mockFetch({ 'GET /api/products/categories': () => categories });
    const first = renderAt(<CategoryStrip />, '/products');
    expect(await screen.findByRole('link', { name: 'All products' })).toHaveAttribute('aria-current', 'page');
    first.unmount();

    renderAt(<CategoryStrip />, '/products?search=lamp');
    expect(await screen.findByRole('link', { name: 'All products' })).not.toHaveAttribute('aria-current');
  });

  it('shows nothing, and no error, when the categories cannot be loaded', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/categories': () => problem(503, 'Service Unavailable') });
    renderAt(<CategoryStrip />, '/');

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows nothing for an empty list of categories', async () => {
    const fetchMock = mockFetch({ 'GET /api/products/categories': () => [] });
    renderAt(<CategoryStrip />, '/');

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});

describe('categoryIcon', () => {
  it.each([
    ['Electronics', 'phone'],
    ['Clothing', 'shirt'],
    ['Books', 'book'],
    ['Furniture', 'sofa'],
    ['Groceries', 'basket'],
    ['Sports and Outdoors', 'ball'],
    ['Toys and Games', 'cube'],
    ['Automotive', 'car'],
    ['Beauty and Personal Care', 'sparkle'],
    ['Home Appliances', 'fridge'],
    ['Something New', 'tag'],
  ])('picks %s -> %s', (category, icon) => {
    expect(categoryIcon(category)).toBe(icon);
  });
});
