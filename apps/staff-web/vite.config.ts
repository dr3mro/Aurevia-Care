/// <reference types="vitest" />
import { readFileSync } from 'node:fs';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The display name is a build-time input from the repository identity file,
// never a hard-coded string in application source.
const project = JSON.parse(
  readFileSync(new URL('../../project.json', import.meta.url), 'utf8'),
) as { displayName: string };

export default defineConfig({
  plugins: [react()],
  define: {
    __APP_DISPLAY_NAME__: JSON.stringify(project.displayName),
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
    clearMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      // Generated contract types and declaration files contain no runnable
      // statements; they are produced by scripts/gen-api-types.sh and kept
      // out of the hand-written coverage scope by this documented rule.
      exclude: [
        'src/api/generated/**',
        '**/*.d.ts',
        '**/*.test.{ts,tsx}',
        'dist/**',
        'coverage/**',
      ],
      reporter: ['text', 'json-summary'],
      thresholds: {
        perFile: true,
        lines: 100,
        branches: 100,
        functions: 100,
        statements: 100,
      },
    },
  },
});
