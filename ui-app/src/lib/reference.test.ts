import { ApiError } from '../api/client';
import { failureText } from './cart';
import { referenceOf, withReference } from './reference';

describe('referenceOf', () => {
  it('words the trace id as a plain "Reference: <id>"', () => {
    expect(referenceOf(new ApiError(500, 'Internal Server Error', 'Boom', '4bf92f3577b34da6'))).toBe(
      'Reference: 4bf92f3577b34da6',
    );
  });

  it('is empty when the server sent no trace id, or the error is not an ApiError', () => {
    expect(referenceOf(new ApiError(500, 'Internal Server Error', 'Boom'))).toBeUndefined();
    expect(referenceOf(new Error('x'))).toBeUndefined();
    expect(referenceOf('text')).toBeUndefined();
    expect(referenceOf(undefined)).toBeUndefined();
  });
});

describe('withReference', () => {
  it('ends the text with the reference', () => {
    expect(withReference('Database is down', new ApiError(500, 'Error', 'Database is down', 'abc123'))).toBe(
      'Database is down. Reference: abc123',
    );
  });

  it('does not add a second full stop', () => {
    expect(withReference('Try again.', new ApiError(503, 'Error', undefined, 'abc123'))).toBe(
      'Try again. Reference: abc123',
    );
  });

  it('returns the text untouched when there is no reference', () => {
    expect(withReference('Try again', new ApiError(503, 'Error'))).toBe('Try again');
  });
});

describe('failureText with a trace id', () => {
  it('ends the reason with the reference', () => {
    expect(failureText(new ApiError(409, 'Conflict', 'Not enough stock for A', 'trace-9'))).toBe(
      'Not enough stock for A. Reference: trace-9',
    );
  });

  it('leaves the offline text alone, because no request reached a server', () => {
    expect(failureText(new ApiError(0, 'Could not reach the server'))).toBe(
      'Could not reach the server. Please try again.',
    );
  });
});
