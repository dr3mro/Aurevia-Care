import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const publicRoot = resolve(here, '..', '..', 'public-pages');

const pages = [
  'status.html',
  'maintenance.html',
  'support.html',
  'privacy.html',
  'accessibility.html',
  'terms.html',
] as const;

const legalPages = ['privacy.html', 'accessibility.html', 'terms.html'] as const;

const pathTo = (name: string): string => resolve(publicRoot, name);
const read = (name: string): string =>
  existsSync(pathTo(name)) ? readFileSync(pathTo(name), 'utf8') : '';

describe('public pages package (WEB-13)', () => {
  it('provides all six required pages, one stylesheet and a hosting README', () => {
    for (const page of pages) {
      expect(existsSync(pathTo(page)), `${page} exists`).toBe(true);
    }
    expect(existsSync(pathTo('styles.css')), 'styles.css exists').toBe(true);
    expect(existsSync(pathTo('README.md')), 'README.md exists').toBe(true);
    expect(read('README.md')).toMatch(/host/i);
  });

  it('keeps the shared stylesheet free of external imports and urls', () => {
    const css = read('styles.css');
    expect(css).not.toMatch(/@import\b/i);
    expect(css).not.toMatch(/url\s*\(/i);
    // A hardcoded full-bleed offset once overflowed the 320 px viewport.
    expect(css, 'one gutter variable').toContain('--gutter: 1rem;');
    expect(css, 'no hardcoded gutter offset').not.toContain('-1rem');
  });
});

describe.each(pages)('public page %s (WEB-13)', (page) => {
  const html = read(page);

  it('links to the privacy notice, accessibility statement and terms', () => {
    for (const target of legalPages) {
      expect(html, `${page} links to ${target}`).toContain(`href="${target}"`);
    }
  });

  it('uses the shared stylesheet only', () => {
    expect(html, `${page} links styles.css`).toContain('href="styles.css"');
  });

  it('loads no external resource and contains no script tag', () => {
    expect(html, `${page} has no script`).not.toMatch(/<script\b/i);
    expect(html, `${page} has no absolute src`).not.toMatch(
      /\bsrc\s*=\s*["'](?:[a-z][a-z\d+.-]*:|\/\/)/i,
    );
    expect(html, `${page} has no external link tag`).not.toMatch(
      /<link\b[^>]*\bhref\s*=\s*["'](?:[a-z][a-z\d+.-]*:|\/\/)/i,
    );
  });

  it('mentions no patient, MRN or chart data', () => {
    expect(html, `${page} has no clinical identifiers`).not.toMatch(
      /\bpatients?\b|\bmrn\b|\bcharts?\b/i,
    );
  });

  it('is accessible: language, direction, one h1, skip link and viewport', () => {
    expect(html, `${page} sets lang`).toMatch(/<html\b[^>]*\blang=["'][a-z-]+["'][^>]*>/i);
    expect(html, `${page} sets dir`).toMatch(/<html\b[^>]*\bdir=["'](?:ltr|rtl)["'][^>]*>/i);
    expect(html.match(/<h1\b[^>]*>/gi) ?? [], `${page} has one h1`).toHaveLength(1);
    expect(html, `${page} has a skip link`).toContain('href="#main"');
    expect(html, `${page} has a main target`).toContain('id="main"');
    expect(html, `${page} main is a focusable skip target`).toContain(
      '<main id="main" tabindex="-1">',
    );
    expect(html, `${page} sets viewport`).toMatch(/<meta\b[^>]*\bname=["']viewport["'][^>]*>/i);
  });

  it('uses only invalid-domain placeholders for contact addresses', () => {
    const addresses = [...html.matchAll(/mailto:([^"'\s>]+)/gi)].map((match) => match[1] ?? '');
    for (const address of addresses) {
      expect(address, `${page} placeholder ${address}`).toMatch(/\.invalid$/i);
    }
  });
});
