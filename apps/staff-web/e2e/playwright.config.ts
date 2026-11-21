import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end and accessibility harness for the staff web (WEB-10, UX-01).
 *
 * The base URL is injectable so the same suite can run against a local Vite
 * server or a staged deployment. Vite is started from the module directory;
 * the dev build is required because `/design-preview` is registered only when
 * `import.meta.env.DEV` is true.
 */
const baseURL = process.env['E2E_BASE_URL'] ?? 'http://127.0.0.1:5173';

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  retries: 1,
  reporter: 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // Start a local dev server only for the default base URL. When a caller
  // supplies E2E_BASE_URL it owns the server (for example a staged dev build),
  // so starting and probing a local server would race or fail in CI.
  webServer: process.env['E2E_BASE_URL']
    ? undefined
    : {
        // Bind IPv4 loopback explicitly: `localhost` can resolve to ::1, while
        // the default baseURL above is 127.0.0.1.
        command: 'npm run dev -- --host 127.0.0.1',
        url: baseURL,
        cwd: '..',
        reuseExistingServer: !process.env['CI'],
        timeout: 120_000,
      },
});
