import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { fakeCart, makeCart, makeCartLine, mockFetch, neverAnswers, renderAt } from '../test/helpers';
import { SiteHeader } from './SiteHeader';

const catalog = { 'ALPHA-1': { name: 'Alpha Phone', price: 799.99 } };

/** Shows where the router is, so a test can see that a click or a search went somewhere. */
function Where() {
  const location = useLocation();
  return <p data-testid="where">{`${location.pathname}${location.search}`}</p>;
}

function renderHeader(auth?: 'USER' | 'ADMIN', path = '/') {
  return renderAt(
    <>
      <SiteHeader />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </>,
    path,
    { auth },
  );
}

const account = () => screen.getByRole('navigation', { name: 'Account' });

describe('SiteHeader for a visitor', () => {
  it('shows the shop name, a search box, Log in and Register', () => {
    renderHeader();

    expect(screen.getByRole('link', { name: 'MicroShop' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('search')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Search products' })).toBeInTheDocument();
    expect(within(account()).getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
    expect(within(account()).getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register');
  });

  it('has no account menu, no Admin link and no cart count', () => {
    renderHeader();

    expect(within(account()).queryByRole('button')).not.toBeInTheDocument();
    expect(within(account()).queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(within(account()).queryByRole('link', { name: 'My orders' })).not.toBeInTheDocument();
    expect(within(account()).queryByRole('link', { name: /^Cart \(/ })).not.toBeInTheDocument();
  });

  it('still has a cart link, which says that a login comes first', () => {
    renderHeader();
    expect(within(account()).getByRole('link', { name: 'Log in to see your cart' })).toHaveAttribute('href', '/cart');
  });
});

describe('SiteHeader for a logged-in person', () => {
  it('shows the email on an account menu button, and the cart link', async () => {
    mockFetch(fakeCart(catalog, { 'ALPHA-1': 2 }).routes);
    renderHeader('USER');

    const menu = within(account()).getByRole('button', { name: /jane@example\.com/ });
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    expect(await within(account()).findByRole('link', { name: 'Cart (2)' })).toHaveAttribute('href', '/cart');
    expect(within(account()).queryByRole('link', { name: 'Log in' })).not.toBeInTheDocument();
    expect(within(account()).queryByRole('link', { name: 'Register' })).not.toBeInTheDocument();
  });

  it('opens the menu with My orders and Log out, and Escape closes it again and gives the focus back', async () => {
    neverAnswers();
    const user = userEvent.setup();
    renderHeader('USER');
    const button = within(account()).getByRole('button', { name: /jane@example\.com/ });

    await user.click(button);

    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(within(account()).getByRole('link', { name: 'My orders' })).toHaveAttribute('href', '/orders');
    expect(within(account()).getByRole('button', { name: 'Log out' })).toBeInTheDocument();

    await user.keyboard('{Escape}');

    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
  });

  it('closes the menu when something else on the page is clicked', async () => {
    neverAnswers();
    const user = userEvent.setup();
    renderHeader('USER');
    const button = within(account()).getByRole('button', { name: /jane@example\.com/ });
    await user.click(button);

    await user.click(screen.getByTestId('where'));

    expect(button).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens My orders from the menu and closes the menu', async () => {
    neverAnswers();
    const user = userEvent.setup();
    renderHeader('USER');
    const button = within(account()).getByRole('button', { name: /jane@example\.com/ });
    await user.click(button);

    await user.click(within(account()).getByRole('link', { name: 'My orders' }));

    expect(screen.getByTestId('where')).toHaveTextContent('/orders');
    expect(button).toHaveAttribute('aria-expanded', 'false');
  });

  it('Log out from the menu forgets the login and shows the login links again', async () => {
    neverAnswers();
    const user = userEvent.setup();
    renderHeader('USER');
    await user.click(within(account()).getByRole('button', { name: /jane@example\.com/ }));

    await user.click(within(account()).getByRole('button', { name: 'Log out' }));

    expect(within(account()).getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });
});

describe('the Admin link', () => {
  it('is shown to an administrator and goes to /admin', () => {
    neverAnswers();
    renderHeader('ADMIN');
    expect(within(account()).getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  it('is not shown to a user without the admin role', () => {
    neverAnswers();
    renderHeader('USER');
    expect(within(account()).queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });
});

describe('the cart count', () => {
  async function renderWithCount(count: number) {
    mockFetch({ 'GET /api/cart': () => makeCart([makeCartLine('ALPHA-1', count, 10)]) });
    renderHeader('USER');
    return within(account()).findByRole('link', { name: `Cart (${count})` });
  }

  it('is a badge on the cart link', async () => {
    const link = await renderWithCount(3);
    expect(link.querySelector('.cart-badge')).toHaveTextContent('3');
  });

  it('shows 99+ for a very full cart, and the exact number to a screen reader', async () => {
    const link = await renderWithCount(120);
    expect(link.querySelector('.cart-badge')).toHaveTextContent('99+');
    expect(link).toHaveAccessibleName('Cart (120)');
  });

  it('has no badge for an empty cart', async () => {
    mockFetch({ 'GET /api/cart': () => makeCart([]) });
    renderHeader('USER');

    const link = await within(account()).findByRole('link', { name: 'Cart (0)' });
    expect(link.querySelector('.cart-badge')).toBeNull();
  });
});

describe('the search box in the header', () => {
  it('goes to the results page with the search text when Enter is pressed', async () => {
    mockFetch({});
    const user = userEvent.setup();
    renderHeader();

    await user.type(screen.getByRole('combobox', { name: 'Search products' }), 'red shoes{Enter}');

    expect(screen.getByTestId('where')).toHaveTextContent('/products?search=red+shoes');
  });

  it('does the same from the Search button', async () => {
    mockFetch({});
    const user = userEvent.setup();
    renderHeader();

    await user.type(screen.getByRole('combobox', { name: 'Search products' }), 'lamp');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(screen.getByTestId('where')).toHaveTextContent('/products?search=lamp');
  });

  it('shows the search text of the results page that is open', async () => {
    mockFetch({});
    renderHeader(undefined, '/products?search=desk+lamp');

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Search products' })).toHaveValue('desk lamp'));
  });
});
