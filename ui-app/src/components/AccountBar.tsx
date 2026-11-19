import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { Icon } from './Icon';

/**
 * The right-hand side of the page header. A visitor sees Log in and Register. A logged-in person sees an
 * account menu (their email, My orders, Log out) and the Cart link with the number of items. Only an
 * administrator also sees an Admin link.
 *
 * The menu is a plain "show or hide" button, so its links and its Log out button are ordinary links and
 * buttons for the keyboard and for screen readers. Escape closes it, and so does a click anywhere else.
 */
export function AccountBar() {
  const { user, isAdmin, logout } = useAuth();
  const { itemCount } = useCart();
  const location = useLocation();
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);

  // Going to another page closes the menu.
  useEffect(() => setOpen(false), [location.pathname]);

  // A click or a tap outside the menu closes it.
  useEffect(() => {
    if (!open) return;
    function outside(event: MouseEvent | TouchEvent) {
      if (root.current && event.target instanceof Node && !root.current.contains(event.target)) setOpen(false);
    }
    document.addEventListener('mousedown', outside);
    document.addEventListener('touchstart', outside);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('touchstart', outside);
    };
  }, [open]);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape' && open) {
      event.stopPropagation();
      setOpen(false);
      button.current?.focus();
    }
  }

  return (
    <nav aria-label="Account" className="account">
      {user ? (
        <>
          {isAdmin && (
            <NavLink to="/admin" className="account-link">
              Admin
            </NavLink>
          )}
          <div className="account-menu" ref={root} onKeyDown={onKeyDown}>
            <button
              ref={button}
              type="button"
              className="account-button"
              aria-expanded={open}
              aria-controls={menuId}
              onClick={() => setOpen((value) => !value)}
            >
              <Icon name="user" size={22} />
              <span className="account-email">{user.email}</span>
              <Icon name="chevron-down" size={16} className="account-caret" />
            </button>
            <div id={menuId} className={open ? 'account-panel is-open' : 'account-panel'}>
              <NavLink to="/orders" onClick={() => setOpen(false)}>
                My orders
              </NavLink>
              <button type="button" onClick={logout}>
                Log out
              </button>
            </div>
          </div>
          <NavLink to="/cart" className="cart-link" aria-label={`Cart (${itemCount})`}>
            <span className="cart-icon">
              <Icon name="cart" size={24} />
              {itemCount > 0 && <span className="cart-badge">{itemCount > 99 ? '99+' : itemCount}</span>}
            </span>
            <span className="cart-label">Cart</span>
          </NavLink>
        </>
      ) : (
        <>
          <NavLink to="/login" className="login-button">
            Log in
          </NavLink>
          <NavLink to="/register" className="account-link account-link--register">
            Register
          </NavLink>
          {/* A visitor can open the cart too: it asks them to log in and then brings them back to it. */}
          <NavLink to="/cart" className="cart-link" aria-label="Log in to see your cart">
            <span className="cart-icon">
              <Icon name="cart" size={24} />
            </span>
            <span className="cart-label">Cart</span>
          </NavLink>
        </>
      )}
    </nav>
  );
}
