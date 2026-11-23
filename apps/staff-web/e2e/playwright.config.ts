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

/** A viewport declared by the responsive staff-web contract, in CSS pixels. */
interface DeviceViewport {
  width: number;
  height: number;
}

/**
 * One staff-web device class at one viewport. `touch` and `mobile` describe
 * pointer emulation; `crossBrowser` opts the size into the WebKit and Firefox
 * variants, which are enabled only when E2E_CROSS_BROWSER=1 (CI installs those
 * engines before setting it).
 */
interface DeviceTarget {
  name: string;
  viewport: DeviceViewport;
  touch: boolean;
  mobile: boolean;
  crossBrowser: boolean;
}

/**
 * The supported device matrix. Sizes are declared once here so a project name
 * and its viewport cannot drift apart; the story's acceptance sizes are
 * authoritative over the older figures in the design verification table.
 */
const deviceTargets: DeviceTarget[] = [
  {
    name: 'phone-portrait-360x740',
    viewport: { width: 360, height: 740 },
    touch: true,
    mobile: true,
    crossBrowser: false,
  },
  {
    name: 'phone-portrait-390x844',
    viewport: { width: 390, height: 844 },
    touch: true,
    mobile: true,
    crossBrowser: true,
  },
  {
    name: 'phone-landscape-844x390',
    viewport: { width: 844, height: 390 },
    touch: true,
    mobile: true,
    crossBrowser: false,
  },
  {
    name: 'tablet-portrait-768x1024',
    viewport: { width: 768, height: 1024 },
    touch: true,
    mobile: false,
    crossBrowser: false,
  },
  {
    name: 'tablet-portrait-820x1180',
    viewport: { width: 820, height: 1180 },
    touch: true,
    mobile: false,
    crossBrowser: true,
  },
  {
    name: 'tablet-landscape-1024x768',
    viewport: { width: 1024, height: 768 },
    touch: true,
    mobile: false,
    crossBrowser: false,
  },
  {
    name: 'tablet-landscape-1180x820',
    viewport: { width: 1180, height: 820 },
    touch: true,
    mobile: false,
    crossBrowser: false,
  },
  {
    name: 'laptop-1280x720',
    viewport: { width: 1280, height: 720 },
    touch: false,
    mobile: false,
    crossBrowser: true,
  },
  {
    name: 'desktop-1920x1080',
    viewport: { width: 1920, height: 1080 },
    touch: false,
    mobile: false,
    crossBrowser: false,
  },
];

/**
 * Build the Chromium project for one device target. The Chrome descriptor
 * supplies the engine; viewport, screen and pointer flags are overwritten so
 * the run matches the device class its name claims to represent.
 */
function chromiumProject(target: DeviceTarget) {
  return {
    name: target.name,
    use: {
      ...devices['Desktop Chrome'],
      viewport: target.viewport,
      screen: target.viewport,
      isMobile: target.mobile,
      hasTouch: target.touch,
    },
  };
}

/**
 * Build an opt-in non-Chromium variant. `isMobile` is a Chromium-only option,
 * so coarse pointers use `hasTouch` alone; `browserName` selects the engine and
 * no Chromium device descriptor is spread into the options.
 */
function crossBrowserProject(target: DeviceTarget, browserName: 'webkit' | 'firefox') {
  return {
    name: `${target.name}-${browserName}`,
    use: {
      browserName,
      viewport: target.viewport,
      screen: target.viewport,
      isMobile: false,
      hasTouch: target.touch,
    },
  };
}

// WebKit and Firefox are opt-in: local runs need only the Chromium binary,
// while CI can install those engines and enable them with this flag.
const crossBrowserEnabled = process.env['E2E_CROSS_BROWSER'] === '1';

const deviceProjects = deviceTargets.flatMap((target) => [
  chromiumProject(target),
  ...(crossBrowserEnabled && target.crossBrowser
    ? [crossBrowserProject(target, 'webkit'), crossBrowserProject(target, 'firefox')]
    : []),
]);

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
    ...deviceProjects,
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
