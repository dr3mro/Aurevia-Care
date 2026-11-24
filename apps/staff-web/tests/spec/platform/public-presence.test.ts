/**
 * WEB-13 clause 1 — umbrella public-presence boundary of the staff portal.
 *
 * Clause 1 is the title of the requirement; its three concrete sub-rules have
 * their own clause tests (no marketing site, separately hosted public pages,
 * noindex plus robots.txt). This file proves the boundary that holds them
 * together from the authenticated portal's side: the served document and its
 * public assets expose no public-presence surface of their own. It changes no
 * production code and reads only configuration that already ships.
 *
 * Synthetic input only: the refusal case injects strings into pure matchers;
 * no network, database, patient record or credential is involved.
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
 * The <meta> tags inside the document head.
 *
 * Only head tags count: a body-level tag must not satisfy a document-level
 * indexability rule. Mirrors the helper in tests/indexability.test.ts.
 *
 * @param html document text to inspect
 * @returns the raw head meta tags in document order
 */
const headMetas = (html: string): string[] =>
  [...(/<head\b[^>]*>([\s\S]*?)<\/head>/i.exec(html)?.[1] ?? '').matchAll(/<meta\b[^>]*>/gi)].map(
    (match) => match[0],
  );

/**
 * The content attribute of a meta tag, empty when it has none.
 *
 * @param tag raw meta tag, or undefined when no matching tag was found
 * @returns the unquoted content value
 */
const metaContent = (tag: string | undefined): string =>
  tag?.match(/\bcontent=["']([^"']*)["']/i)?.[1] ?? '';

/**
 * The non-empty, trimmed directive lines of a robots.txt body.
 *
 * @param text robots.txt content
 * @returns directive lines without blank lines
 */
const robotsRules = (text: string): string[] =>
  text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

/**
 * Detect an indexing permission in a document head.
 *
 * A bare "index" token is the forbidden signal. The word boundary matters:
 * "noindex" must never be mistaken for an indexing permission.
 *
 * @param html document text to inspect
 * @returns true when a robots or googlebot meta permits indexing
 */
const hasIndexingSignal = (html: string): boolean =>
  headMetas(html).some(
    (tag) =>
      /\bname=["'](?:robots|googlebot)["']/i.test(tag) && /\bindex\b/i.test(metaContent(tag)),
  );

/**
 * Detect a crawl-allowing directive in parsed robots.txt rules.
 *
 * @param rules trimmed robots.txt directive lines
 * @returns true when any Allow rule is present
 */
const allowsCrawling = (rules: string[]): boolean =>
  rules.some((rule) => /^allow\s*:/i.test(rule));

/**
 * Detect a top-level public presence route in a feature route table.
 *
 * Only a route rooted at a presence name counts: a nested authenticated route
 * such as /prescriber/status is a clinical feature, not a public page.
 *
 * @param source route table source text
 * @returns true when a public presence route is declared
 */
const hasPublicRoute = (source: string): boolean =>
  /pattern\s*:\s*['"]\/(?:status|maintenance|support|marketing|public)\b/i.test(source);

/** Names that would mark a file as a public-presence page or a served page. */
const PRESENCE_PAGE_NAME =
  /\b(?:marketing|landing|status|maintenance|support|privacy|accessibility|terms|homepage)\b/i;

/** Synthetic clinical identifiers that must never appear on a presence page. */
const PATIENT_DATA_MARKER = /\b(?:patients?|mrns?|charts?)\b/i;

/**
 * Select paths that look like a public-presence page.
 *
 * A page is either named for a presence surface or is a server-rendered page
 * extension; the staff portal's static public directory should hold neither.
 *
 * @param paths staff-web-relative paths to inspect
 * @returns the paths that look like a presence page
 */
const presencePagePaths = (paths: string[]): string[] =>
  paths.filter(
    (path) => PRESENCE_PAGE_NAME.test(path) || /\.(?:html?|php|aspx?)$/i.test(path),
  );

/**
 * Detect patient-data markers in synthetic or served page content.
 *
 * @param text page content to inspect
 * @returns true when a clinical identifier is present
 */
const containsPatientData = (text: string): boolean => PATIENT_DATA_MARKER.test(text);

describe('WEB-13 clause 1 — public presence boundary of the staff portal', () => {
  it('provesTheStaffPortalExposesNoPublicPresenceSurface', () => {
    const html = read('index.html');

    // Assert the exact single directive: a later conflicting "index, follow"
    // tag must fail rather than be masked by a containment check.
    const robotsMetas = headMetas(html).filter((tag) => /\bname=["']robots["']/i.test(tag));
    expect(robotsMetas).toEqual(['<meta name="robots" content="noindex, nofollow" />']);
    expect(metaContent(robotsMetas[0])).toBe('noindex, nofollow');
    expect(hasIndexingSignal(html)).toBe(false);

    const rules = robotsRules(read('public/robots.txt'));
    expect(rules).toEqual(['User-agent: *', 'Disallow: /']);
    expect(allowsCrawling(rules)).toBe(false);

    // The portal's own public directory carries no presence page and no
    // patient-data content. Presence pages belong to the separate module.
    const publicFiles = listFiles('public');
    expect(publicFiles.length).toBeGreaterThan(0);
    expect(presencePagePaths(publicFiles)).toEqual([]);
    expect(publicFiles.filter((path) => containsPatientData(read(path)))).toEqual([]);
  });

  it('refusesASyntheticPublicIndexingOrPublicPageSignal', () => {
    // Each synthetic signal is tried alone so one cannot mask another. The
    // same matchers used on the real surface must refuse every one of them.
    expect(hasIndexingSignal('<head><meta name="robots" content="index, follow" /></head>')).toBe(
      true,
    );
    expect(hasIndexingSignal('<head><meta name="googlebot" content="index,follow" /></head>')).toBe(
      true,
    );
    // A naive substring check would pass this: the real "noindex" directive
    // contains the letters "index" but is not an indexing permission.
    expect(hasIndexingSignal('<head><meta name="robots" content="noindex" /></head>')).toBe(false);

    expect(allowsCrawling(robotsRules('User-agent: *\nAllow: /\n'))).toBe(true);

    const syntheticPages = ['public/status.html', 'public/support.html', 'public/marketing.html'];
    expect(presencePagePaths(syntheticPages)).toEqual(syntheticPages);
    expect(containsPatientData('<p>Patient MRN 42 appears in the chart</p>')).toBe(true);
    expect(containsPatientData('<p>Service desk opening hours</p>')).toBe(false);

    expect(hasPublicRoute("{ pattern: '/status', component: StatusPage }")).toBe(true);
    // A nested clinical route shares the word but is not a public page.
    expect(hasPublicRoute("{ pattern: '/prescriber/status' }")).toBe(false);
  });

  /**
   * GAP tripwire: presence pages are hosted separately, never inside this
   * authenticated portal. These are active, green absence assertions; the day
   * such a page lands in this module they fail and must be replaced by the
   * positive proof quoted in the clause document. They are not it.skip because
   * AGENTS.md and scripts/check-constraints.py forbid added skipped tests.
   */
  it('GAP: separately hosted status, maintenance and support pages are absent', () => {
    const publicFiles = listFiles('public');
    expect(publicFiles.length).toBeGreaterThan(0);
    expect(presencePagePaths(publicFiles)).toEqual([]);
    expect(publicFiles.filter((path) => /\.(?:html?|php|aspx?)$/i.test(path))).toEqual([]);
    expect(publicFiles.filter((path) => containsPatientData(read(path)))).toEqual([]);

    // A presence page could also arrive as a route rather than a public file.
    const routePaths = listFiles('src').filter((path) => path.endsWith('/routes.ts'));
    expect(routePaths.length).toBeGreaterThan(0);
    const publicRoutes = routePaths.filter((path) => hasPublicRoute(read(path)));
    expect(publicRoutes).toEqual([]);
  });
});
