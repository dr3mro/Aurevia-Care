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

// Only inspect meta tags inside <head>; a body-level tag must not satisfy a
// document-level requirement such as the viewport or theme colour.
const headMetas = (html: string): string[] =>
  [...(/<head\b[^>]*>([\s\S]*?)<\/head>/i.exec(html)?.[1] ?? '').matchAll(/<meta\b[^>]*>/gi)].map(
    (match) => match[0],
  );

const metaContent = (tag: string | undefined): string =>
  tag?.match(/\bcontent=["']([^"']*)["']/i)?.[1] ?? '';

const viewportContent = (html: string): string =>
  metaContent(headMetas(html).find((tag) => /\bname=["']viewport["']/i.test(tag)));

const themeColourFor = (html: string, scheme: 'light' | 'dark'): string =>
  metaContent(
    headMetas(html).find(
      (tag) =>
        /\bname=["']theme-color["']/i.test(tag) &&
        new RegExp(`prefers-color-scheme:\\s*${scheme}\\b`, 'i').test(tag),
    ),
  );

describe('staff web indexability (WEB-13)', () => {
  it('marks the authenticated portal noindex inside the document head', () => {
    const html = read('index.html');
    const head = /<head\b[^>]*>([\s\S]*?)<\/head>/i.exec(html)?.[1] ?? '';
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
    expect(html.toLowerCase()).not.toMatch(/analytics|googletagmanager|google-analytics/);
  });

  it('sets a zoomable viewport that covers the screen for safe-area insets (WEB-07, UX-01)', () => {
    const viewport = viewportContent(read('index.html'));
    // Assert the exact required value: an extra token could reintroduce a zoom
    // restriction, and a token matcher would tolerate a fractional initial zoom.
    expect(viewport).toBe('width=device-width, initial-scale=1, viewport-fit=cover');
    // viewport-fit=cover lets page content extend under the display cutout so
    // CSS env(safe-area-inset-*) can inset interactive content.
    expect(viewport).toMatch(/\bviewport-fit=cover\b/i);
    // Zoom must stay available for low-vision users (WCAG 1.4.4).
    expect(viewport).not.toMatch(/user-scalable\s*=\s*(?:no|0)\b/i);
    expect(viewport).not.toMatch(/maximum-scale\s*=/i);
  });

  it('keeps the light and dark colour-scheme declaration (WEB-07, UX-01)', () => {
    const html = read('index.html');
    const colorScheme = metaContent(
      headMetas(html).find((tag) => /\bname=["']color-scheme["']/i.test(tag)),
    );
    expect(colorScheme).toBe('light dark');
  });

  it('declares a distinct theme colour for light and dark schemes (WEB-07, UX-01)', () => {
    const html = read('index.html');
    const themes = headMetas(html).filter((tag) => /\bname=["']theme-color["']/i.test(tag));
    expect(themes).toHaveLength(2);
    for (const theme of themes) {
      expect(theme, `theme colour has a media attribute: ${theme}`).toMatch(/\bmedia=/i);
    }
    const light = themeColourFor(html, 'light');
    const dark = themeColourFor(html, 'dark');
    expect(light).toMatch(/^#[0-9a-f]{6}$/i);
    expect(dark).toMatch(/^#[0-9a-f]{6}$/i);
    expect(light.toLowerCase()).not.toBe(dark.toLowerCase());
  });
});
