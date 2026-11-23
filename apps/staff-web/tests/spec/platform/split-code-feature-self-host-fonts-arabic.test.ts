/**
 * WEB-08 clause 4 — production web platform delivery.
 *
 * Evidence for the two halves of the clause that the staff web already builds:
 * code is split per feature, hashed assets are left to Vite's default naming,
 * and no third-party script or tracker is loaded. The other two halves —
 * self-hosted fonts with Arabic coverage, and immutable caching of hashed
 * assets — are absent from this snapshot. They are recorded here as ACTIVE
 * absence tripwires and as complete, quoted tests in the clause document,
 * because AGENTS.md and scripts/check-constraints.py forbid added skipped
 * tests in TypeScript.
 *
 * Synthetic input only. No network, database, patient data or credential is
 * involved; this file reads build and source configuration from disk.
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
// This spec lives in tests/spec/platform, three levels below the staff-web
// root; the read() helper below is relative to that root, not to this file.
const moduleRoot = resolve(here, '../../..');

/**
 * Read a staff-web-relative file as text.
 *
 * An absent file reads as empty text so a missing rule fails as an assertion
 * about the rule rather than an uncaught filesystem error.
 *
 * @param path path relative to the staff-web root
 * @returns the file contents, or the empty string when the file is absent
 */
const read = (path: string): string => {
  const absolute = resolve(moduleRoot, path);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
};

/**
 * List every regular file below a staff-web-relative directory.
 *
 * @param path directory path relative to the staff-web root
 * @returns sorted, relative paths; empty when the directory is absent
 */
const listFiles = (path: string): string[] => {
  const absolute = resolve(moduleRoot, path);
  if (!existsSync(absolute)) {
    return [];
  }
  return readdirSync(absolute, { withFileTypes: true })
    .flatMap((entry) => {
      const child = join(path, entry.name);
      if (entry.isDirectory()) {
        return listFiles(child);
      }
      return entry.isFile() ? [child] : [];
    })
    .sort();
};

/**
 * The forbidden third-party script matcher shared by the positive and negative
 * cases. It refuses both a remote script source and an analytics/tracker
 * provider name, mirroring the document-level check in tests/indexability.test.ts.
 *
 * @param html document text to inspect
 * @returns true when a remote script or a tracker is present
 */
const hasForbiddenScript = (html: string): boolean =>
  /<script[^>]+src=["']https?:\/\//i.test(html) ||
  /analytics|googletagmanager|google-analytics/i.test(html);

/** Paths of the per-feature route modules, one directory per business feature. */
const featureRoutePaths = (): string[] =>
  readdirSync(resolve(moduleRoot, 'src/features'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => join('src/features', entry.name, 'routes.ts'))
    .filter((path) => existsSync(resolve(moduleRoot, path)));

describe('WEB-08 clause 4 — split code, self-host fonts, hashed assets, no trackers', () => {
  it('provesPerFeatureCodeSplittingHashedAssetsAndNoThirdPartyScripts', () => {
    // Features own their route tables and lazy-load their screens, so a
    // visitor downloads a chunk only for the feature they actually open. The
    // one screen-less feature is handled explicitly below.
    const routePaths = featureRoutePaths();
    expect(routePaths.length).toBeGreaterThan(0);
    for (const path of routePaths) {
      const source = read(path);
      // A feature with no screens of its own (print mounts its buttons on
      // other features' pages) still joins the glob with an empty table. It
      // must not claim a route pattern without a lazy screen to back it.
      const ownsNoScreens = /export\s+const\s+routes\s*:\s*AppRoute\[\]\s*=\s*\[\s*\]\s*;/.test(
        source,
      );
      if (ownsNoScreens) {
        expect(source, `${path} must not claim a pattern without a screen`).not.toMatch(
          /pattern\s*:/,
        );
        continue;
      }
      expect(source, `${path} must register a lazy screen`).toMatch(/\blazy\s*\(/);
      expect(source, `${path} must use a dynamic import`).toMatch(/\bimport\s*\(/);
    }
    const registry = read('src/app/routes.tsx');
    expect(registry).toMatch(/import\.meta\.glob/);
    expect(registry).toMatch(/\blazy\s*\(/);

    // The production document loads only the local entry module.
    expect(hasForbiddenScript(read('index.html'))).toBe(false);

    // With no rollup filename override, Vite's defaults emit content-hashed
    // names in dist/assets; check-bundle-size.mjs measures that directory after
    // build. This proves the naming half only: serving those hashed assets as
    // immutable is the recorded gap, not a claim this rule is fully built.
    // The built directory is deliberately not read here: it does not exist
    // when this suite runs on its own.
    const viteConfig = read('vite.config.ts');
    expect(viteConfig).toMatch(/defineConfig/);
    expect(viteConfig).not.toMatch(/entryFileNames|chunkFileNames|assetFileNames|rollupOptions/);
  });

  it('refusesAnInjectedThirdPartyTrackerScript', () => {
    // Synthetic forbidden signals, each tried alone so one cannot mask another.
    // The matcher used on the real index.html must refuse every one of them.
    const forbiddenMarkers = [
      '<script src="https://tracker.example.test/collect.js"></script>',
      'googletagmanager',
      'google-analytics',
      'analytics',
    ];
    for (const marker of forbiddenMarkers) {
      const injectedHtml = `<html><head>${marker}</head><body></body></html>`;
      expect(hasForbiddenScript(injectedHtml), marker).toBe(true);
    }
  });

  /**
   * GAP tripwire: no self-hosted font asset, @font-face rule or Arabic
   * unicode-range is present yet. The assertions pin today's absence with real
   * outcomes. The day a font task adds any of them, this case fails and must be
   * replaced by the positive proof quoted in the clause document.
   */
  it('GAP: self-hosted Arabic-coverage fonts are absent', () => {
    const candidates = [...listFiles('src'), ...listFiles('public')];
    const fontFiles = candidates.filter((path) => /\.(?:woff2?|ttf|otf)$/i.test(path));
    expect(fontFiles).toEqual([]);

    const styledSources = candidates
      .filter((path) => /\.(?:css|ts|tsx|html)$/i.test(path))
      .map((path) => read(path));
    expect(styledSources.filter((source) => /@font-face\b/i.test(source))).toEqual([]);
    // The Arabic block starts at U+0600; no unicode-range requests it today.
    expect(styledSources.some((source) => /unicode-range\s*:[^;}]*U\+06/i.test(source))).toBe(
      false,
    );
  });

  /**
   * GAP tripwire: no deployment configuration sets an immutable cache policy.
   * The assertions fail when a future task adds Cache-Control: immutable for
   * hashed assets, at which point this case must become the positive proof.
   */
  it('GAP: immutable cache headers for hashed assets are absent', () => {
    // moduleRoot is apps/staff-web, so the repository's infra/ is two levels up.
    const configPaths = ['vite.config.ts', ...listFiles('../../infra')];
    // Guard the scan itself: an empty list would keep this tripwire green for
    // the wrong reason if the deployment tree ever moved or was missed.
    expect(configPaths.length).toBeGreaterThan(1);
    const withImmutableCache = configPaths.filter((path) =>
      /cache-control[^\n]*immutable/i.test(read(path)),
    );
    expect(withImmutableCache).toEqual([]);
  });
});
