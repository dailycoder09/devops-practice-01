import { getJson, withQuery, type RequestOptions } from './client';
import type { AdminUser, Page } from './types';

// user-service, administrators only. The newest user comes first.

/** `q` is an optional "contains" search on the email. */
export function fetchUsers(page: number, size: number, q?: string, options?: RequestOptions): Promise<Page<AdminUser>> {
  return getJson<Page<AdminUser>>(withQuery('/api/users', { page, size, q }), options);
}

export async function fetchUserCount(options?: RequestOptions): Promise<number> {
  const result = await getJson<Page<AdminUser>>(withQuery('/api/users', { size: 1 }), options);
  return result.totalElements;
}
