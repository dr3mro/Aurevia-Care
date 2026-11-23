import { defineConfig } from 'vitest/config';

// Root tooling tests only. Application tests run in their own module gate with
// that module's jsdom setup (apps/staff-web/vite.config.ts), not here.
export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['tools/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      include: ['tools/**/*.ts'],
      exclude: ['tools/**/*.test.ts'],
      all: false,
      thresholds: { lines: 100, functions: 100, branches: 100, statements: 100 },
    },
  },
});
