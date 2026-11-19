import { ApiError, errorMessage, getJson, isAbortError } from '../api/client';
import { withPeriod } from '../lib/format';
import { withReference } from '../lib/reference';

export type CheckKey = 'product' | 'inventory' | 'user' | 'cart' | 'order';

export interface ServiceCheck {
  key: CheckKey;
  label: string;
  /** One cheap read that only works when the service and its database are up. */
  path: string;
}

/** The five services, each asked one small question. Every one of these needs the admin login. */
export const SERVICE_CHECKS: readonly ServiceCheck[] = [
  { key: 'product', label: 'Product service', path: '/api/products?size=1' },
  { key: 'inventory', label: 'Inventory service', path: '/api/inventory?size=1' },
  { key: 'user', label: 'User service', path: '/api/users?size=1' },
  { key: 'cart', label: 'Cart service', path: '/api/cart' },
  { key: 'order', label: 'Order service', path: '/api/orders/stats' },
];

export interface CheckResult {
  up: boolean;
  /** How long the answer took, in whole milliseconds. */
  ms: number;
  /** When the answer came (or the failure), as milliseconds since 1970. */
  checkedAt: number;
  /** Why the service counts as down, a full sentence that ends with the "Reference: ..." of the server when it sent one. Missing when it is up. */
  problem?: string;
}

/** Asks the service its question and times it. Never rejects. Answers `null` when the check was cancelled. */
export async function runCheck(check: ServiceCheck, signal: AbortSignal): Promise<CheckResult | null> {
  const started = performance.now();
  try {
    await getJson<unknown>(check.path, { signal });
    return { up: true, ms: Math.round(performance.now() - started), checkedAt: Date.now() };
  } catch (failure) {
    if (isAbortError(failure) || signal.aborted) return null;
    const problem = withReference(
      withPeriod(failure instanceof ApiError || failure instanceof Error ? errorMessage(failure) : 'No answer'),
      failure,
    );
    return { up: false, ms: Math.round(performance.now() - started), checkedAt: Date.now(), problem };
  }
}
