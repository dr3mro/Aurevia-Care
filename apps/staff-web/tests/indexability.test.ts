import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..');

// An absent file reads as empty text so a missing rule fails as an assertion
// about the rule rather than an uncaught filesystem error.
const read = (path: string) => {
  const absolute = resolve(moduleRoot, path);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
};

describe('staff web indexability (WEB-13)', () => {
  it('marks the authenticated portal noindex inside the document head', () => {
    const html = read('index.html');
    const head = html.match(/<head\b[^>]*>([\s\S]*?)<\/head>/i)?.[1] ?? '';
    // Assert the exact single directive: a later conflicting "index, follow"
    // meta must fail rather than be masked by a lenient containment check.
    const robotsMetas = [...head.matchAll(/<meta\b[^>]*\bname=["']robots["'][^>]*>/gi)].map(
      (match) => match[0],
    );
    expect(robotsMetas).toEqual(['<meta name="robots" content="noindex, nofollow">']);
  });

  it('disallows all crawling with exactly two robots.txt rules', () => {
    const rules = read('public/robots.txt')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0);
    expect(rules).toEqual(['User-agent: *', 'Disallow: /']);
  });

  it('loads no analytics, tracker or external script', () => {
    const html = read('index.html');
    expect(html).not.toMatch(/<script[^>]+src=["']https?:\/\//i);
    expect(html.toLowerCase()).not.toMatch(
      /analytics|googletagmanager|google-analytics/,
    );
  });
});
