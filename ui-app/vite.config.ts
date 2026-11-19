import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// In production the load balancer routes /api/products*, /api/inventory*,
// /api/users*, /api/cart* and /api/orders* to the services. The dev server
// proxy mimics that for local work.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/products': 'http://localhost:8081',
      '/api/inventory': 'http://localhost:8082',
      '/api/users': 'http://localhost:8083',
      '/api/cart': 'http://localhost:8084',
      '/api/orders': 'http://localhost:8085',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
});
