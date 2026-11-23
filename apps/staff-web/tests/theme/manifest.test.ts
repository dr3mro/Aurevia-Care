import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

interface WebAppManifest {
  name: string;
  short_name: string;
  display: string;
  start_url: string;
  scope: string;
  theme_color: string;
  background_color: string;
  dir: string;
  lang: string;
  icons?: { src: string }[];
}

type RequiredStringKey = Exclude<keyof WebAppManifest, 'icons'>;

const here = dirname(fileURLToPath(import.meta.url));
const publicRoot = resolve(here, '..', '..', 'public');
const manifestPath = resolve(publicRoot, 'manifest.webmanifest');

const readText = (): string => (existsSync(manifestPath) ? readFileSync(manifestPath, 'utf8') : '');

const parseManifest = (): WebAppManifest => JSON.parse(readText()) as WebAppManifest;

const requiredStringKeys: RequiredStringKey[] = [
  'name',
  'short_name',
  'display',
  'start_url',
  'scope',
  'theme_color',
  'background_color',
  'dir',
  'lang',
];

describe('staff web app manifest (WEB-07, UX-01)', () => {
  it('exists and is valid JSON', () => {
    expect(existsSync(manifestPath), 'public/manifest.webmanifest exists').toBe(true);
    expect(() => parseManifest(), 'manifest parses as JSON').not.toThrow();
  });

  it('declares every home-screen member as a non-empty string', () => {
    const manifest = parseManifest();
    for (const key of requiredStringKeys) {
      expect(typeof manifest[key], `${key} is a string`).toBe('string');
      expect(manifest[key].trim(), `${key} is not blank`).not.toBe('');
    }
  });

  it('uses standalone presentation with the online-only root document', () => {
    const manifest = parseManifest();
    expect(manifest).toMatchObject({
      name: 'Aurevia Care Staff Portal',
      short_name: 'Staff Portal',
      display: 'standalone',
      start_url: '/',
      scope: '/',
      theme_color: '#0b5cab',
      background_color: '#ffffff',
      dir: 'auto',
      lang: 'en',
    });
  });

  it('registers no service worker, cache or offline mode', () => {
    const text = readText();
    const keys = Object.keys(parseManifest()).map((key) => key.toLowerCase());
    expect(text).not.toMatch(/service.?worker/i);
    expect(text).not.toMatch(/\bcache\b|\boffline\b/i);
    for (const banned of ['serviceworker', 'service_worker', 'cache', 'caching', 'offline']) {
      expect(keys, `no ${banned} member`).not.toContain(banned);
    }
  });

  it('keeps start_url relative to the root and free of patient data', () => {
    const { start_url } = parseManifest();
    expect(start_url, 'root-relative URL').toMatch(/^\/(?!\/)/);
    expect(start_url, 'no scheme').not.toMatch(/^[a-z][a-z\d+.-]*:/i);
    expect(start_url, 'not protocol-relative').not.toMatch(/^\/\//);
    expect(start_url, 'no query or fragment').not.toMatch(/[?#]/);
    expect(start_url, 'no path segments').toBe('/');
    expect(start_url, 'no patient-shaped token').not.toMatch(/patient|mrn|chart|\d/i);
  });

  it('declares no icon that is absent from the repository', () => {
    const icons = parseManifest().icons ?? [];
    // No icon asset ships with the staff web, so none may be claimed here.
    expect(icons).toEqual([]);
    for (const icon of icons) {
      expect(icon.src, 'relative icon path').toMatch(/^\/(?!\/)/);
      expect(
        existsSync(resolve(publicRoot, icon.src.replace(/^\//, ''))),
        `${icon.src} exists`,
      ).toBe(true);
    }
  });
});
