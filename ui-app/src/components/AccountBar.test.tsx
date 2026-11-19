import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeCart, mockFetch, neverAnswers, renderAt, requestedUrls } from '../test/helpers';
import { AccountBar } from './AccountBar';

describe('AccountBar', () => {
  it('offers Log in and Register links when nobody is logged in', () => {
    renderAt(<AccountBar />);
    const account = screen.getByRole('navigation', { name: 'Account' });
    expect(within(account).getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
    expect(within(account).getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register');
    expect(screen.queryByRole('button', { name: 'Log out' })).not.toBeInTheDocument();
  });

  it('shows the email and a Log out button when logged in', () => {
    neverAnswers();
    renderAt(<AccountBar />, '/', { auth: 'USER' });
    const account = screen.getByRole('navigation', { name: 'Account' });
    expect(within(account).getByText('jane@example.com')).toBeInTheDocument();
    expect(within(account).getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Log in' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Register' })).not.toBeInTheDocument();
  });

  it('Log out forgets the login and goes back to the links', async () => {
    const user = userEvent.setup();
    renderAt(<AccountBar />, '/', { auth: 'ADMIN' });

    await user.click(screen.getByRole('button', { name: 'Log out' }));

    expect(screen.queryByText('admin@example.com')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(localStorage.getItem('shop.auth')).toBeNull();
  });

  describe('cart and orders', () => {
    const catalog = { 'ALPHA-1': { name: 'Alpha Phone', price: 799.99 } };

    it('shows a Cart link with the number of items, and a link to the orders, when logged in', async () => {
      mockFetch(fakeCart(catalog, { 'ALPHA-1': 3 }).routes);
      renderAt(<AccountBar />, '/', { auth: 'USER' });

      const account = screen.getByRole('navigation', { name: 'Account' });
      const cart = await within(account).findByRole('link', { name: 'Cart (3)' });
      expect(cart).toHaveAttribute('href', '/cart');
      expect(within(account).getByRole('link', { name: 'My orders' })).toHaveAttribute('href', '/orders');
    });

    it('shows Cart (0) for an empty cart', async () => {
      mockFetch(fakeCart(catalog).routes);
      renderAt(<AccountBar />, '/', { auth: 'ADMIN' });
      expect(await screen.findByRole('link', { name: 'Cart (0)' })).toBeInTheDocument();
    });

    it('shows no Cart or orders link to a visitor, and asks for no cart', () => {
      const fetchMock = mockFetch({});
      renderAt(<AccountBar />);
      expect(screen.queryByRole('link', { name: /^Cart/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'My orders' })).not.toBeInTheDocument();
      expect(requestedUrls(fetchMock)).toEqual([]);
    });

    it('Log out takes the Cart and orders links away', async () => {
      const user = userEvent.setup();
      mockFetch(fakeCart(catalog, { 'ALPHA-1': 1 }).routes);
      renderAt(<AccountBar />, '/', { auth: 'USER' });
      await screen.findByRole('link', { name: 'Cart (1)' });

      await user.click(screen.getByRole('button', { name: 'Log out' }));

      expect(screen.queryByRole('link', { name: /^Cart/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'My orders' })).not.toBeInTheDocument();
    });
  });
});
