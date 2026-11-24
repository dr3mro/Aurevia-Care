import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative: string): string => readFileSync(resolve(root, relative), 'utf8');

/**
 * Assert the failure-detecting part of the shared toolchain contract.
 *
 * Keeping this separate makes the negative control precise: a weakened
 * threshold is rejected by this function, not by an unrelated missing string.
 */
function requireCoverageThresholds(config: string): void {
  for (const metric of ['lines', 'functions', 'branches', 'statements']) {
    expect(config, `${metric} coverage must be 100`).toContain(`${metric}: 100`);
  }
}

describe('shared frontend tooling', () => {
  it('keeps strict compilation and a 100% coverage gate wired into root scripts', () => {
    const config = read('vitest.config.ts');
    const manifest = JSON.parse(read('package.json')) as { scripts: Record<string, string> };

    expect(config).toContain("include: ['tools/**/*.test.ts']");
    requireCoverageThresholds(config);
    for (const script of ['typecheck', 'lint', 'format:check', 'test']) {
      expect(manifest.scripts[script]).toBeDefined();
    }
    expect(read('tsconfig.json')).toContain('"strict": true');
    expect(read('tsconfig.json')).toContain('"noUncheckedIndexedAccess": true');
  });

  it('rejects a lowered threshold', () => {
    expect(() => {
      requireCoverageThresholds('thresholds: { lines: 90 }');
    }).toThrow();
  });

  it('rejects a threshold that omits a metric', () => {
    const partial = 'thresholds: { lines: 100, functions: 100, branches: 100 }';
    expect(() => {
      requireCoverageThresholds(partial);
    }).toThrow();
  });
});
