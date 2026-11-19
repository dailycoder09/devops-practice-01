import { NavLink, Outlet } from 'react-router-dom';

const LINKS = [
  { to: '/admin', label: 'Overview', end: true },
  { to: '/admin/inventory', label: 'Inventory', end: false },
  { to: '/admin/orders', label: 'Orders', end: false },
  { to: '/admin/users', label: 'Users', end: false },
] as const;

/** The frame of every admin page: a menu on the left (a row of tabs on a phone) and the page beside it. */
export function AdminLayout() {
  return (
    <div className="admin">
      <nav className="admin-nav" aria-label="Admin">
        <p className="admin-nav-title">Admin area</p>
        <div className="admin-nav-links">
          {LINKS.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end}>
              {link.label}
            </NavLink>
          ))}
        </div>
      </nav>
      <div className="admin-content">
        <Outlet />
      </div>
    </div>
  );
}
