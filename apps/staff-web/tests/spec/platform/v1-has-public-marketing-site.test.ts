/**
 * WEB-13 clause 2 — V1 has no public marketing site.
 *
 * Read-only, synthetic evidence that this repository ships no public marketing
 * site: the authenticated staff portal registers no marketing route and serves
 * no marketing file from its public/ tree, apps/ holds only the four known
 * applications, and apps/public-pages/ is the fixed set of read-only status,
 * maintenance, support and legal pages. The portal stays unindexed and closed
 * to crawlers.
 *
 * Absence is the rule, so a positive assertion would pass vacuously if a scan
 * returned nothing. Every scan is guarded with a non-empty or exact-set
 * assertion, and a second test proves that the same matchers refuse synthetic
 * marketing markers. No network, database, patient data or credential is used.
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
// This spec lives in tests/spec/platform, three levels below the staff-web
// root; every helper below resolves from that root, not from this file.
const moduleRoot = resolve(here, '../../..');

/**
 * Read a staff-web-relative file as text.
 *
 * An absent file reads as empty text so a missing rule fails as an assertion
 * about the rule rather than an uncaught filesystem error. Callers that rely on
 * file content must guard against the empty result explicitly.
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
 * @returns sorted, staff-web-relative file paths; empty when the directory is absent
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
 * List the immediate child directory names of a staff-web-relative directory.
 *
 * Used to detect a planted application or static site tree: a marketing tree
 * under apps/ would not be seen by a scan limited to staff-web source files.
 *
 * @param path directory path relative to the staff-web root
 * @returns sorted child directory names; empty when the directory is absent
 */
const listDirectories = (path: string): string[] => {
  const absolute = resolve(moduleRoot, path);
  if (!existsSync(absolute)) {
    return [];
  }
  return readdirSync(absolute, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
};

/**
 * Extract route pattern literals from a route module's source.
 *
 * The same extractor is used on real route tables and on the synthetic marker
 * sources in the refusal test, so both cases exercise one parsing path.
 *
 * @param source route module source text
 * @returns the quoted route patterns found after `pattern:` keys, in either quote style
 */
const extractPatterns = (source: string): string[] =>
  [...source.matchAll(/pattern\s*:\s*(['"])([^'"]+)\1/g)].map((match) => match[2] ?? '');

/** Paths of the per-feature route modules, one directory per business feature. */
const featureRoutePaths = (): string[] =>
  readdirSync(resolve(moduleRoot, 'src/features'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => join('src/features', entry.name, 'routes.ts'))
    .filter((path) => existsSync(resolve(moduleRoot, path)));

/**
 * Exact lowercase path segments that would turn a route or static path into a
 * public marketing destination. Members are segments, not substrings, so a
 * legitimate word inside a file name cannot trip the matcher.
 */
const marketingSegments = new Set([
  'about',
  'blog',
  'careers',
  'demo',
  'feature',
  'features',
  'landing',
  'marketing',
  'news',
  'plan',
  'plans',
  'pricing',
  'product',
  'products',
  'signup',
  'subscribe',
  'testimonials',
]);

/**
 * Refuse a marketing route pattern or a Vite-served static path.
 *
 * Only ever applied to route literals and to `public/` file paths, never to
 * page prose: words such as "about" and "planned" are legitimate in the static
 * information pages. Compound slugs are caught by the second expression.
 *
 * @param value a route pattern or a staff-web-relative static file path
 * @returns true when the value names a public marketing destination
 */
const isMarketingRoute = (value: string): boolean => {
  const segments = value
    .split(/[/?#]/)
    .filter((part) => part.length > 0)
    .map((part) => part.toLowerCase());
  return (
    segments.some((part) => marketingSegments.has(part)) ||
    /pricing|demo|marketing|testimonial|free-trial|get-started/i.test(value)
  );
};

/**
 * Refuse marketing copy inside the static information pages.
 *
 * Phrase-level and deliberately does not include "about", bare "plan" or bare
 * "features": those words occur in legitimate maintenance and privacy prose.
 *
 * @param text page, stylesheet or README text
 * @returns true when promotional marketing copy is present
 */
const hasMarketingCopy = (text: string): boolean =>
  /\b(?:pricing|book a demo|testimonials?|get started|free trial|contact sales|buy now|subscribe now)\b/i.test(
    text,
  );

/** The only applications allowed at the top level of apps/. */
const expectedApps = ['api', 'patient-mobile', 'public-pages', 'staff-web'] as const;

/** The exact read-only public-pages file set, in the helper's sorted order. */
const expectedPublicFiles = [
  '../public-pages/README.md',
  '../public-pages/accessibility.html',
  '../public-pages/maintenance.html',
  '../public-pages/privacy.html',
  '../public-pages/status.html',
  '../public-pages/styles.css',
  '../public-pages/support.html',
  '../public-pages/terms.html',
] as const;

/** The six separately hosted static information pages required by WEB-13. */
const publicPages = [
  'accessibility.html',
  'maintenance.html',
  'privacy.html',
  'status.html',
  'support.html',
  'terms.html',
] as const;

describe('WEB-13 clause 2 — no public marketing site', () => {
  it('provesNoPublicMarketingSiteExists', () => {
    // A planted apps/marketing/ tree must fail: a scan of staff-web alone
    // would stay green while a public site existed in the monorepo.
    expect(listDirectories('../../apps')).toEqual([...expectedApps]);

    // Guard the route scan against an empty glob or a moved features tree.
    const routePaths = [...featureRoutePaths(), 'src/app/routes.tsx'];
    expect(routePaths.length).toBeGreaterThan(0);
    const patterns = routePaths.flatMap((path) => extractPatterns(read(path)));
    expect(patterns.length).toBeGreaterThanOrEqual(25);
    expect(patterns).toContain('/patients');
    expect(patterns.filter(isMarketingRoute), 'no marketing route is registered').toEqual([]);

    // A planted site could be served from the portal's own public/ tree
    // instead of a route. The exact set makes even a non-keyword landing file
    // fail, and the matcher refuses any marketing path in that tree.
    const servedFiles = listFiles('public');
    expect(servedFiles).toEqual(['public/manifest.webmanifest', 'public/robots.txt']);
    expect(servedFiles.filter(isMarketingRoute), 'no marketing file is served').toEqual([]);

    // The separate public presence is exactly the six pages, one stylesheet
    // and one hosting README — no landing page or extra marketing asset.
    expect(listFiles('../public-pages')).toEqual([...expectedPublicFiles]);
    for (const page of publicPages) {
      const html = read(`../public-pages/${page}`);
      expect(html.length, `${page} is not empty`).toBeGreaterThan(0);
      expect(hasMarketingCopy(html), `${page} has no marketing copy`).toBe(false);
    }
    for (const supporting of ['README.md', 'styles.css']) {
      const text = read(`../public-pages/${supporting}`);
      expect(text.length, `${supporting} is not empty`).toBeGreaterThan(0);
      expect(hasMarketingCopy(text), `${supporting} has no marketing copy`).toBe(false);
    }

    // Bounded tripwires for the authenticated portal: noindex and no crawling.
    const head = /<head\b[^>]*>([\s\S]*?)<\/head>/i.exec(read('index.html'))?.[1] ?? '';
    expect(head).toMatch(/<meta name="robots" content="noindex, nofollow" \/>/);
    expect(
      read('public/robots.txt')
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line.length > 0),
    ).toEqual(['User-agent: *', 'Disallow: /']);
  });

  it('refusesAnInjectedPublicMarketingSite', () => {
    // Synthetic route markers pass through the same extractor and matcher as
    // the real route tables, so a refusal proves the positive scan can fail.
    const plantedRoutes = [
      "pattern: '/pricing'",
      "pattern: '/demo'",
      "pattern: '/pricing/plans'",
      "pattern: '/marketing/landing'",
    ];
    for (const source of plantedRoutes) {
      const extracted = extractPatterns(source);
      expect(extracted).toHaveLength(1);
      expect(extracted.some(isMarketingRoute), source).toBe(true);
    }

    // Synthetic page copy passes through the same copy matcher as the real
    // public-pages text. Each marker is tried on its own.
    const plantedCopy = [
      'See our pricing',
      'Book a demo',
      'Get started free',
      'Contact sales',
      'Start your free trial',
      'Testimonials from customers',
    ];
    for (const copy of plantedCopy) {
      expect(hasMarketingCopy(copy), copy).toBe(true);
    }

    // The real information pages and benign prose are not refused. The last
    // two are regression guards for words a broader matcher would trip on.
    for (const page of publicPages) {
      expect(hasMarketingCopy(read(`../public-pages/${page}`)), page).toBe(false);
    }
    const benign = [
      "pattern: '/patients'",
      'Service status',
      'Planned maintenance windows',
      'Questions about privacy',
    ];
    for (const sample of benign) {
      const extracted = extractPatterns(sample);
      if (extracted.length > 0) {
        expect(extracted.some(isMarketingRoute), sample).toBe(false);
      }
      expect(hasMarketingCopy(sample), sample).toBe(false);
    }
  });
});
