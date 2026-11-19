import { screen, within } from '@testing-library/react';
import { neverAnswers, renderAt } from '../test/helpers';
import { SiteFooter } from './SiteFooter';

describe('SiteFooter', () => {
  it('has the shop name, a plain line about the shop, and links', () => {
    renderAt(<SiteFooter />);

    const footer = screen.getByRole('contentinfo');
    expect(within(footer).getByRole('link', { name: 'MicroShop' })).toHaveAttribute('href', '/');
    expect(within(footer).getByText('A demo shop for learning AWS and DevOps.')).toBeInTheDocument();
    const links = within(screen.getByRole('navigation', { name: 'Footer' }));
    expect(links.getByRole('link', { name: 'Products' })).toHaveAttribute('href', '/products');
    expect(links.getByRole('link', { name: 'Cart' })).toHaveAttribute('href', '/cart');
    expect(links.getByRole('link', { name: 'My orders' })).toHaveAttribute('href', '/orders');
  });

  it('offers Log in to a visitor', () => {
    renderAt(<SiteFooter />);
    expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
  });

  it('does not offer Log in to someone who is logged in', () => {
    neverAnswers();
    renderAt(<SiteFooter />, '/', { auth: 'USER' });
    expect(screen.queryByRole('link', { name: 'Log in' })).not.toBeInTheDocument();
  });
});
