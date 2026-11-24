/**
 * WEB-07 clause 5 evidence, staff web: the end-to-end suite declares a browser
 * matrix. The Playwright config is read as text rather than imported because
 * `isBrowserInstalled()` drops WebKit and Firefox when their binaries are
 * absent, so an imported project list would hide part of the declared matrix in
 * this image. These checks prove declaration and wiring only: no browser is
 * launched and no matrix run is claimed. Synthetic fixtures only — no patient
 * data, credentials or network calls.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/** Module directory; Vitest's `import.meta.url` is not a file URL for `readFileSync`. */
const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..', '..', '..');

/** Playwright config source, read as text so every declared engine stays visible. */
const configSource: string = readFileSync(
  resolve(moduleRoot, 'e2e', 'playwright.config.ts'),
  'utf8',
);

/** Package source, read as text to prove the config is the e2e entry point. */
const packageSource: string = readFileSync(resolve(moduleRoot, 'package.json'), 'utf8');

/** Engines the matrix must declare; WebKit stands in for desktop Safari. */
const REQUIRED_ENGINES = ['chromium', 'webkit', 'firefox'] as const;

/** Device projects that carry the WEB-07 compact and desktop floors. */
const REQUIRED_VIEWPORTS = [
  { name: 'tablet-portrait-768x1024', width: 768, height: 1024 },
  { name: 'laptop-1280x720', width: 1280, height: 720 },
] as const;

/** Exact script that runs the declared matrix through Playwright. */
const E2E_SCRIPT = '"e2e": "playwright test --config e2e/playwright.config.ts"';

/**
 * Assert that the cross-browser representatives and the project wiring survive.
 * Token presence alone is not enough: a config can declare a width but never
 * add it to the exported project list, so both are checked.
 */
function assertCrossBrowserWiring(source: string): void {
  const crossBrowserMatch = /crossBrowserViewportNames[^=]*= new Set\(\[([\s\S]*?)\]\);/.exec(
    source,
  );
  const crossBrowserNames = crossBrowserMatch?.[1] ?? '';
  if (!crossBrowserNames.includes("'laptop-1280x720'")) {
    throw new Error('browser matrix does not run the desktop viewport cross-browser');
  }
  if (!source.includes('...crossBrowserProjects()')) {
    throw new Error('browser matrix does not add cross-browser projects to the suite');
  }
  if (!source.includes("for (const browser of ['webkit', 'firefox'] as const)")) {
    throw new Error('browser matrix does not iterate the WebKit and Firefox engines');
  }
  if (!source.includes("...deviceViewports.map((device) => projectFor(device, 'chromium'))")) {
    throw new Error('browser matrix does not add device viewports to the suite');
  }
}

/**
 * Assert that a Playwright config source declares and wires the WEB-07 matrix.
 *
 * @param source Real config text or a synthetic mutation of it.
 * @throws When an engine, viewport or wiring step is missing, so the negative
 * tests can prove the check is not a no-op.
 */
function assertBrowserMatrix(source: string): void {
  for (const engine of REQUIRED_ENGINES) {
    if (!source.includes(`'${engine}'`)) {
      throw new Error(`browser matrix omits the ${engine} engine`);
    }
  }
  for (const viewport of REQUIRED_VIEWPORTS) {
    const declaration =
      `name: '${viewport.name}', width: ${viewport.width}, height: ${viewport.height}`;
    if (!source.includes(declaration)) {
      throw new Error(`browser matrix omits ${viewport.name} at ${viewport.width}x${viewport.height}`);
    }
  }
  assertCrossBrowserWiring(source);
}

/** Assert that the package script runs the Playwright matrix config. */
function assertE2eScript(source: string): void {
  if (!source.includes(E2E_SCRIPT)) {
    throw new Error('e2e script does not run the Playwright matrix config');
  }
}

describe('WEB-07 clause 5 — browser matrix in the end-to-end suite', () => {
  it('declaresBrowserMatrixForSupportedEnginesAndViewports', () => {
    expect(() => assertBrowserMatrix(configSource)).not.toThrow();
    expect(() => assertE2eScript(packageSource)).not.toThrow();
    for (const engine of REQUIRED_ENGINES) {
      expect(configSource).toContain(`'${engine}'`);
    }
    expect(configSource).toContain('name: \'tablet-portrait-768x1024\', width: 768, height: 1024');
    expect(configSource).toContain('name: \'laptop-1280x720\', width: 1280, height: 720');
  });

  it('rejectsMatrixMissingSafariEngine', () => {
    const withoutWebKit = configSource.replaceAll("'webkit'", "'safari'");
    expect(() => assertBrowserMatrix(withoutWebKit)).toThrow(
      'browser matrix omits the webkit engine',
    );
  });

  it('rejectsMatrixMissingCompactOrDesktopViewport', () => {
    const withoutCompact = configSource.replace(
      'tablet-portrait-768x1024',
      'tablet-portrait-736x1024',
    );
    expect(() => assertBrowserMatrix(withoutCompact)).toThrow(
      'browser matrix omits tablet-portrait-768x1024 at 768x1024',
    );
    const withoutDesktop = configSource.replace(
      "name: 'laptop-1280x720', width: 1280, height: 720",
      "name: 'laptop-1366x720', width: 1366, height: 720",
    );
    expect(() => assertBrowserMatrix(withoutDesktop)).toThrow(
      'browser matrix omits laptop-1280x720 at 1280x720',
    );
  });

  it('rejectsMatrixNotWiredIntoTheEndToEndSuite', () => {
    const withoutCrossBrowser = configSource.replace('...crossBrowserProjects(),', '');
    expect(() => assertBrowserMatrix(withoutCrossBrowser)).toThrow(
      'browser matrix does not add cross-browser projects to the suite',
    );
    const withoutEngineLoop = configSource.replace(
      "for (const browser of ['webkit', 'firefox'] as const)",
      "for (const browser of ['chromium'] as const)",
    );
    expect(() => assertBrowserMatrix(withoutEngineLoop)).toThrow(
      'browser matrix does not iterate the WebKit and Firefox engines',
    );
    const withoutDeviceProjects = configSource.replace(
      "...deviceViewports.map((device) => projectFor(device, 'chromium')),",
      '',
    );
    expect(() => assertBrowserMatrix(withoutDeviceProjects)).toThrow(
      'browser matrix does not add device viewports to the suite',
    );
    const withoutDesktopCrossBrowser = configSource.replace("  'laptop-1280x720',\n", '');
    expect(() => assertBrowserMatrix(withoutDesktopCrossBrowser)).toThrow(
      'browser matrix does not run the desktop viewport cross-browser',
    );
    const withoutCrossBrowserSet = configSource.replace('new Set([', 'new Map([');
    expect(() => assertBrowserMatrix(withoutCrossBrowserSet)).toThrow(
      'browser matrix does not run the desktop viewport cross-browser',
    );
    const withoutE2eScript = packageSource.replace(E2E_SCRIPT, '"e2e": "echo no-matrix"');
    expect(() => assertE2eScript(withoutE2eScript)).toThrow(
      'e2e script does not run the Playwright matrix config',
    );
  });
});
