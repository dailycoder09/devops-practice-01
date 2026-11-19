import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, type MemoryRouterProps } from 'react-router-dom';
import type {
  AdminUser,
  Cart,
  CartLine,
  InventoryItem,
  Order,
  OrderStats,
  Page,
  Product,
  ProductDetail,
  Role,
  ServerInfo,
} from '../api/types';
import { AuthProvider } from '../auth/AuthContext';
import type { StoredAuth } from '../auth/session';
import { CartProvider } from '../cart/CartContext';

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

export function problem(status: number, title: string, detail?: string): Response {
  return new Response(JSON.stringify({ title, status, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

type Handler = (url: URL, init?: RequestInit) => unknown;

/**
 * Replaces fetch with a router. Keys look like "GET /api/products". A handler
 * may return a Response, or any value to be sent as 200 JSON. Unmatched
 * requests answer 404 so a forgotten route shows up as an error, not a hang.
 */
export function mockFetch(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw, 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    const handler = routes[`${method} ${url.pathname}`];
    if (!handler) return problem(404, 'Not Found', `No mock for ${method} ${url.pathname}`);
    const result = await handler(url, init);
    return result instanceof Response ? result : json(result);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/**
 * For a test that is about something else but renders a logged-in person: the
 * cart is asked for, and the answer never comes. Nothing is updated later, so
 * the test ends cleanly.
 */
export function neverAnswers() {
  const fetchMock = vi.fn(() => new Promise<Response>(() => {}));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

export function requestedUrls(fetchMock: ReturnType<typeof mockFetch>): string[] {
  return fetchMock.mock.calls.map(([input]) => String(input));
}

/** Writes a valid login (what the app keeps in localStorage) so the next render starts logged in. */
export function signInAs(role: Role, overrides: Partial<StoredAuth> = {}): StoredAuth {
  const stored: StoredAuth = {
    token: `${role.toLowerCase()}-token`,
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    email: role === 'ADMIN' ? 'admin@example.com' : 'jane@example.com',
    role,
    ...overrides,
  };
  localStorage.setItem('shop.auth', JSON.stringify(stored));
  return stored;
}

type Entry = NonNullable<MemoryRouterProps['initialEntries']>[number];

/**
 * Renders inside a router, the login provider and the cart provider. Nobody is
 * logged in unless the test passes `auth`, which logs in as a user or an admin
 * first. A logged-in person makes the cart provider ask for `GET /api/cart`.
 */
export function renderAt(ui: ReactElement, path: Entry = '/', options: { auth?: Role } = {}) {
  if (options.auth) signInAs(options.auth);
  return render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <CartProvider>{ui}</CartProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}

export function pageOf<T>(
  content: T[],
  { number = 0, size = 20, totalElements = content.length }: { number?: number; size?: number; totalElements?: number } = {},
): Page<T> {
  const totalPages = Math.ceil(totalElements / size);
  return {
    content,
    totalElements,
    totalPages,
    number,
    size,
    first: number === 0,
    last: number >= totalPages - 1,
    numberOfElements: content.length,
  };
}

export function makeProduct(id: number, name: string, skuCode: string, overrides: Partial<Product> = {}): Product {
  return {
    id,
    name,
    category: 'Electronics',
    price: 19.99,
    skuCode,
    createdAt: '2026-10-01T09:00:00',
    updatedAt: '2026-10-01T09:00:00',
    ...overrides,
  };
}

/** A bare product detail: no images, highlights or specifications until a test adds them. */
export function makeDetail(overrides: Partial<ProductDetail> = {}): ProductDetail {
  return {
    id: 1,
    name: 'Alpha Phone',
    category: 'Electronics',
    price: 799.99,
    skuCode: 'ALPHA-1',
    stockQuantity: 42,
    images: [],
    highlights: [],
    specifications: [],
    createdAt: '2026-10-01T09:00:00',
    updatedAt: '2026-10-01T09:00:00',
    ...overrides,
  };
}

export function makeItem(
  id: number,
  skuCode: string,
  quantityOnHand: number,
  reorderThreshold: number,
): InventoryItem {
  return {
    id,
    skuCode,
    quantityOnHand,
    reorderThreshold,
    createdAt: '2026-10-01T09:00:00',
    updatedAt: '2026-10-01T09:00:00',
  };
}

export function makeServerInfo(
  hostname: string,
  { instanceId, zone, forwardedFor }: { instanceId?: string; zone?: string; forwardedFor?: string } = {},
): ServerInfo {
  return {
    application: { name: 'product-service', version: '1.2.3', profiles: ['dev'], port: 8081 },
    host: {
      hostname,
      addresses: [
        { interfaceName: 'eth0', address: '10.0.1.23' },
        { interfaceName: 'lo', address: '127.0.0.1' },
        { interfaceName: 'eth0', address: 'fe80::1' },
      ],
      container: false,
    },
    request: {
      hostHeader: 'shop.example.com',
      serverAddress: '10.0.1.23',
      serverPort: 8081,
      clientAddress: '10.0.0.5',
      forwardedFor,
    },
    runtime: {
      javaVersion: '17.0.14',
      javaVendor: 'OpenLogic',
      osName: 'Linux',
      osVersion: '6.1',
      osArch: 'amd64',
      cpus: 2,
      heapUsedMb: 210,
      heapMaxMb: 512,
      heapUsedPercent: 41,
      pid: 4121,
      startedAt: new Date(Date.now() - 12_000).toISOString(),
      startedAtEpochMs: Date.now() - 12_000,
      uptime: '00:00:12',
    },
    ...(instanceId || zone ? { cloud: { instanceId, availabilityZone: zone } } : {}),
  };
}

/** A promise a test settles by hand, to hold a request open while it checks the screen. */
export function deferred<T = void>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** 204 No Content, which is what the cart answers to a delete. */
export function noContent(): Response {
  return new Response(null, { status: 204 });
}

const cents = (value: number) => Math.round(value * 100) / 100;

export function makeCartLine(sku: string, quantity: number, unitPrice: number, overrides: Partial<CartLine> = {}): CartLine {
  return {
    sku,
    quantity,
    available: true,
    name: `Product ${sku}`,
    unitPrice,
    lineTotal: cents(quantity * unitPrice),
    ...overrides,
  };
}

/** A line for a product that no longer exists: no name and no price. */
export function makeGoneLine(sku: string, quantity: number): CartLine {
  return { sku, quantity, available: false, name: null, unitPrice: null, lineTotal: null };
}

/** The cart JSON, with the count and the total worked out the way the server does. */
export function makeCart(items: CartLine[]): Cart {
  return {
    items,
    itemCount: items.reduce((sum, line) => sum + line.quantity, 0),
    total: cents(items.filter((line) => line.available).reduce((sum, line) => sum + (line.lineTotal ?? 0), 0)),
  };
}

export function makeOrder(id: number, overrides: Partial<Order> = {}): Order {
  return {
    id,
    status: 'CONFIRMED',
    total: 1599.98,
    createdAt: '2026-11-19T10:15:00Z',
    items: [{ sku: 'ALPHA-1', name: 'Alpha Phone', unitPrice: 799.99, quantity: 2, lineTotal: 1599.98 }],
    ...overrides,
  };
}

/** An order as the admin list returns it: with the customer on it. */
export function makeAdminOrder(id: number, userEmail: string | null, overrides: Partial<Order> = {}): Order {
  return makeOrder(id, { userId: 7, userEmail, ...overrides });
}

export function makeUser(id: number, email: string, role: Role = 'USER'): AdminUser {
  return { id, email, role, createdAt: '2026-11-19T10:15:00' };
}

export function makeStats(overrides: Partial<OrderStats> = {}): OrderStats {
  return { totalOrders: 11, pending: 0, confirmed: 7, rejected: 4, revenue: 11195.66, ...overrides };
}

/**
 * A small pretend cart-service for tests. `catalog` is what product-service
 * knows (name and price by SKU); `initial` is what is already in the cart
 * (SKU to quantity). A SKU in the cart but not in the catalog comes back as
 * "not available". `quantities` is live, so a test can look at what the
 * "server" holds after a click.
 */
export function fakeCart(catalog: Record<string, { name: string; price: number }>, initial: Record<string, number> = {}) {
  const quantities = new Map(Object.entries(initial));

  function body(): Cart {
    return makeCart(
      [...quantities].map(([sku, quantity]) => {
        const product = catalog[sku];
        return product ? makeCartLine(sku, quantity, product.price, { name: product.name }) : makeGoneLine(sku, quantity);
      }),
    );
  }

  const routes: Record<string, Handler> = {
    'GET /api/cart': () => body(),
    'DELETE /api/cart': () => {
      quantities.clear();
      return noContent();
    },
  };
  for (const sku of Object.keys(catalog)) {
    routes[`PUT /api/cart/items/${sku}`] = (_url, init) => {
      const { quantity } = JSON.parse(String(init?.body)) as { quantity: unknown };
      if (typeof quantity !== 'number' || !Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
        return problem(400, 'Bad Request', 'Quantity must be from 1 to 99');
      }
      quantities.set(sku, quantity);
      return body();
    };
  }
  for (const sku of [...Object.keys(catalog), ...Object.keys(initial)]) {
    routes[`DELETE /api/cart/items/${sku}`] = () => {
      quantities.delete(sku);
      return noContent();
    };
  }
  return { routes, quantities };
}
