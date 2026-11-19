// A small fetch wrapper. Every request bypasses the HTTP cache so the load
// balancer really picks a server each time. Failures become ApiError.
// When someone is logged in, every request carries their token. If the server
// answers 401, the login is forgotten and the app goes to the login page.

import { getToken, handleUnauthorized } from '../auth/session';
import { logger } from '../lib/logger';

/** The response header in which the services send the id that ties one request to their own logs. */
export const TRACE_ID_HEADER = 'X-Trace-Id';

export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail?: string;
  /** From the X-Trace-Id header of the answer, when the server sent one. Shown to people as "Reference: ...". */
  readonly traceId?: string;

  constructor(status: number, title: string, detail?: string, traceId?: string) {
    super(detail ?? title);
    this.name = 'ApiError';
    this.status = status;
    this.title = title;
    this.detail = detail;
    this.traceId = traceId;
  }
}

function traceIdOf(response: Response): string | undefined {
  const value = response.headers.get(TRACE_ID_HEADER)?.trim();
  return value ? value : undefined;
}

/** Only the path, never the query string: a search text or an id in the query is nobody's business in a log. */
function pathOnly(path: string): string {
  const cut = path.search(/[?#]/);
  return cut === -1 ? path : path.slice(0, cut);
}

export interface RequestOptions {
  signal?: AbortSignal;
  /**
   * For login and register. Sends no token, and a 401 is just a wrong
   * password, not a login that has ended, so nothing is cleared.
   */
  anonymous?: boolean;
}

export const NETWORK_ERROR_TITLE = 'Could not reach the server';

export function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError';
}

/** The most specific message we have for showing to a person. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.detail ?? error.title;
  if (error instanceof Error) return error.message;
  return 'Something went wrong';
}

export type QueryValue = string | number | boolean | undefined | null;

/** Appends a query string, skipping empty values. */
export function withQuery(path: string, params: Record<string, QueryValue>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `${path}?${query}` : path;
}

async function parseError(response: Response): Promise<ApiError> {
  const fallbackTitle = `HTTP ${response.status}`;
  const traceId = traceIdOf(response);
  let body: unknown;
  try {
    body = JSON.parse(await response.text());
  } catch {
    return new ApiError(response.status, fallbackTitle, undefined, traceId);
  }
  if (body && typeof body === 'object') {
    const { title, detail } = body as { title?: unknown; detail?: unknown };
    return new ApiError(
      response.status,
      typeof title === 'string' && title ? title : fallbackTitle,
      typeof detail === 'string' && detail ? detail : undefined,
      traceId,
    );
  }
  return new ApiError(response.status, fallbackTitle, undefined, traceId);
}

type Method = 'GET' | 'PATCH' | 'POST' | 'PUT' | 'DELETE';

/** Sends the request and returns the answer when it is a success. Any failure becomes an ApiError. */
async function send(method: Method, path: string, options: RequestOptions, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = options.anonymous ? null : getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      cache: 'no-store',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: options.signal,
    });
  } catch (error) {
    if (isAbortError(error)) throw error;
    logger.error('Request failed: could not reach the server', { method, path: pathOnly(path), status: 0 });
    throw new ApiError(0, NETWORK_ERROR_TITLE);
  }

  if (!response.ok) {
    const failure = await parseError(response);
    // A refusal the person caused (404, 409, 422 ...) is a warning. The server's own trouble (5xx) is an error.
    const log = response.status >= 500 ? logger.error : logger.warn;
    log('Request failed', { method, path: pathOnly(path), status: response.status, traceId: failure.traceId });
    if (response.status === 401 && !options.anonymous) handleUnauthorized();
    throw failure;
  }
  return response;
}

async function request<T>(method: Method, path: string, options: RequestOptions, body?: unknown): Promise<T> {
  const response = await send(method, path, options, body);
  try {
    return (await response.json()) as T;
  } catch {
    // Typically index.html served for an /api path nobody is routing.
    throw new ApiError(
      response.status,
      'Unexpected response',
      'The server answered with something other than JSON. Check that the load balancer routes /api/ paths to the services.',
      traceIdOf(response),
    );
  }
}

export function getJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return request<T>('GET', path, options);
}

export function patchJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return request<T>('PATCH', path, options);
}

export function postJson<T>(path: string, body: unknown, options: RequestOptions = {}): Promise<T> {
  return request<T>('POST', path, options, body);
}

export function putJson<T>(path: string, body: unknown, options: RequestOptions = {}): Promise<T> {
  return request<T>('PUT', path, options, body);
}

/** For a delete the server answers with 204 and no body. A success has nothing to read. */
export async function deleteNoContent(path: string, options: RequestOptions = {}): Promise<void> {
  await send('DELETE', path, options);
}
