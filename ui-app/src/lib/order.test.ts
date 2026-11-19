import { makeOrder } from '../test/helpers';
import { CART_NOT_CLEARED, placedState, readPlaced, STATUS_LABEL } from './order';

describe('placedState and readPlaced', () => {
  it('carry the order number and whether the cart was emptied from the cart page to the orders page', () => {
    expect(readPlaced(placedState(makeOrder(12, { cartCleared: true })))).toEqual({ orderId: 12, cartCleared: true });
    expect(readPlaced(placedState(makeOrder(13, { cartCleared: false })))).toEqual({ orderId: 13, cartCleared: false });
  });

  it('count a missing cartCleared as cleared, because only an explicit false is a warning', () => {
    expect(readPlaced(placedState(makeOrder(14)))).toEqual({ orderId: 14, cartCleared: true });
    expect(readPlaced(placedState(makeOrder(15, { cartCleared: null })))).toEqual({ orderId: 15, cartCleared: true });
  });

  it.each([undefined, null, 'x', 3, {}, { placed: null }, { placed: 'x' }, { placed: {} }, { placed: { orderId: '1' } }, { placed: { orderId: NaN } }])(
    'readPlaced ignores %j',
    (state) => {
      expect(readPlaced(state)).toBeNull();
    },
  );
});

describe('texts', () => {
  it('says the order is confirmed and what to do about the cart, in the words of the plan', () => {
    expect(CART_NOT_CLEARED).toBe(
      'Your order is confirmed. We could not empty your cart, please remove the items yourself before ordering again.',
    );
  });

  it('has a plain label for every status', () => {
    expect(STATUS_LABEL).toEqual({ PENDING: 'Pending', CONFIRMED: 'Confirmed', REJECTED: 'Rejected' });
  });
});
