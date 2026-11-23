import { defineConfig, devices, firefox, webkit } from '@playwright/test';
import type { Project } from '@playwright/test';
import { existsSync } from 'node:fs';

/**
 * End-to-end and accessibility harness for the staff web (WEB-07, UX-01).
 *
 * The base URL is injectable so the same suite can run against a local Vite
 * server or a staged deployment. Vite is started from the module directory;
 * the dev build is required because `/design-preview` is registered only when
 * `import.meta.env.DEV` is true.
 *
 * Projects mirror the supported device classes in
 * `docs/design/responsive-layout.md`: every viewport runs on Chromium, and the
 * representative compact, medium and expanded widths also run on WebKit and
 * Firefox when those engines are present. A browser that is not installed is
 * omitted rather than failing the whole matrix, so a Chromium-only CI job stays
 * green while a full browser-matrix job exercises every engine.
 */
const baseURL = process.env['E2E_BASE_URL'] ?? 'http://127.0.0.1:5173';

/** Playwright engine identifiers used by the `browserName` use option. */
type BrowserName = 'chromium' | 'firefox' | 'webkit';

/**
 * One viewport in the staff-web device matrix (WEB-07).
 *
 * `hasTouch` and `isMobile` model the pointer capabilities of the device class:
 * phones emulate a mobile, coarse-pointer device; tablets emulate a coarse
 * pointer without mobile viewport behaviour; laptop and desktop use a fine
 * pointer. Width is a layout input only — it never changes data or permission.
 */
interface DeviceViewport {
  /** Stable project name: device class plus viewport size, unique per row. */
  readonly name: string;
  /** Viewport inline size in CSS pixels. */
  readonly width: number;
  /** Viewport block size in CSS pixels. */
  readonly height: number;
  /** Whether the device exposes a coarse, touch-capable pointer. */
  readonly hasTouch: boolean;
  /** Whether the browser should apply mobile-viewport emulation. */
  readonly isMobile: boolean;
}

/** Chromium projects covering every supported staff-web layout band. */
const deviceViewports: readonly DeviceViewport[] = [
  { name: 'phone-portrait-360x740', width: 360, height: 740, hasTouch: true, isMobile: true },
  { name: 'phone-portrait-390x844', width: 390, height: 844, hasTouch: true, isMobile: true },
  { name: 'phone-landscape-844x390', width: 844, height: 390, hasTouch: true, isMobile: true },
  { name: 'tablet-portrait-768x1024', width: 768, height: 1024, hasTouch: true, isMobile: false },
  { name: 'tablet-portrait-820x1180', width: 820, height: 1180, hasTouch: true, isMobile: false },
  { name: 'tablet-landscape-1024x768', width: 1024, height: 768, hasTouch: true, isMobile: false },
  { name: 'tablet-landscape-1180x820', width: 1180, height: 820, hasTouch: true, isMobile: false },
  { name: 'laptop-1280x720', width: 1280, height: 720, hasTouch: false, isMobile: false },
  { name: 'desktop-1920x1080', width: 1920, height: 1080, hasTouch: false, isMobile: false },
];

/** Representative widths that additionally run on WebKit and Firefox. */
const crossBrowserViewportNames: ReadonlySet<string> = new Set([
  'phone-portrait-390x844',
  'tablet-portrait-820x1180',
  'laptop-1280x720',
]);

/**
 * Build one Playwright project for a device viewport and browser engine.
 *
 * `isMobile` is a Chromium-only emulation switch — Firefox rejects it — so it
 * is applied only to Chromium projects. Touch emulation is supported by every
 * engine and is retained for the coarse-pointer phone and tablet classes.
 */
function projectFor(device: DeviceViewport, browser: BrowserName): Project {
  return {
    name: browser === 'chromium' ? device.name : `${device.name}-${browser}`,
    use: {
      browserName: browser,
      viewport: { width: device.width, height: device.height },
      ...(device.hasTouch ? { hasTouch: true } : {}),
      ...(device.isMobile && browser === 'chromium' ? { isMobile: true } : {}),
    },
  };
}

/** True when a non-Chromium engine's Playwright binary exists in this image. */
function isBrowserInstalled(browser: Exclude<BrowserName, 'chromium'>): boolean {
  return existsSync((browser === 'webkit' ? webkit : firefox).executablePath());
}

/** WebKit and Firefox variants for the representative cross-browser widths. */
function crossBrowserProjects(): Project[] {
  const projects: Project[] = [];
  for (const device of deviceViewports) {
    if (!crossBrowserViewportNames.has(device.name)) continue;
    for (const browser of ['webkit', 'firefox'] as const) {
      if (isBrowserInstalled(browser)) projects.push(projectFor(device, browser));
    }
  }
  return projects;
}

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
    // The original desktop-Chromium project is kept unchanged; the config-level
    // retries and web-server timeout below are preserved as they were.
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    ...deviceViewports.map((device) => projectFor(device, 'chromium')),
    ...crossBrowserProjects(),
  ],
  // Start a local dev server only for the default base URL. When a caller
  // supplies E2E_BASE_URL it owns the server (for example a staged dev build),
  // so starting and probing a local server would race or fail in CI. The key is
  // omitted rather than set to undefined so the config stays valid under
  // exactOptionalPropertyTypes.
  ...(process.env['E2E_BASE_URL']
    ? {}
    : {
        webServer: {
          // Bind IPv4 loopback explicitly: `localhost` can resolve to ::1,
          // while the default baseURL above is 127.0.0.1.
          command: 'npm run dev -- --host 127.0.0.1',
          url: baseURL,
          cwd: '..',
          reuseExistingServer: !process.env['CI'],
          timeout: 120_000,
        },
      }),
});
