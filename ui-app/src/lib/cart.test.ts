import { ApiError } from '../api/client';
import { failureText, MAX_QUANTITY, parseQuantity, QUANTITY_HINT } from './cart';

describe('parseQuantity', () => {
  it.each([
    ['1', 1],
    ['99', 99],
    ['42', 42],
    [' 7 ', 7],
    ['007', 7],
  ])('accepts "%s"', (text, expected) => {
    expect(parseQuantity(text)).toBe(expected);
  });

  it.each(['', ' ', '0', '100', '-1', '1.5', '1e1', '+3', '12abc', 'abc', '99999999999999999999', '１２'])(
    'refuses "%s"',
    (text) => {
      expect(parseQuantity(text)).toBeNull();
    },
  );

  it('uses the same limit as the server and says it in the hint', () => {
    expect(MAX_QUANTITY).toBe(99);
    expect(QUANTITY_HINT).toBe('Enter a whole number from 1 to 99.');
  });
});

describe('failureText', () => {
  it('uses the detail the server sent', () => {
    expect(failureText(new ApiError(409, 'Conflict', 'Not enough stock for A: wanted 3, available 1'))).toBe(
      'Not enough stock for A: wanted 3, available 1',
    );
  });

  it('falls back to the title when there is no detail', () => {
    expect(failureText(new ApiError(502, 'HTTP 502'))).toBe('HTTP 502');
  });

  it('uses its own words only when the server could not be reached', () => {
    expect(failureText(new ApiError(0, 'Could not reach the server'))).toBe('Could not reach the server. Please try again.');
    expect(failureText(new ApiError(0, 'Could not reach the server'), 'Custom words.')).toBe('Custom words.');
  });

  it('copes with something that is not an ApiError', () => {
    expect(failureText(new Error('boom'))).toBe('boom');
    expect(failureText('oops')).toBe('Something went wrong');
  });
});
