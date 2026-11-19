import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['frontend/**/*.test.ts', 'apps/**/src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      include: ['frontend/**/*.ts', 'apps/**/src/**/*.ts', 'apps/**/src/**/*.tsx'],
      exclude: ['frontend/**/*.test.ts', 'apps/**/src/**/*.test.ts', 'apps/**/src/**/*.test.tsx'],
      all: false,
      thresholds: { lines: 100, functions: 100, branches: 100, statements: 100 },
    },
  },
});
