/**
 * WEB-08 clause 1 — "Front-end performance."
 *
 * Synthetic evidence for the halves of the clause the staff web enforces in
 * this checkout: the CI gzip chunk budget, per-feature route code splitting and
 * the absence of third-party scripts. Field p75 metrics, self-hosted Arabic
 * fonts and immutable hashed-asset headers are recorded as active GAP tripwires
 * (skipped tests are forbidden by the repository's constraint gate).
 *
 * No network, database, patient data or production source is involved. The
 * build is provisioned locally when dist/ is absent so the spec also passes the
 * plain `npm test` path used by CI, where dist/ is gitignored.
 */
import { Buffer } from 'node:buffer';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..', '..', '..');
const repoRoot = resolve(moduleRoot, '..', '..');

/** The WEB-08 budget mirrored from scripts/check-bundle-size.mjs, in decimal bytes. */
const MAX_CHUNK_GZIP_BYTES = 256_000;

/** True when a path exists under the staff-web module root. */
function modulePathExists(relativePath: string): boolean {
  return existsSync(resolve(moduleRoot, relativePath));
}

/** Read a UTF-8 file under the module root; empty text when absent. */
function readModuleText(relativePath: string): string {
  const absolute = resolve(moduleRoot, relativePath);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
}

/** Read a UTF-8 file under the repository root; empty text when absent. */
function readRepoText(relativePath: string): string {
  const absolute = resolve(repoRoot, relativePath);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
}

/**
 * Refuse a built chunk whose gzipped size exceeds the WEB-08 budget.
 *
 * This mirrors the production gate without importing the untyped .mjs module.
 * The label and requirement ID appear in the failure so an over-budget chunk is
 * attributable in CI output.
 */
function requireWithinGzipBudget(contents: Uint8Array, label: string): number {
  const size = gzipSync(contents).byteLength;
  if (size > MAX_CHUNK_GZIP_BYTES) {
    throw new Error(`${label}: ${size} bytes gzipped exceeds the budget (WEB-08)`);
  }
  return size;
}

/** Human-readable violations for external scripts or tracker markers in HTML. */
function findThirdPartyScripts(html: string): string[] {
  const violations: string[] = [];
  for (const match of html.matchAll(/<script\b[^>]*>/gi)) {
    const src = /\bsrc\s*=\s*["']([^"']+)["']/i.exec(match[0])?.[1];
    if (src !== undefined && /^(?:https?:)?\/\//i.test(src)) {
      violations.push(`external script src: ${src}`);
    }
  }
  const lowered = html.toLowerCase();
  for (const marker of ['analytics', 'googletagmanager', 'google-analytics', 'gtag', 'hotjar']) {
    if (lowered.includes(marker)) {
      violations.push(`tracker marker: ${marker}`);
    }
  }
  return violations;
}

/** List emitted JavaScript chunks, building once when dist/ has none. */
function builtChunkNames(): string[] {
  const assetsDir = resolve(moduleRoot, 'dist', 'assets');
  const names = (): string[] =>
    existsSync(assetsDir)
      ? readdirSync(assetsDir).filter((name) => name.endsWith('.js')).sort()
      : [];
  if (names().length === 0) {
    execFileSync('npm', ['run', 'build'], { cwd: moduleRoot, stdio: 'pipe' });
  }
  return names();
}

describe('WEB-08 clause 1 — front-end performance', () => {
  it('enforcesTheGzipBudgetOnRealBuiltChunks', () => {
    // Tie the mirrored constant to the CI gate so drift fails this proof.
    const gate = readModuleText('scripts/check-bundle-size.mjs');
    const declared = /export const MAX_CHUNK_GZIP_BYTES\s*=\s*([\d_]+)\s*;/.exec(gate)?.[1] ?? '';
    expect(declared.replaceAll('_', '')).toBe(String(MAX_CHUNK_GZIP_BYTES));

    const names = builtChunkNames();
    expect(names.length, 'the staff web build must emit JavaScript chunks').toBeGreaterThan(0);

    const assetsDir = resolve(moduleRoot, 'dist', 'assets');
    for (const name of names) {
      const size = requireWithinGzipBudget(readFileSync(join(assetsDir, name)), name);
      expect(size, `${name} must stay within the WEB-08 gzip budget`).toBeLessThanOrEqual(
        MAX_CHUNK_GZIP_BYTES,
      );
    }
  }, 120_000);

  it('refusesAnOverBudgetPayload', () => {
    // Incompressible synthetic payload: gzip cannot shrink it under the budget.
    const oversized = randomBytes(300 * 1024);
    expect(gzipSync(oversized).byteLength).toBeGreaterThan(MAX_CHUNK_GZIP_BYTES);
    expect(() => requireWithinGzipBudget(oversized, 'oversized.js')).toThrow(/oversized\.js/);
    expect(() => requireWithinGzipBudget(oversized, 'oversized.js')).toThrow(/WEB-08/);

    const compressible = Buffer.alloc(10 * 1024, 0x41);
    expect(requireWithinGzipBudget(compressible, 'small.js')).toBeLessThanOrEqual(
      MAX_CHUNK_GZIP_BYTES,
    );
  });

  it('splitsFeatureRoutesIntoLazyChunks', () => {
    const lazyPage = /\blazy\s*\(\s*(?:async\s*)?\(\s*\)\s*=>\s*import\s*\(/;
    const appRoutes = readModuleText('src/app/routes.tsx');
    expect(appRoutes).toMatch(/\bimport\s*\{\s*lazy\s*\}\s*from\s*'react'/);
    expect(lazyPage.test(appRoutes)).toBe(true);

    const featuresDir = resolve(moduleRoot, 'src', 'features');
    const featureRoutes = readdirSync(featuresDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => join(featuresDir, entry.name, 'routes.ts'))
      .filter((path) => existsSync(path))
      .sort();
    expect(featureRoutes.length).toBeGreaterThanOrEqual(10);

    for (const routeFile of featureRoutes) {
      const source = readFileSync(routeFile, 'utf8');
      const routeCount = [...source.matchAll(/\bpattern\s*:/g)].length;
      if (routeCount === 0) {
        // A feature with no screens (print mounts its buttons on other pages)
        // owns no chunk; its registry entry is deliberately empty.
        expect(source).toMatch(/export const routes:\s*AppRoute\[\]\s*=\s*\[\s*\]/);
        continue;
      }
      expect(lazyPage.test(source), `${routeFile} must lazy-load its pages`).toBe(true);
      // A module-scope page import would pull the page into the initial chunk.
      expect(source).not.toMatch(/^\s*import\s+\w*Page\s+from\b/m);
    }

    // The built output is the durable proof: many chunks, not one eager bundle.
    expect(builtChunkNames().length).toBeGreaterThan(10);
  }, 120_000);

  it('loadsNoThirdPartyScriptsOrTrackers', () => {
    // Ensure the shipped artifacts exist even when this test runs on its own.
    builtChunkNames();
    const html = readModuleText('index.html');
    const scripts = [...html.matchAll(/<script\b[^>]*>/gi)].map((match) => match[0]);
    expect(scripts).toHaveLength(1);
    const script = scripts[0] ?? '';
    expect(script).toMatch(/\btype\s*=\s*["']module["']/i);
    expect(script).toMatch(/\bsrc\s*=\s*["']\/src\/main\.tsx["']/i);
    expect(findThirdPartyScripts(html)).toEqual([]);

    // The shipped document carries the same single, local module script.
    const shipped = readModuleText('dist/index.html');
    expect(shipped.length).toBeGreaterThan(0);
    const shippedScripts = [...shipped.matchAll(/<script\b[^>]*>/gi)].map((match) => match[0]);
    expect(shippedScripts).toHaveLength(1);
    expect(shippedScripts[0] ?? '').toMatch(/\bsrc\s*=\s*["']\/assets\//i);
    expect(findThirdPartyScripts(shipped)).toEqual([]);

    // Negative: the detector refuses a synthetic external tracker in HTML.
    const trackerHtml =
      '<!doctype html><html><head>' +
      '<script async src="https://tracker.example.invalid/analytics.js"></script>' +
      '</head><body><div id="root"></div></body></html>';
    const violations = findThirdPartyScripts(trackerHtml);
    expect(violations.length).toBeGreaterThan(0);
    expect(violations).toContain(
      'external script src: https://tracker.example.invalid/analytics.js',
    );
    expect(violations).toContain('tracker marker: analytics');
  }, 120_000);

  /**
   * GAP tripwire: WEB-08 requires p75 LCP <= 2.5 s, INP <= 200 ms and CLS <= 0.1
   * measured in the field. No web-vitals collector is wired into the staff-web
   * entry point today. The enabling task adds a typed collector module, wires it
   * into src/main.tsx, records the three p75 values and asserts the thresholds
   * in CI, then replaces this absence proof with a positive measurement test.
   */
  it('GAP: field p75 LCP, INP and CLS are not measured', () => {
    const entry = readModuleText('src/main.tsx');
    expect(entry.length).toBeGreaterThan(0);
    expect(entry).not.toMatch(/\bweb-vitals\b|\bonLCP\b|\bonINP\b|\bonCLS\b|\breportWebVitals\b/i);
    expect(modulePathExists('src/performance/webVitals.ts')).toBe(false);

    // The normative thresholds are documented, not asserted in CI yet.
    const checklist = readRepoText('docs/reference/performance-checklist.md');
    expect(checklist).toMatch(/Largest Contentful Paint \(web\)/);
    expect(checklist).toMatch(/Interaction to Next Paint \(web\)/);
    expect(checklist).toMatch(/Cumulative Layout Shift \(web\)/);
  });

  /**
   * GAP tripwire: WEB-08 requires self-hosted fonts with Arabic coverage. The
   * document loads no font provider and the module ships no font files. The
   * enabling task adds Arabic-covering WOFF2 files under public/fonts, a
   * self-hosted @font-face declaration and a glyph-coverage test, then replaces
   * this absence assertion with the positive font proof.
   */
  it('GAP: fonts are not self-hosted with Arabic coverage', () => {
    const html = readModuleText('index.html');
    expect(html.length).toBeGreaterThan(0);
    expect(html).not.toMatch(/fonts\.googleapis\.com|fonts\.gstatic\.com/i);
    expect(html).not.toMatch(/@font-face/i);
    expect(modulePathExists('public/fonts')).toBe(false);
    expect(modulePathExists('src/assets/fonts')).toBe(false);
  });

  /**
   * GAP tripwire: WEB-08 requires hashed assets served with an immutable cache
   * policy. No serving configuration declares it. The enabling task adds the
   * header rule to the owning delivery layer, proves it in an HTTP test and
   * replaces this assertion with the positive header proof.
   */
  it('GAP: hashed assets are not served as immutable', () => {
    expect(modulePathExists('public/_headers')).toBe(false);
    expect(readModuleText('vite.config.ts')).not.toMatch(/immutable/i);
    // The container nginx config serves /opt/aurevia/web, so it is the owning
    // layer available in this repository; a production CDN may own the real rule.
    const servingConfig = readRepoText('infra/app/nginx.conf');
    expect(servingConfig.length).toBeGreaterThan(0);
    expect(servingConfig).not.toMatch(/immutable|expires\s+\d|Cache-Control/i);
    // The evidence register records the residual gap in prose, not in a test.
    const evidence = readRepoText('docs/operations/production-readiness/evidence.md');
    expect(evidence).toMatch(/WEB-08/);
    expect(evidence).toMatch(/250 KB gzip chunk budget/);
  });
});
