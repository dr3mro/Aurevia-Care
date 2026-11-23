import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..', '..');
const stylesheetPath = resolve(moduleRoot, 'src/theme/responsive.css');

// An absent stylesheet reads as empty text so every rule fails as an assertion
// about the stylesheet rather than as an uncaught filesystem error.
const readStylesheet = (): string =>
  existsSync(stylesheetPath) ? readFileSync(stylesheetPath, 'utf8') : '';

const withoutComments = (css: string): string => css.replace(/\/\*[\s\S]*?\*\//g, '');

const escapeRegExp = (value: string): string => value.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&');

const hasClass = (css: string, name: string): boolean =>
  new RegExp(`\\.${escapeRegExp(name)}(?![\\w-])`).test(css);

/** Rule body of a top-level selector, for example `.grid-2 { ... }`. */
const ruleBody = (css: string, selector: string): string =>
  new RegExp(`(?:^|\\n)\\s*${escapeRegExp(selector)}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '';

/** Map each `@media` feature list to its body, using brace depth to find the end. */
const mediaBlocks = (css: string): Map<string, string> => {
  const blocks = new Map<string, string>();
  for (const match of css.matchAll(/@media\s+([^{]+)\{/g)) {
    const query = (match[1] ?? '').trim();
    const start = match.index + match[0].length;
    let depth = 1;
    let end = start;
    while (end < css.length && depth > 0) {
      const character = css[end];
      if (character === '{') {
        depth += 1;
      } else if (character === '}') {
        depth -= 1;
      }
      end += 1;
    }
    blocks.set(query, css.slice(start, end - 1));
  }
  return blocks;
};

/** Extract every `var(--sc-name, fallback)` pair, tolerating nested parens. */
const tokenFallbacks = (css: string): Map<string, string> => {
  const pairs = new Map<string, string>();
  for (const match of css.matchAll(/var\(--sc-/g)) {
    const start = match.index + 'var('.length;
    let depth = 1;
    let end = start;
    while (end < css.length && depth > 0) {
      const character = css[end];
      if (character === '(') {
        depth += 1;
      } else if (character === ')') {
        depth -= 1;
      }
      end += 1;
    }
    const body = css.slice(start, end - 1).trim();
    const comma = body.indexOf(',');
    expect(comma, `token variable without a fallback: ${body}`).toBeGreaterThan(-1);
    pairs.set(body.slice(0, comma).trim(), body.slice(comma + 1).trim());
  }
  return pairs;
};

const LAYOUT_CLASSES = [
  'page',
  'stack',
  'cluster',
  'grid-2',
  'table-region',
  'card-list',
  'only-compact',
  'hide-compact',
  'touch',
  'field',
  'sheet',
  'app-shell',
  'skip-link',
] as const;

describe('WEB-07 / UX-01 responsive base stylesheet', () => {
  it('defines every documented layout utility class', () => {
    const css = readStylesheet();
    for (const name of LAYOUT_CLASSES) {
      expect(hasClass(css, name), `missing .${name} in responsive.css`).toBe(true);
    }
  });

  it('uses only logical properties, including in the skip-link rule', () => {
    const css = withoutComments(readStylesheet());
    const physicalPatterns = [
      /(?<![-\w])(?:margin|padding)-(?:left|right|top|bottom)\s*:/g,
      /(?<![-\w])(?:left|right|top|bottom)\s*:/g,
      /(?<![-\w])float\s*:/g,
      /text-align\s*:\s*(?:left|right)\b/g,
      /translateX\s*\(/g,
      /border-(?:left|right|top|bottom)\b/g,
      /border-(?:top|bottom)-(?:left|right)-radius\b/g,
    ];
    expect(css.length).toBeGreaterThan(0);
    for (const pattern of physicalPatterns) {
      expect(css, `physical property matched by ${pattern}`).not.toMatch(pattern);
    }
  });

  it('declares only the 768 and 1280 layout widths', () => {
    const css = withoutComments(readStylesheet());
    const queries = [...css.matchAll(/@media\s+([^{]+)\{/g)].map((match) =>
      (match[1] ?? '').trim(),
    );
    expect(queries.length).toBeGreaterThan(0);
    const widths: string[] = [];
    for (const query of queries) {
      for (const value of query.match(/\b\d+px\b/g) ?? []) {
        expect(['768px', '1280px']).toContain(value);
      }
      for (const match of query.matchAll(/\b(?:min|max)-width\s*:\s*(\d+)px\b/g)) {
        widths.push(match[1] ?? '');
      }
    }
    expect(widths.length).toBeGreaterThan(0);
    expect(new Set(widths)).toEqual(new Set(['768', '1280']));
  });

  it('keeps the compact, medium, expanded, coarse-pointer and reduced-motion queries', () => {
    const blocks = mediaBlocks(withoutComments(readStylesheet()));
    for (const query of [
      '(min-width: 768px)',
      '(min-width: 1280px)',
      '(pointer: coarse)',
      '(prefers-reduced-motion: reduce)',
    ]) {
      expect(blocks.has(query), `missing @media ${query}`).toBe(true);
    }
  });

  it('collapses .grid-2 to one column and grows to two at 768px', () => {
    const css = withoutComments(readStylesheet());
    const medium = mediaBlocks(css).get('(min-width: 768px)') ?? '';
    expect(ruleBody(css, '.grid-2')).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)/);
    expect(medium).toMatch(
      /\.grid-2\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/,
    );
  });

  it('moves the shell from a compact drawer to an icon rail to a labelled rail', () => {
    const css = withoutComments(readStylesheet());
    const blocks = mediaBlocks(css);
    expect(ruleBody(css, '.app-shell__nav')).toMatch(/position:\s*fixed/);
    expect(blocks.get('(min-width: 768px)') ?? '').toMatch(
      /\.app-shell__nav\s*\{[^}]*position:\s*static/,
    );
    expect(blocks.get('(min-width: 1280px)') ?? '').toMatch(/\.app-shell__nav\s*\{/);
  });

  it('keeps the skip link visible on focus and the ring with an offset', () => {
    const css = withoutComments(readStylesheet());
    expect(ruleBody(css, '.skip-link')).toMatch(/inset-inline-start:\s*-9999px/);
    expect(ruleBody(css, '.skip-link:focus')).toMatch(/inset-inline-start:/);
    expect(ruleBody(css, ':focus-visible')).toMatch(/outline:\s*var\(--sc-border-width-focus/);
    expect(ruleBody(css, ':focus-visible')).toMatch(
      /outline-offset:\s*var\(--sc-border-width-focus/,
    );
    expect(css).not.toMatch(/outline:\s*(?:none|0)\b/);
  });

  it('maps safe-area insets, honours reduced motion and wraps long values', () => {
    const css = withoutComments(readStylesheet());
    for (const edge of ['top', 'bottom', 'left', 'right']) {
      expect(css).toContain(`env(safe-area-inset-${edge})`);
    }
    expect(css).toMatch(/overflow-wrap:\s*anywhere/);
    expect(css).toMatch(/min-inline-size:\s*0/);
    expect(mediaBlocks(css).get('(prefers-reduced-motion: reduce)') ?? '').toMatch(
      /transition-duration:\s*0ms/,
    );
  });

  it('sizes coarse-pointer targets at 44px and keeps the iOS-safe field font', () => {
    const css = withoutComments(readStylesheet());
    const coarse = mediaBlocks(css).get('(pointer: coarse)') ?? '';
    expect(coarse).toMatch(
      /\.touch\s*\{[^}]*min-block-size:\s*var\(--sc-size-target-comfortable,\s*44px\)/,
    );
    expect(coarse).toMatch(
      /\.touch\s*\{[^}]*min-inline-size:\s*var\(--sc-size-target-comfortable,\s*44px\)/,
    );
    expect(ruleBody(css, '.field')).toMatch(/inline-size:\s*100%/);
    expect(ruleBody(css, '.field')).toMatch(/font-size:\s*16px/);
  });

  it('fills the screen with .sheet on phones and centres it from 768px', () => {
    const css = withoutComments(readStylesheet());
    const medium = mediaBlocks(css).get('(min-width: 768px)') ?? '';
    expect(ruleBody(css, '.sheet')).toMatch(/inset:\s*0/);
    expect(ruleBody(css, '.sheet')).toMatch(/block-size:\s*100%/);
    expect(medium).toMatch(/\.sheet\s*\{[^}]*block-size:\s*auto/);
  });

  it('shows the rail labels only at the expanded breakpoint', () => {
    const blocks = mediaBlocks(withoutComments(readStylesheet()));
    expect(blocks.get('(min-width: 768px)') ?? '').toMatch(
      /\.app-shell__label\s*\{[^}]*display:\s*none/,
    );
    expect(blocks.get('(min-width: 1280px)') ?? '').toMatch(
      /\.app-shell__label\s*\{[^}]*display:\s*inline/,
    );
  });

  it('swaps the inline safe-area mapping under right-to-left', () => {
    const rtl = ruleBody(withoutComments(readStylesheet()), ":root[dir='rtl']");
    expect(rtl).toMatch(/--safe-inline-start:\s*env\(safe-area-inset-right\)/);
    expect(rtl).toMatch(/--safe-inline-end:\s*env\(safe-area-inset-left\)/);
  });

  it('uses the contract token names with the documented fallback values', () => {
    const css = withoutComments(readStylesheet());
    // Light-mode values from docs/design/token-contract.md v1.0.0.
    const expected = new Map<string, string>([
      ['--sc-space-2', '8px'],
      ['--sc-space-4', '16px'],
      ['--sc-space-8', '32px'],
      ['--sc-border-width-thin', '1px'],
      ['--sc-border-width-focus', '2px'],
      ['--sc-color-border-default', '#6b7280'],
      ['--sc-color-border-subtle', '#c3cbd8'],
      ['--sc-color-focus-ring', '#0b5cab'],
      ['--sc-color-surface-default', '#ffffff'],
      ['--sc-color-text-primary', '#152033'],
      ['--sc-radius-md', '8px'],
      ['--sc-size-target-min', '24px'],
      ['--sc-size-target-comfortable', '44px'],
      ['--sc-motion-duration-base', '180ms'],
      ['--sc-motion-easing-standard', 'cubic-bezier(0.2, 0, 0.2, 1)'],
    ]);
    const fallbacks = tokenFallbacks(css);
    expect(fallbacks.size).toBeGreaterThan(0);
    expect([...fallbacks.keys()].sort()).toEqual([...expected.keys()].sort());
    for (const [name, value] of fallbacks) {
      expect(value, `fallback for ${name}`).toBe(expected.get(name));
    }
    expect(css).not.toMatch(/var\(\s*--(?!sc-|layout-|safe-)/);
  });

  it('stays under the 6 KB gzip budget', () => {
    const css = readStylesheet();
    expect(css.length).toBeGreaterThan(0);
    const bytes = gzipSync(Buffer.from(css, 'utf8')).byteLength;
    expect(bytes).toBeGreaterThan(0);
    expect(bytes).toBeLessThan(6 * 1024);
  });
});
