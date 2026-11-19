// Shapes of the JSON returned by product-service and inventory-service.

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
}

export interface Product {
  id: number;
  name: string;
  description?: string;
  price: number;
  category?: string;
  skuCode: string;
  createdAt: string;
  updatedAt: string;
  /** Only meaningful on single-item reads. Do not rely on it in lists. */
  stockQuantity?: number;
  // The four fields below are omitted by the server when null.
  brand?: string;
  /** List price before any discount. */
  mrp?: number;
  /** Whole percent, derived by the server. Absent when there is no discount. */
  discountPercent?: number;
  /** The first image of the product. */
  imageUrl?: string;
}

export interface ProductImage {
  url: string;
  alt?: string;
}

export interface SpecItem {
  key: string;
  value: string;
}

/** Already grouped and ordered by the server. */
export interface SpecGroup {
  group: string;
  items: SpecItem[];
}

/** `GET /api/products/{id}/details`: the product with everything the detail page needs. */
export interface ProductDetail {
  id: number;
  name: string;
  description?: string;
  price: number;
  mrp?: number;
  discountPercent?: number;
  category?: string;
  brand?: string;
  skuCode: string;
  warranty?: string;
  seller?: string;
  /** Absent when the inventory service is unreachable or has no record. */
  stockQuantity?: number | null;
  images: ProductImage[];
  highlights: string[];
  specifications: SpecGroup[];
  createdAt: string;
  updatedAt: string;
}

export interface InventoryItem {
  id: number;
  skuCode: string;
  quantityOnHand: number;
  reorderThreshold: number;
  createdAt: string;
  updatedAt: string;
}

export type ChangeType = 'CREATED' | 'ADJUSTED' | 'UPDATED' | 'DELETED';

export interface AuditEntry {
  id: number;
  skuCode: string;
  changeType: ChangeType;
  previousQuantity?: number;
  newQuantity?: number;
  changedAt: string;
}

/** skuCode -> quantity. Unknown SKUs are absent. */
export type StockMap = Record<string, number>;

export interface ServerInfo {
  application: {
    name: string;
    version: string;
    profiles: string[];
    port: number;
  };
  host: {
    hostname: string;
    addresses: { interfaceName: string; address: string }[];
    container: boolean;
  };
  request: {
    hostHeader?: string;
    serverAddress: string;
    serverPort: number;
    clientAddress: string;
    forwardedFor?: string;
  };
  runtime: {
    javaVersion: string;
    javaVendor: string;
    osName: string;
    osVersion: string;
    osArch: string;
    cpus: number;
    heapUsedMb: number;
    heapMaxMb: number;
    heapUsedPercent: number;
    pid: number;
    startedAt: string;
    startedAtEpochMs: number;
    uptime: string;
  };
  cloud?: {
    instanceId?: string;
    instanceType?: string;
    availabilityZone?: string;
    region?: string;
    privateIp?: string;
    publicIp?: string;
    amiId?: string;
  };
}

// Shapes of the JSON returned by user-service.

export type Role = 'USER' | 'ADMIN';

/** `POST /api/users/login` */
export interface LoginResult {
  token: string;
  /** ISO-8601 UTC time when the token stops working. */
  expiresAt: string;
  email: string;
  role: Role;
}

/** `POST /api/users/register` */
export interface RegisteredUser {
  id: number;
  email: string;
  role: 'USER';
}

/** `GET /api/users/me` */
export interface CurrentUser {
  id: number;
  email: string;
  role: string;
}

// Shapes of the JSON returned by cart-service.

/** One line of the cart. The name and prices are null when the product no longer exists. */
export interface CartLine {
  sku: string;
  quantity: number;
  available: boolean;
  name: string | null;
  unitPrice: number | null;
  lineTotal: number | null;
}

/** `GET /api/cart`, and the answer to `PUT /api/cart/items/{sku}` */
export interface Cart {
  /** In the order they were added. */
  items: CartLine[];
  /** The sum of the quantities. */
  itemCount: number;
  /** The sum of the line totals of the available items. */
  total: number;
}

// Shapes of the JSON returned by order-service.

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED';

export interface OrderLine {
  sku: string;
  name: string;
  /** The price at the moment of the order. */
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface Order {
  id: number;
  /** Who placed the order. Set by the server on every order, but only the admin pages show it. */
  userId?: number;
  userEmail?: string | null;
  status: OrderStatus;
  total: number;
  /** Left out by the server unless the order was rejected. */
  rejectReason?: string | null;
  createdAt: string;
  items: OrderLine[];
  /** Only in the answer to `POST /api/orders`: false when the cart could not be emptied afterwards. */
  cartCleared?: boolean | null;
}

/** `GET /api/orders/stats` (administrators only): the numbers for the admin overview. */
export interface OrderStats {
  totalOrders: number;
  pending: number;
  confirmed: number;
  rejected: number;
  /** The sum of the totals of the confirmed orders. */
  revenue: number;
}

/** One row of `GET /api/users` (administrators only). The server never sends a password. */
export interface AdminUser {
  id: number;
  email: string;
  role: Role;
  createdAt: string;
}
