import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Brand } from './Brand';

export function SiteFooter() {
  const { user } = useAuth();
  return (
    <footer className="site-footer">
      <div className="shell site-footer-inner">
        <div className="site-footer-about">
          <Brand />
          <p>A demo shop for learning AWS and DevOps.</p>
        </div>
        <nav aria-label="Footer" className="site-footer-links">
          <Link to="/products">Products</Link>
          <Link to="/cart">Cart</Link>
          <Link to="/orders">My orders</Link>
          {!user && <Link to="/login">Log in</Link>}
        </nav>
      </div>
    </footer>
  );
}
