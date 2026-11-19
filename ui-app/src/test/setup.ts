import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { setUnauthorizedHandler } from '../auth/session';
import { clearProductLookupCache } from '../hooks/useProducts';

const QUIET = ['error', 'warn', 'info', 'debug'] as const;
let spies: { mockRestore: () => void }[] = [];

beforeEach(() => {
  // The app logs failed requests to the console. Many tests fail a request on purpose, so keep the output
  // clean. A test that wants to read the lines looks at the same spies: vi.mocked(console.warn).mock.calls.
  spies = QUIET.map((method) => vi.spyOn(console, method).mockImplementation(() => {}));
});

afterEach(() => {
  cleanup();
  for (const spy of spies) spy.mockRestore();
  vi.unstubAllGlobals();
  // A login kept by one test must never leak into the next one.
  localStorage.clear();
  setUnauthorizedHandler(null);
  // So is a product looked up by one test (the cart and the orders remember products by SKU).
  clearProductLookupCache();
});
