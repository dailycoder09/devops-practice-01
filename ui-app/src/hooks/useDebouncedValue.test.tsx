import { act, renderHook } from '@testing-library/react';
import { useDebouncedValue } from './useDebouncedValue';

describe('useDebouncedValue', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('starts with the value it is given', () => {
    const { result } = renderHook(() => useDebouncedValue('first', 300));
    expect(result.current).toBe('first');
  });

  it('changes only after the value has stayed the same for the delay', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    });

    rerender({ value: 'ab' });
    act(() => void vi.advanceTimersByTime(299));
    expect(result.current).toBe('a');

    act(() => void vi.advanceTimersByTime(1));
    expect(result.current).toBe('ab');
  });

  it('starts the wait again with every change, so fast typing makes one change', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: '' },
    });

    rerender({ value: 'a' });
    act(() => void vi.advanceTimersByTime(200));
    rerender({ value: 'ab' });
    act(() => void vi.advanceTimersByTime(200));
    rerender({ value: 'abc' });
    act(() => void vi.advanceTimersByTime(200));
    expect(result.current).toBe('');

    act(() => void vi.advanceTimersByTime(100));
    expect(result.current).toBe('abc');
  });
});
