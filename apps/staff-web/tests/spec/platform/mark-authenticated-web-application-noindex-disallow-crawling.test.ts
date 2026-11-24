/**
 * WEB-13 clause 4 — mark every authenticated web application noindex and
 * disallow crawling in robots.txt.
 *
 * Read-only, synthetic evidence that the authenticated staff portal is closed
 * to search engines: its document head carries exactly one `noindex, nofollow`
 * robots directive and its served robots.txt disallows every user agent from
 * every path. The proof is an active tripwire, not a scan for absence, so a
 * later conflicting tag or an Allow rule fails the positive test.
 *
 * The second test drives the same matchers with synthetic strings to prove the
 * tripwire can fail: indexing permissions and an Allow rule are refused, while
 * a body-level tag and the word-boundary case of the real `noindex` directive
 * are not mistaken for violations. No network, database, patient record or
 * credential is used.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
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
 * file content guard the empty result explicitly.
 *
 * @param path path relative to the staff-web root
 * @returns the file contents, or the empty string when the file is absent
 */
const read = (path: string): string => {
  const absolute = resolve(moduleRoot, path);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
};

/**
 * The raw meta tags inside the document head, in source order.
 *
 * Only head tags count: a body-level tag must not satisfy a document-level
 * indexability rule. Mirrors the helper in tests/indexability.test.ts.
 *
 * @param html document text to inspect
 * @returns the raw head meta tags
 */
const headMetas = (html: string): string[] =>
  [...(/<head\b[^>]*>([\s\S]*?)<\/head>/i.exec(html)?.[1] ?? '').matchAll(/<meta\b[^>]*>/gi)].map(
    (match) => match[0],
  );

/**
 * The unquoted content attribute of a meta tag, empty when it has none.
 *
 * @param tag raw meta tag, or undefined when no matching tag was found
 * @returns the content attribute value
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
 * The robots meta tags declared inside the document head.
 *
 * @param html document text to inspect
 * @returns head meta tags whose name is `robots`
 */
const robotsMetasIn = (html: string): string[] =>
  headMetas(html).filter((tag) => /\bname=["']robots["']/i.test(tag));

/**
 * Detect an indexing permission in a document head.
 *
 * A bare, word-bounded `index` token is the forbidden signal: `noindex` is not
 * an indexing permission. Only head robots and googlebot metas are inspected.
 *
 * @param html document text to inspect
 * @returns true when a head meta permits indexing
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

describe('WEB-13 clause 4 — noindex and disallow all crawling', () => {
  it('marksAuthenticatedPortalNoindexInHeadAndDisallowsAllCrawling', () => {
    const html = read('index.html');
    // Guard against a vacuous pass if the document or its head moved away.
    expect(html.length).toBeGreaterThan(0);
    expect(headMetas(html).length).toBeGreaterThan(0);

    // Exactly one head robots directive: a later conflicting `index, follow`
    // meta must fail rather than be masked by a containment check.
    const robotsMetas = robotsMetasIn(html);
    expect(robotsMetas).toEqual(['<meta name="robots" content="noindex, nofollow" />']);
    expect(metaContent(robotsMetas[0])).toBe('noindex, nofollow');
    expect(hasIndexingSignal(html)).toBe(false);

    const robotsText = read('public/robots.txt');
    expect(robotsText.length).toBeGreaterThan(0);
    const rules = robotsRules(robotsText);
    expect(rules).toEqual(['User-agent: *', 'Disallow: /']);
    expect(allowsCrawling(rules)).toBe(false);
  });

  it('refusesSyntheticIndexingAndCrawlAllowingSignals', () => {
    // Every synthetic signal is tried alone through the same matchers used on
    // the real document, so one cannot mask another and the positive test is
    // proven capable of failing.
    expect(hasIndexingSignal('<head><meta name="robots" content="index, follow" /></head>')).toBe(
      true,
    );
    expect(hasIndexingSignal('<head><meta name="googlebot" content="index,follow" /></head>')).toBe(
      true,
    );
    expect(allowsCrawling(robotsRules('User-agent: *\nAllow: /\n'))).toBe(true);

    // A body-level tag is not a document-level rule: the head keeps its real
    // noindex directive even though the body carries an indexing permission.
    expect(
      hasIndexingSignal(
        '<head><meta name="robots" content="noindex, nofollow" /></head>' +
          '<body><meta name="robots" content="index, follow" /></body>',
      ),
    ).toBe(false);

    // Word-boundary guard: the real `noindex` contains the letters "index" but
    // is not a permission, and a genuine disallow-all body is not an Allow rule.
    expect(hasIndexingSignal('<head><meta name="robots" content="noindex" /></head>')).toBe(false);
    expect(allowsCrawling(robotsRules('User-agent: *\nDisallow: /\n'))).toBe(false);
  });
});
