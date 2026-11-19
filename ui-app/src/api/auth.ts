import { getJson, postJson } from './client';
import type { CurrentUser, LoginResult, RegisteredUser } from './types';

// user-service. Login and register are anonymous: they send no token, and a
// 401 from them means "wrong email or password", not "your login ended".

export function login(email: string, password: string): Promise<LoginResult> {
  return postJson<LoginResult>('/api/users/login', { email, password }, { anonymous: true });
}

export function register(email: string, password: string): Promise<RegisteredUser> {
  return postJson<RegisteredUser>('/api/users/register', { email, password }, { anonymous: true });
}

/** The profile for the stored token. A 401 sends the person to the login page. */
export function fetchMe(): Promise<CurrentUser> {
  return getJson<CurrentUser>('/api/users/me');
}
