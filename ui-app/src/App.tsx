import { useEffect } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { adminRoutes } from './admin/routes';
import { AuthProvider } from './auth/AuthContext';
import { CartProvider } from './cart/CartContext';
import { CategoryStrip } from './components/CategoryStrip';
import { NotFoundView } from './components/NotFoundView';
import { SiteFooter } from './components/SiteFooter';
import { SiteHeader } from './components/SiteHeader';
import { useDocumentTitle } from './hooks/useDocumentTitle';
import { logger } from './lib/logger';
import { Cart } from './pages/Cart';
import { Home } from './pages/Home';
import { Login } from './pages/Login';
import { Orders } from './pages/Orders';
import { ProductDetail } from './pages/ProductDetail';
import { Products } from './pages/Products';
import { Register } from './pages/Register';

function NotFound() {
  useDocumentTitle('Page not found');
  return (
    <NotFoundView
      title="We can't find that page."
      text="There is nothing at this address. Check it for mistakes, or go back to the shop."
      primary={{ to: '/', label: 'Go to home page' }}
      secondary={{ to: '/products', label: 'Browse products' }}
    />
  );
}

/** Writes one debug line for every page the person goes to. Only the path, never the query. */
function RouteLogger() {
  const { pathname } = useLocation();
  useEffect(() => {
    logger.debug('Route', { path: pathname });
  }, [pathname]);
  return null;
}

/** The category strip belongs to the pages where a person browses. */
function showsCategoryStrip(pathname: string): boolean {
  return pathname === '/' || pathname === '/products' || pathname.startsWith('/products/');
}

/** Everything except the router and the providers, so tests can supply their own. */
export function AppRoutes() {
  const { pathname } = useLocation();
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <RouteLogger />
      <SiteHeader />
      {showsCategoryStrip(pathname) && <CategoryStrip />}
      <main id="main" className="shell">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/products" element={<Products />} />
          <Route path="/products/:id" element={<ProductDetail />} />
          <Route path="/cart" element={<Cart />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          {adminRoutes}
          {/* The inventory page used to be public and lived here. Old bookmarks still work. */}
          <Route path="/inventory" element={<Navigate to="/admin/inventory" replace />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      <SiteFooter />
    </>
  );
}

export default function App() {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <CartProvider>
          <AppRoutes />
        </CartProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
