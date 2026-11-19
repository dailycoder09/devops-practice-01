// The login kept in the browser, and the one place the rest of the app reads
// it. This file has no React in it, so the fetch wrapper can use it too.
//
// The value lives in localStorage under "shop.auth" as JSON. localStorage can
// be read by any script on the page, so the token must never be logged and the
// app must never load scripts from other sites. The server checks the token on
// every request anyway; what is stored here only decides what the page shows.

import type { Role } from '../api/types';

export const AUTH_STORAGE_KEY = 'shop.auth';

export interface StoredAuth {
  token: string;
  /** ISO-8601 time when the token stops working. */
  expiresAt: string;
  email: string;
  role: Role;
}

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    // Blocked by the browser (for example a strict privacy mode).
    return null;
  }
}

function isStoredAuth(value: unknown): value is StoredAuth {
  if (typeof value !== 'object' || value === null) return false;
  const { token, expiresAt, email, role } = value as Record<string, unknown>;
  return (
    typeof token === 'string' &&
    token !== '' &&
    typeof expiresAt === 'string' &&
    !Number.isNaN(Date.parse(expiresAt)) &&
    typeof email === 'string' &&
    email !== '' &&
    (role === 'USER' || role === 'ADMIN')
  );
}

export function clearSession(): void {
  try {
    storage()?.removeItem(AUTH_STORAGE_KEY);
  } catch {
    // Nothing more can be done if the browser refuses.
  }
}

/**
 * The stored login, or null. A value that cannot be read, has the wrong
 * shape, or has expired is not used, and is removed.
 */
export function readSession(): StoredAuth | null {
  let raw: string | null = null;
  try {
    raw = storage()?.getItem(AUTH_STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
  if (raw === null) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    clearSession();
    return null;
  }
  if (!isStoredAuth(parsed) || Date.parse(parsed.expiresAt) <= Date.now()) {
    clearSession();
    return null;
  }
  return parsed;
}

export function writeSession(auth: StoredAuth): void {
  try {
    storage()?.setItem(AUTH_STORAGE_KEY, JSON.stringify(auth));
  } catch {
    // Without storage the login lasts until the page is reloaded.
  }
}

/** The token to send with a request, or null when nobody is logged in. */
export function getToken(): string | null {
  return readSession()?.token ?? null;
}

// When the server says 401, the fetch wrapper cannot navigate by itself. The
// AuthProvider (which lives inside the router) registers what to do here.
let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

/** The server no longer accepts the token: forget it, then tell the app. */
export function handleUnauthorized(): void {
  clearSession();
  onUnauthorized?.();
}
