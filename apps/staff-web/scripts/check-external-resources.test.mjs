import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  THIRD_PARTY_HOSTS,
  assertNoExternalResources,
  findExternalResources,
  scanBuiltOutput,
} from './check-external-resources.mjs';

/** Build a throwaway output tree from a map of relative path to contents. */
const tempDirectory = (files) => {
  const directory = mkdtempSync(join(tmpdir(), 'external-resources-'));
  for (const [name, contents] of Object.entries(files)) {
    const path = join(directory, name);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, contents);
  }
  return directory;
};

describe('no external resources in the build output (WEB-08)', () => {
  it('lists the third-party font and tracker hosts that are forbidden', () => {
    expect(THIRD_PARTY_HOSTS).toContain('fonts.googleapis.com');
    expect(THIRD_PARTY_HOSTS).toContain('fonts.gstatic.com');
    expect(THIRD_PARTY_HOSTS).toContain('www.google-analytics.com');
    expect(THIRD_PARTY_HOSTS).toContain('googletagmanager.com');
    expect(THIRD_PARTY_HOSTS).toContain('cdn.jsdelivr.net');
  });

  it('reports an external script src in built HTML', () => {
    const findings = findExternalResources(
      '<script src="https://cdn.example.com/tracker.js"></script>',
      'dist/index.html',
    );
    expect(findings).toEqual([
      { label: 'dist/index.html', kind: 'script', host: 'cdn.example.com' },
    ]);
  });

  it('reports an external stylesheet or font href in built HTML', () => {
    const findings = findExternalResources(
      '<link rel="stylesheet" href="https://fonts.example.test/face.css">',
      'dist/index.html',
    );
    expect(findings).toEqual([
      { label: 'dist/index.html', kind: 'link', host: 'fonts.example.test' },
    ]);
  });

  it('reports a protocol-relative external resource', () => {
    const findings = findExternalResources('<script src="//cdn.example.com/a.js"></script>');
    expect(findings.map((finding) => finding.host)).toEqual(['cdn.example.com']);
  });

  it('reports an absolute url() in CSS or bundled JavaScript', () => {
    const findings = findExternalResources(
      '@font-face { src: url("https://cdn.example.com/a.woff2") format("woff2"); }',
      'dist/assets/index.css',
    );
    expect(findings).toEqual([
      { label: 'dist/assets/index.css', kind: 'url', host: 'cdn.example.com' },
    ]);
  });

  it('reports a quoted @import of an external stylesheet or font', () => {
    const findings = findExternalResources(
      '@import "https://cdn.example.com/fonts.css";',
      'dist/assets/index.css',
    );
    expect(findings).toEqual([
      { label: 'dist/assets/index.css', kind: 'import', host: 'cdn.example.com' },
    ]);
  });

  it('reports an @import url() form once', () => {
    const findings = findExternalResources('@import url(https://cdn.example.com/fonts.css);');
    expect(findings.map((finding) => finding.host)).toEqual(['cdn.example.com']);
    expect(findings).toHaveLength(1);
  });

  it('reports a known tracker host even when it is only a string', () => {
    const findings = findExternalResources(
      'window.dataLayer = []; /* https://www.google-analytics.com/analytics.js */',
      'dist/assets/index.js',
    );
    expect(findings).toEqual([
      { label: 'dist/assets/index.js', kind: 'third-party', host: 'www.google-analytics.com' },
    ]);
  });

  it('ignores same-origin and relative URLs', () => {
    const html =
      '<script src="/assets/index-abc123.js"></script>' +
      '<link rel="stylesheet" href="./assets/index-abc123.css">' +
      'url("/fonts/smartclinic-ui-subset.woff")';
    expect(findExternalResources(html)).toEqual([]);
  });

  it('ignores benign absolute URLs that are not loaded resources', () => {
    expect(findExternalResources('See https://react.dev/errors/418 for details')).toEqual([]);
  });

  it('de-duplicates repeated references to the same host and kind', () => {
    const html = '<script src="https://cdn.example.com/a.js"></script>'.repeat(3);
    expect(findExternalResources(html)).toHaveLength(1);
  });

  it('returns no findings for empty content', () => {
    expect(findExternalResources('')).toEqual([]);
  });

  it('walks the built output recursively and ignores non-text assets', () => {
    const directory = tempDirectory({
      'index.html': '<script src="https://cdn.html.test/a.js"></script>',
      'assets/index.css': 'url(https://cdn.css.test/a.woff2)',
      'assets/index.js': 'console.log(1);',
      'assets/logo.png': 'not text',
      'fonts/fonts.css': 'url("/fonts/local.woff")',
    });
    try {
      const findings = scanBuiltOutput(directory);
      expect(findings.map((finding) => `${finding.kind}:${finding.host}:${finding.label}`)).toEqual([
        'url:cdn.css.test:assets/index.css',
        'script:cdn.html.test:index.html',
      ]);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it('accepts a file URL as well as a path for the output directory', () => {
    const directory = tempDirectory({
      'index.html': '<script src="https://cdn.example.com/a.js"></script>',
    });
    try {
      expect(scanBuiltOutput(pathToFileURL(directory))).toEqual([
        { label: 'index.html', kind: 'script', host: 'cdn.example.com' },
      ]);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it('fails a fixture bundle that contains an external URL', () => {
    const directory = tempDirectory({
      'index.html':
        '<script type="module" src="/assets/index-abc.js"></script>' +
        '<script src="https://third-party.example/tracker.js"></script>',
    });
    try {
      expect(() => assertNoExternalResources(directory)).toThrow(/WEB-08/);
      expect(() => assertNoExternalResources(directory)).toThrow(/third-party\.example/);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it('accepts a clean fixture bundle whose assets are local and hashed', () => {
    const directory = tempDirectory({
      'index.html': '<script type="module" src="/assets/index-ab12cd.js"></script>',
      'assets/index-ab12cd.css': 'url("/fonts/smartclinic-ui-subset.woff")',
    });
    try {
      expect(assertNoExternalResources(directory)).toEqual([]);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
});
