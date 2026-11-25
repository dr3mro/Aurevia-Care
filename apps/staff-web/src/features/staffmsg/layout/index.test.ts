import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import * as layout from './index';

describe('staff message layout exports', () => {
  it('exports only the public wrapper, pane geometry and message bundle', () => {
    expect(Object.keys(layout).sort()).toEqual(
      [
        'MessageLayout',
        'layoutKeys',
        'layoutMessages',
        'layoutText',
        'paneLayout',
      ].sort(),
    );
  });
});

describe('RTL-safe layout source', () => {
  it('uses logical styling properties in every feature source file', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const sourceFiles = readdirSync(featureDir).filter(
      (name) =>
        (name.endsWith('.tsx') || name.endsWith('.ts')) &&
        !name.includes('.test.') &&
        name !== 'fixtures.ts',
    );
    expect(sourceFiles.length).toBeGreaterThan(0);
    const physical =
      /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]|\b(min|max)?Width\s*:|\bbottom\s*:|overflowX\s*:/;
    for (const name of sourceFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(physical);
    }
  });
});
