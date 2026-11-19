import { ApiError } from '../api/client';
import { withPeriod } from './format';

/**
 * The trace id the server gave to a failed request, as a plain sentence a person can read out to support
 * ("Reference: 4bf92f3577b34da6a3ce929d0e0e4736"). Nothing when the server sent none.
 */
export function referenceOf(error: unknown): string | undefined {
  return error instanceof ApiError && error.traceId ? `Reference: ${error.traceId}` : undefined;
}

/** The text, ended with the reference when there is one. Without a reference the text is returned as it is. */
export function withReference(text: string, error: unknown): string {
  const reference = referenceOf(error);
  return reference ? `${withPeriod(text)} ${reference}` : text;
}
