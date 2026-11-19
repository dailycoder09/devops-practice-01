import { ApiError, errorMessage } from '../api/client';
import { withReference } from './reference';

export const MAX_QUANTITY = 99;

/** What to tell a person who typed a quantity the server would refuse. */
export const QUANTITY_HINT = `Enter a whole number from 1 to ${MAX_QUANTITY}.`;

/** The quantity typed in a box, or null when it is not a whole number from 1 to 99. */
export function parseQuantity(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const quantity = Number(trimmed);
  return quantity >= 1 && quantity <= MAX_QUANTITY ? quantity : null;
}

/**
 * The reason for a failed cart or order call, in the server's own words
 * (they are already plain sentences). Only a call that never reached the
 * server needs words of our own. When the server gave the request an id, the text ends with
 * "Reference: <id>".
 */
export function failureText(error: unknown, offline = 'Could not reach the server. Please try again.'): string {
  if (error instanceof ApiError && error.status === 0) return offline;
  return withReference(errorMessage(error), error);
}
