import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import * as layout from './index';

describe('staff-message layout exports', () => {
  it('exports only the public component, props type helpers and message bundle', () => {
    expect(Object.keys(layout).sort()).toEqual(
      ['StaffMessageLayout', 'layoutKeys', 'layoutMessages', 'layoutText'].sort(),
    );
  });
});

describe('RTL-safe layout', () => {
  it('uses logical styling properties in every feature source file', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const sourceFiles = readdirSync(featureDir).filter(
      (name) =>
        (name.endsWith('.tsx') || name.endsWith('.ts')) &&
        !name.includes('.test.') &&
        name !== 'fixtures.ts',
    );
    expect(sourceFiles.length).toBeGreaterThan(0);
    for (const name of sourceFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
