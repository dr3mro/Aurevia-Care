/**
 * Synthetic mobile performance evidence for the staff web (WEB-07, UX-01).
 *
 * Two independent proofs live here. A browser-free test measures every built
 * JavaScript chunk against the WEB-08 gzip budget published by
 * `scripts/check-bundle-size.mjs`. A Playwright test drives the production
 * build through the supported device matrix and records LCP, INP and CLS with
 * `PerformanceObserver` after a mid-range-phone CPU and network throttle.
 *
 * The metrics are task-proposed mobile targets (4 s LCP); WEB-08 owns the
 * laptop p75 numbers. No endpoint, credential or patient data is used. The
 * preview server and observers are created and torn down inside this file so
 * the spec is runnable without editing the shared Playwright configuration.
 */
import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { expect, test } from '@playwright/test';

/** Decimal-byte gzip budget mirrored from the CI gate; drift is asserted below. */
const MAX_CHUNK_GZIP_BYTES = 256_000;

/** Task-proposed mobile web-vital targets; WEB-08 owns the laptop p75 numbers. */
const MAX_LCP_MS = 4_000;
const MAX_INP_MS = 200;
const MAX_CLS = 0.1;

/** Sign-in-free, API-free routes measured in every device project. */
const ROUTES: readonly string[] = ['/', '/signed-out'];

/** Slow-4G profile: 150 ms RTT, 1.6 Mbps down and 750 Kbps up. */
const SLOW_4G_LATENCY_MS = 150;
const SLOW_4G_DOWNLOAD_BYTES_PER_SECOND = 163_840;
const SLOW_4G_UPLOAD_BYTES_PER_SECOND = 98_304;

/** CPU slowdown that approximates a mid-range phone. */
const CPU_THROTTLE_RATE = 4;

/** Bounds for the cross-worker build lock so a killed worker cannot hang a run. */
const MAX_BUILD_LOCK_WAIT_MS = 120_000;
const STALE_BUILD_LOCK_MS = 300_000;
const LOCK_RETRY_MS = 200;

/** Samples published by the injected observers into the measured page. */
interface PerformanceSamples {
  largestContentfulPaintMs: number | null;
  interactionToNextPaintMs: number | null;
  cumulativeLayoutShift: number;
}

/** A preview process bound to one worker and its readiness URL. */
interface PreviewServer {
  readonly url: string;
  stop(): void;
}

declare global {
  interface Window {
    /** Observer samples, installed before navigation by `addInitScript`. */
    __staffWebPerformance?: PerformanceSamples;
    /** Observer handles retained so the measured page can disconnect them. */
    __staffWebPerformanceObservers?: PerformanceObserver[];
  }
}

/** Absolute path of the staff-web module, derived from this spec's location. */
const moduleRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** Exclusive build lock shared by every Playwright worker using this checkout. */
const buildLock = join(
  tmpdir(),
  `staff-web-performance-${moduleRoot.replace(/[^A-Za-z0-9]/g, '_')}.lock`,
);
/** Ownership marker so a worker never removes a lock held by another worker. */
const buildLockOwner = `${process.pid}-${randomBytes(6).toString('hex')}`;
const buildLockOwnerFile = join(buildLock, 'owner');

/** True when an error has the given Node filesystem code. */
function hasFilesystemCode(error: unknown, code: string): boolean {
  return error instanceof Error && 'code' in error && error.code === code;
}

/** True while this worker still owns the build lock directory. */
function ownsBuildLock(): boolean {
  try {
    return readFileSync(buildLockOwnerFile, 'utf8') === buildLockOwner;
  } catch (error: unknown) {
    if (hasFilesystemCode(error, 'ENOENT')) {
      return false;
    }
    throw error;
  }
}

/** Clear a build lock left behind by a worker that died mid-build. */
function removeStaleBuildLock(): void {
  if (ownsBuildLock()) {
    return;
  }
  try {
    if (Date.now() - statSync(buildLock).mtimeMs > STALE_BUILD_LOCK_MS) {
      rmSync(buildLock, { recursive: true, force: true });
    }
  } catch (error: unknown) {
    if (!hasFilesystemCode(error, 'ENOENT')) {
      throw error;
    }
  }
}

/** Release the lock only if it still carries this worker's owner marker. */
function releaseBuildLock(): void {
  if (ownsBuildLock()) {
    rmSync(buildLock, { recursive: true, force: true });
  }
}

/** Block for a short, deterministic interval without an external dependency. */
function pauseMilliseconds(milliseconds: number): void {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

/** Read the budget published by the CI gate so a drifted mirror fails the proof. */
function publishedGzipBudget(): string {
  const gate = readFileSync(join(moduleRoot, 'scripts', 'check-bundle-size.mjs'), 'utf8');
  const declared = /export const MAX_CHUNK_GZIP_BYTES\s*=\s*([\d_]+)\s*;/.exec(gate)?.[1] ?? '';
  return declared.replaceAll('_', '');
}

/**
 * Ensure a production build exists, serialising concurrent Playwright workers
 * with an exclusive directory lock, and return its emitted JavaScript names.
 */
function ensureProductionBuild(): string[] {
  const assetsDir = join(moduleRoot, 'dist', 'assets');
  const names = (): string[] =>
    existsSync(assetsDir)
      ? readdirSync(assetsDir).filter((name) => name.endsWith('.js')).sort()
      : [];
  // The existence check lives inside the lock: reading dist while another
  // worker is mid-build would return an incomplete chunk list.
  const deadline = Date.now() + MAX_BUILD_LOCK_WAIT_MS;
  for (;;) {
    try {
      mkdirSync(buildLock);
      writeFileSync(buildLockOwnerFile, buildLockOwner);
      break;
    } catch (error: unknown) {
      if (!hasFilesystemCode(error, 'EEXIST')) {
        throw error;
      }
      removeStaleBuildLock();
      if (Date.now() > deadline) {
        throw new Error(`Timed out waiting for the production build lock at ${buildLock}`);
      }
      pauseMilliseconds(LOCK_RETRY_MS);
    }
  }
  try {
    if (names().length === 0) {
      execFileSync('npm', ['run', 'build'], { cwd: moduleRoot, stdio: 'pipe' });
    }
    // Capture the list while still holding the lock, then return through
    // `finally` so no caller can observe a half-built chunk set.
    return names();
  } finally {
    releaseBuildLock();
  }
}

/** Reject a built asset whose gzipped size exceeds the WEB-08 budget. */
function requireWithinGzipBudget(contents: Uint8Array, label: string): number {
  const size = gzipSync(contents).byteLength;
  if (size > MAX_CHUNK_GZIP_BYTES) {
    throw new Error(`${label}: ${size} bytes gzipped exceeds the ${MAX_CHUNK_GZIP_BYTES} byte budget (WEB-08)`);
  }
  return size;
}

/** Start a production preview on a worker-specific port and wait until it answers. */
async function startPreviewServer(workerIndex: number): Promise<PreviewServer> {
  const port = 4_200 + workerIndex;
  const vite = join(moduleRoot, 'node_modules', '.bin', 'vite');
  const child = spawn(
    vite,
    ['preview', '--host', '127.0.0.1', '--port', String(port), '--strictPort'],
    { cwd: moduleRoot, stdio: ['ignore', 'ignore', 'pipe'] },
  );
  let previewErrors = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk: string) => {
    if (previewErrors.length < 4_000) {
      previewErrors += chunk;
    }
  });
  child.on('error', (error: Error) => {
    previewErrors += `${error.message}\n`;
  });
  const url = `http://127.0.0.1:${port}/`;
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      // A 200 from a different local process must not be mistaken for the app.
      if (response.ok && (await response.text()).includes('Aurevia Staff Portal')) {
        return { url, stop: (): void => { child.kill('SIGTERM'); } };
      }
    } catch {
      // The preview process is not listening yet; the loop retries.
    }
    pauseMilliseconds(200);
  }
  child.kill('SIGTERM');
  const reason = previewErrors.trim() === '' ? '' : `: ${previewErrors.trim()}`;
  throw new Error(`The production preview at ${url} did not become ready${reason}`);
}

/** One preview server per worker, created on first use and closed in `afterAll`. */
let previewServer: PreviewServer | undefined;

/** Return the base URL of the built application for this worker. */
async function productionBaseUrl(workerIndex: number): Promise<string> {
  if (previewServer === undefined) {
    ensureProductionBuild();
    previewServer = await startPreviewServer(workerIndex);
  }
  return previewServer.url;
}

test.afterAll(() => {
  previewServer?.stop();
});

/**
 * Install buffered observers in the measured page before navigation.
 *
 * The function is serialised into the browser, so every declaration it uses
 * is local. Layout shift counts only entries without recent input, and INP
 * counts only entries with a positive `interactionId`, so passive page load
 * cannot satisfy either assertion.
 */
function installPerformanceObservers(): void {
  interface EventTimingEntry extends PerformanceEntry {
    readonly interactionId: number;
  }
  interface LayoutShiftEntry extends PerformanceEntry {
    readonly hadRecentInput: boolean;
    readonly value: number;
  }
  interface EventObserverInit extends PerformanceObserverInit {
    durationThreshold: number;
  }

  function isEventTiming(entry: PerformanceEntry): entry is EventTimingEntry {
    return 'interactionId' in entry && typeof entry.interactionId === 'number';
  }

  function isLayoutShift(entry: PerformanceEntry): entry is LayoutShiftEntry {
    return (
      'hadRecentInput' in entry &&
      typeof entry.hadRecentInput === 'boolean' &&
      'value' in entry &&
      typeof entry.value === 'number'
    );
  }

  const samples: PerformanceSamples = {
    largestContentfulPaintMs: null,
    interactionToNextPaintMs: null,
    cumulativeLayoutShift: 0,
  };
  const observers: PerformanceObserver[] = [];
  window.__staffWebPerformance = samples;
  window.__staffWebPerformanceObservers = observers;

  const largestPaint = new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      if (
        samples.largestContentfulPaintMs === null ||
        entry.startTime > samples.largestContentfulPaintMs
      ) {
        samples.largestContentfulPaintMs = entry.startTime;
      }
    }
  });
  largestPaint.observe({ type: 'largest-contentful-paint', buffered: true });
  observers.push(largestPaint);

  const interaction = new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      if (!isEventTiming(entry) || entry.interactionId <= 0) {
        continue;
      }
      if (
        samples.interactionToNextPaintMs === null ||
        entry.duration > samples.interactionToNextPaintMs
      ) {
        samples.interactionToNextPaintMs = entry.duration;
      }
    }
  });
  // Threshold zero keeps sub-104 ms clicks observable, so a fast interaction
  // cannot leave the INP assertion vacuously unsatisfied.
  const eventOptions: EventObserverInit = { type: 'event', buffered: true, durationThreshold: 0 };
  interaction.observe(eventOptions);
  observers.push(interaction);

  const layout = new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      if (!isLayoutShift(entry) || entry.hadRecentInput) {
        continue;
      }
      samples.cumulativeLayoutShift += entry.value;
    }
  });
  layout.observe({ type: 'layout-shift', buffered: true });
  observers.push(layout);
}

/** Return a recorded metric or fail with an attributable message. */
function requireMetric(value: number | null, where: string, metric: string): number {
  if (value === null) {
    throw new Error(`${where}: ${metric} entry was not recorded`);
  }
  return value;
}

test('built route chunks stay within the WEB-08 gzip budget', () => {
  test.setTimeout(180_000);
  expect(
    publishedGzipBudget(),
    'the mirrored budget must match scripts/check-bundle-size.mjs',
  ).toBe(String(MAX_CHUNK_GZIP_BYTES));

  const names = ensureProductionBuild();
  expect(names.length, 'the production build must emit JavaScript chunks').toBeGreaterThan(0);
  const assetsDir = join(moduleRoot, 'dist', 'assets');
  for (const name of names) {
    const size = requireWithinGzipBudget(readFileSync(join(assetsDir, name)), name);
    expect(size, `${name} must stay within the WEB-08 gzip budget`).toBeLessThanOrEqual(
      MAX_CHUNK_GZIP_BYTES,
    );
  }
});

test('the gzip budget assertion rejects an oversized synthetic chunk', () => {
  const oversized = randomBytes(300 * 1024);
  expect(gzipSync(oversized).byteLength).toBeGreaterThan(MAX_CHUNK_GZIP_BYTES);
  expect(() => requireWithinGzipBudget(oversized, 'oversized.js')).toThrow(/oversized\.js/);
  expect(() => requireWithinGzipBudget(oversized, 'oversized.js')).toThrow(/WEB-08/);
  const compressible = new Uint8Array(10 * 1024).fill(0x41);
  expect(requireWithinGzipBudget(compressible, 'small.js')).toBeLessThanOrEqual(
    MAX_CHUNK_GZIP_BYTES,
  );
});

for (const route of ROUTES) {
  test(`mobile web vitals stay within budget on ${route}`, async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const projectName = testInfo.project.name;
    const where = `${route} on ${projectName}`;

    // CDP emulation is a Chromium capability; other engines are not measured
    // by this spec and are reported as fixme rather than as a product defect.
    if (testInfo.project.use.browserName !== 'chromium') {
      test.fixme('CDP CPU and network throttling is available only in Chromium projects');
    }

    await page.addInitScript(installPerformanceObservers);
    const session = await page.context().newCDPSession(page);
    try {
      await session.send('Emulation.setCPUThrottlingRate', { rate: CPU_THROTTLE_RATE });
      await session.send('Network.emulateNetworkConditions', {
        offline: false,
        latency: SLOW_4G_LATENCY_MS,
        downloadThroughput: SLOW_4G_DOWNLOAD_BYTES_PER_SECOND,
        uploadThroughput: SLOW_4G_UPLOAD_BYTES_PER_SECOND,
      });

      const baseUrl = await productionBaseUrl(testInfo.workerIndex);
      // Resolve against the base URL: naive concatenation produces
      // `//signed-out`, whose empty path segment does not match any route.
      await page.goto(new URL(route, baseUrl).href);
      await expect(page.locator('main#main')).toBeVisible();

      await expect
        .poll(
          () => page.evaluate(() => window.__staffWebPerformance?.largestContentfulPaintMs ?? null),
          { message: `${where}: largest-contentful-paint entry was not recorded`, timeout: 20_000 },
        )
        .not.toBeNull();

      // Read layout shift before the interaction: opening the compact drawer
      // is a deliberate UI change and must not count against page stability.
      // Two animation frames give buffered shifts a chance to arrive without
      // a fixed wall-clock wait that would depend on the runner's speed.
      await page.evaluate(
        () => new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        }),
      );
      const beforeInteraction = await page.evaluate(() => window.__staffWebPerformance ?? null);
      if (beforeInteraction === null) {
        throw new Error(`${where}: PerformanceObserver samples disappeared before the interaction`);
      }
      const layoutShiftBeforeClick = beforeInteraction.cumulativeLayoutShift;

      await page.locator('header button:visible').first().click();

      await expect
        .poll(
          () => page.evaluate(() => window.__staffWebPerformance?.interactionToNextPaintMs ?? null),
          {
            message: `${where}: interaction-to-next-paint entry was not recorded after a real click`,
            timeout: 20_000,
          },
        )
        .not.toBeNull();

      const samples = await page.evaluate(() => {
        const stored = window.__staffWebPerformance ?? null;
        const observers = window.__staffWebPerformanceObservers ?? [];
        for (const observer of observers) {
          observer.disconnect();
        }
        delete window.__staffWebPerformanceObservers;
        return stored;
      });
      if (samples === null) {
        throw new Error(`${where}: PerformanceObserver samples were not recorded`);
      }

      const largestPaint = requireMetric(
        samples.largestContentfulPaintMs,
        where,
        'largest-contentful-paint',
      );
      const nextPaint = requireMetric(
        samples.interactionToNextPaintMs,
        where,
        'interaction-to-next-paint',
      );
      expect(largestPaint, `${where}: largest-contentful-paint`).toBeLessThanOrEqual(MAX_LCP_MS);
      expect(nextPaint, `${where}: interaction-to-next-paint`).toBeLessThanOrEqual(MAX_INP_MS);
      expect(layoutShiftBeforeClick, `${where}: cumulative layout shift`).toBeLessThanOrEqual(
        MAX_CLS,
      );
    } finally {
      await session.detach();
    }
  });
}
