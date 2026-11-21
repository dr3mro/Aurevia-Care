import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  FONT_STYLESHEET_HREF,
  IMMUTABLE_ASSET_HEADERS,
  assignChunk,
  immutableCacheHeader,
  injectFontStylesheet,
} from './web-platform-config.ts';

const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..');
const read = (path) => readFileSync(resolve(moduleRoot, path), 'utf8');

describe('staff web build platform (WEB-08)', () => {
  it('configures hashed asset names and wires the platform helpers', () => {
    const config = read('vite.config.ts');
    expect(config).toContain('[name]-[hash][extname]');
    expect(config).toContain('rollupOptions');
    expect(config).toContain('manualChunks: assignChunk');
    expect(config).toContain('immutableCacheHeader');
    expect(config).toContain('injectFontStylesheet');
  });

  it('splits shared React packages into a vendor chunk', () => {
    expect(assignChunk('/app/node_modules/react/index.js')).toBe('react-vendor');
    expect(assignChunk('/app/node_modules/react-dom/client.js')).toBe('react-vendor');
    expect(assignChunk('/app/node_modules/scheduler/index.js')).toBe('react-vendor');
  });

  it('keeps other packages in one vendor chunk and feature modules in route chunks', () => {
    expect(assignChunk('/app/node_modules/zod/index.js')).toBe('vendor');
    expect(assignChunk('/app/src/features/admin/FacilitiesPage.tsx')).toBeUndefined();
    expect(assignChunk('/app/src/features/patients/PatientsPage.tsx')).toBeUndefined();
  });

  it('serves hashed assets from /assets/ as immutable', () => {
    expect(IMMUTABLE_ASSET_HEADERS['/assets/']).toEqual({
      'Cache-Control': 'public, max-age=31536000, immutable',
    });
    expect(immutableCacheHeader('/assets/index-ab12cd.js')).toBe(
      'public, max-age=31536000, immutable',
    );
    expect(immutableCacheHeader('/index.html')).toBeUndefined();
    expect(immutableCacheHeader(undefined)).toBeUndefined();
  });

  it('injects the self-hosted font stylesheet exactly once', () => {
    expect(FONT_STYLESHEET_HREF).toBe('/fonts/fonts.css');
    const html = '<head><title>Staff</title></head><body></body>';
    const once = injectFontStylesheet(html);
    expect(once).toContain('<link rel="stylesheet" href="/fonts/fonts.css">');
    expect(once.match(/fonts\/fonts\.css/g)).toHaveLength(1);
    expect(injectFontStylesheet(once)).toBe(once);
  });

  it('self-hosts an Arabic-coverage font with a local @font-face', () => {
    const css = read('public/fonts/fonts.css');
    expect(css).toContain('@font-face');
    expect(css).toContain('font-display: swap');
    expect(css).toContain('url("/fonts/smartclinic-ui-subset.woff")');
    expect(css).toContain('U+0600-06FF');
    expect(css).not.toMatch(/https?:\/\//);
  });

  it('ships the self-hosted font asset as a real WOFF container', () => {
    const path = resolve(moduleRoot, 'public/fonts/smartclinic-ui-subset.woff');
    const font = readFileSync(path);
    expect(font.subarray(0, 4).toString('ascii')).toBe('wOFF');
    expect(statSync(path).size).toBeGreaterThan(200);
  });

  it('runs the external-resource gate inside the wired bundle-size check', () => {
    const script = read('scripts/check-bundle-size.mjs');
    expect(script).toContain("from './check-external-resources.mjs'");
    expect(script).toContain('assertNoExternalResources(');
  });
});
