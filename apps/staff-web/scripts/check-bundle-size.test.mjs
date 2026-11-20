import { Buffer } from 'node:buffer';
import { randomBytes } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  MAX_CHUNK_GZIP_BYTES,
  assertWithinBudget,
  gzipSize,
  measureAssets,
} from './check-bundle-size.mjs';

describe('bundle gzip budget (WEB-08)', () => {
  it('fixes the limit at 250 KB expressed as 256000 bytes', () => {
    expect(MAX_CHUNK_GZIP_BYTES).toBe(256000);
  });

  it('rejects a 300 KB incompressible buffer', () => {
    const buffer = randomBytes(300 * 1024);
    expect(gzipSize(buffer)).toBeGreaterThan(MAX_CHUNK_GZIP_BYTES);
    expect(() => assertWithinBudget(buffer, 'large.js')).toThrow(/large\.js/);
    expect(() => assertWithinBudget(buffer, 'large.js')).toThrow(/WEB-08/);
  });

  it('accepts a 10 KB incompressible buffer', () => {
    const buffer = randomBytes(10 * 1024);
    expect(gzipSize(buffer)).toBeLessThanOrEqual(MAX_CHUNK_GZIP_BYTES);
    expect(() => assertWithinBudget(buffer, 'small.js')).not.toThrow();
  });

  it('measures gzip size rather than raw byte length', () => {
    const repetitive = Buffer.alloc(300 * 1024, 0x41);
    expect(repetitive.byteLength).toBeGreaterThan(MAX_CHUNK_GZIP_BYTES);
    expect(gzipSize(repetitive)).toBeLessThanOrEqual(MAX_CHUNK_GZIP_BYTES);
  });

  it('fails the gate when any measured asset is over budget', () => {
    const directory = mkdtempSync(join(tmpdir(), 'bundle-size-'));
    try {
      writeFileSync(join(directory, 'small.js'), randomBytes(10 * 1024));
      writeFileSync(join(directory, 'oversized.js'), randomBytes(300 * 1024));
      writeFileSync(join(directory, 'ignored.txt'), randomBytes(300 * 1024));

      const { rows, failed } = measureAssets(directory);
      expect(failed).toBe(true);
      expect(rows.map((row) => row.name)).toEqual(['oversized.js', 'small.js']);
      expect(rows[0].withinBudget).toBe(false);
      expect(rows[1].withinBudget).toBe(true);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it('wires the size check into check.sh after the build step', () => {
    const check = readFileSync('check.sh', 'utf8');
    expect(check).toContain('npm run build');
    expect(check).toContain('node scripts/check-bundle-size.mjs');
    expect(check.indexOf('node scripts/check-bundle-size.mjs')).toBeGreaterThan(
      check.indexOf('npm run build'),
    );
  });
});
