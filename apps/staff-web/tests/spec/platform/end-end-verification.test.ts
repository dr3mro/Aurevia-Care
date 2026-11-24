import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Static evidence for WEB-10 clause 1 (end-to-end verification).
 *
 * The Playwright journeys need a real browser, which this jsdom worker does not
 * have. These tests read the harness sources and assert the concrete
 * configuration the journeys rely on; they do not claim a browser run, a staged
 * deployment or a report retained from a release candidate.
 */

const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..', '..', '..');
const e2eRoot = resolve(moduleRoot, 'e2e');

/**
 * Read an evidence file as UTF-8 text.
 *
 * An absent file reads as empty text so a missing rule fails as an assertion
 * about the harness rather than as an uncaught filesystem error.
 */
const readEvidence = (relativePath: string): string => {
  const absolute = resolve(moduleRoot, relativePath);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
};

/** Journey spec paths under `e2e/`, one per supported role or workflow. */
const journeySpecs = (): string[] =>
  readdirSync(e2eRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .flatMap((directory) =>
      readdirSync(resolve(e2eRoot, directory.name))
        .filter((file) => file.endsWith('-journey.spec.ts'))
        .map((file) => `${directory.name}/${file}`),
    )
    .sort();

/** Harness source files that mount the real screens under the app providers. */
const harnessSources = (): string[] =>
  readdirSync(e2eRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .flatMap((directory) =>
      readdirSync(resolve(e2eRoot, directory.name))
        .filter((file) => file.endsWith('-harness.tsx'))
        .map((file) => readEvidence(`e2e/${directory.name}/${file}`)),
    );

describe('WEB-10 clause 1 — end-to-end verification evidence', () => {
  it('assertsEndToEndEvidenceHarnessIsConfigured', () => {
    // One injectable base URL lets the same suite target a staged deployment;
    // the local dev server is started only when no target has been supplied.
    const config = readEvidence('e2e/playwright.config.ts');
    expect(config).toContain('process.env.E2E_BASE_URL');
    expect(config).toContain("isSet('E2E_BASE_URL')");

    // At least one journey exercises both locales, including the RTL mirror.
    const journey = readEvidence('e2e/reception/reception-journey.spec.ts');
    expect(journey).toMatch(/locale:\s*'en'/);
    expect(journey).toMatch(/direction:\s*'ltr'/);
    expect(journey).toMatch(/locale:\s*'ar'/);
    expect(journey).toMatch(/direction:\s*'rtl'/);

    // The shared gate is the one place that defines a blocking violation.
    const axeGate = readEvidence('e2e/accessibility.ts');
    expect(axeGate).toContain('export async function expectNoBlockingViolations');
    expect(axeGate).toContain("violation.impact === 'serious' || violation.impact === 'critical'");
    expect(axeGate).toContain('toEqual([])');

    // Every harness mounts the real screens under ThemeProvider, which owns
    // the light/dark mode a visual comparison would exercise.
    const harnesses = harnessSources();
    expect(harnesses.length).toBeGreaterThan(0);
    for (const harness of harnesses) {
      expect(harness, 'e2e harness must mount ThemeProvider').toContain('ThemeProvider');
    }
    const themeProvider = readEvidence('src/theme/ThemeProvider.tsx');
    expect(themeProvider).toContain("useState<ThemeMode>('light')");
    expect(themeProvider).toContain("current === 'light' ? 'dark' : 'light'");
    expect(themeProvider).toContain('document.documentElement.dataset.theme = mode');

    // Each supported role or workflow has its own Playwright journey spec.
    const journeys = journeySpecs();
    const directories = readdirSync(e2eRoot, { withFileTypes: true }).filter((entry) =>
      entry.isDirectory(),
    );
    expect(directories.length).toBeGreaterThanOrEqual(6);
    for (const directory of directories) {
      expect(
        journeys.some((path) => path.startsWith(`${directory.name}/`)),
        `no journey spec for ${directory.name}`,
      ).toBe(true);
    }
  });

  it('refusesSeriousAndCriticalAccessibilityViolations', () => {
    // The negative case for this clause: the gate must reject exactly the two
    // impacts that can block a clinical task. Moderate and minor findings stay
    // in the full review, so widening the filter would fail this assertion.
    for (const path of ['e2e/accessibility.ts', 'e2e/smoke.spec.ts']) {
      const gate = readEvidence(path);
      expect(gate).toContain("violation.impact === 'serious' || violation.impact === 'critical'");
      expect(gate).not.toContain("violation.impact === 'moderate'");
      expect(gate).not.toContain("violation.impact === 'minor'");
      expect(gate).toContain('toEqual([])');
    }
  });
});
