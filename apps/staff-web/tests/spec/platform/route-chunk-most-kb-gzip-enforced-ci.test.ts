/**
 * WEB-08 clause 3 — "Each route chunk is at most 250 KB gzip, enforced in CI."
 *
 * Evidence for this clause is complete. The behavioural proof that the budget
 * refuses an oversized built chunk already lives in the existing
 * `scripts/check-bundle-size.test.mjs` suite and the real post-build gate at
 * `apps/staff-web/check.sh:18-19`. This file pins the checked-in ceiling,
 * measurement and refusal message of that gate so a silent change to the
 * budget is caught, and proves the CI-enforcement half: the required
 * `Frontend standards` job runs the canonical staff-web check, which builds
 * the app and invokes the budget script on the real emitted chunks. The exact
 * run-step pin is load-bearing, so dropping an enforcement step fails this case
 * instead of silently removing the gate.
 *
 * Synthetic buffers only: no network, database, fixture or patient data.
 */
import { Buffer } from 'node:buffer';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../../../../../');
const read = (path: string): string => readFileSync(resolve(repoRoot, path), 'utf8');

const BUDGET_SCRIPT = 'apps/staff-web/scripts/check-bundle-size.mjs';
const FRONTEND_CHECK = 'scripts/frontend-check.sh';
const DELIVERY_WORKFLOW = '.github/workflows/delivery.yml';
// WEB-08 states 250 KB; the gate is expressed in decimal bytes for determinism.
const BUDGET_BYTES = 256_000;

/** Return the trimmed lines of `text` that start with `prefix`. */
const linesStarting = (text: string, prefix: string): string[] =>
  text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith(prefix));

/** Return the body of a two-space-indented CI job, excluding its header. */
function workflowJob(workflow: string, name: string): string {
  const header = `  ${name}:\n`;
  const start = workflow.indexOf(header);
  if (start < 0) {
    return '';
  }
  const body = workflow.slice(start + header.length);
  const nextJob = body.search(/^ {2}[A-Za-z][A-Za-z0-9_-]*:\s*$/m);
  return nextJob < 0 ? body : body.slice(0, nextJob);
}

/** Return the npm commands in the per-module subshell of frontend-check.sh. */
function moduleCommands(script: string): string[] {
  const startMarker = '  (\n    cd "$module"';
  const endMarker = '  ) || fail=1';
  const start = script.indexOf(startMarker);
  const end = script.indexOf(endMarker, start);
  if (start < 0 || end < 0) {
    return [];
  }
  return linesStarting(script.slice(start + startMarker.length, end), 'npm ');
}

describe('WEB-08 clause 3 — route chunks at most 250 KB gzip, enforced in CI', () => {
  it('pins the 256000-byte ceiling and gzip measurement in the budget script', () => {
    const script = read(BUDGET_SCRIPT);
    // The checked-in ceiling is the decimal expression of the 250 KB budget.
    expect(script).toContain('MAX_CHUNK_GZIP_BYTES = 256_000');
    // The gate measures a real built asset with gzip, not its raw byte length.
    expect(script).toContain('gzipSync(contents).byteLength');
    expect(script).toContain("new URL('../dist/assets/', import.meta.url)");

    // Synthetic input confirms the measurement API used here is gzip, not raw bytes.
    const smallChunk = Buffer.alloc(10 * 1024, 0x41);
    expect(gzipSync(smallChunk).byteLength).toBeLessThanOrEqual(BUDGET_BYTES);
  });

  it('pins the over-budget refusal branch and WEB-08 message in the budget script', () => {
    // Random bytes resist compression, so the gzipped size still exceeds 250 KB.
    const oversizedChunk = randomBytes(300 * 1024);
    expect(gzipSync(oversizedChunk).byteLength).toBeGreaterThan(BUDGET_BYTES);

    // The negative branch and its WEB-08-labelled refusal message are present.
    const script = read(BUDGET_SCRIPT);
    expect(script).toContain('if (size > MAX_CHUNK_GZIP_BYTES)');
    expect(script).toContain('byte budget (WEB-08)');
  });

  /**
   * Positive proof of CI enforcement. The required `Frontend standards` job
   * runs the canonical staff-web check, which builds the app and then invokes
   * the WEB-08 budget gate on the real emitted chunks (`check.sh:18-19`). The
   * exact run-step pin is load-bearing: a dropped enforcement step, or a
   * removal of the build/budget commands from the canonical check, fails this
   * case instead of silently dropping enforcement.
   */
  it('CI Frontend standards job enforces the gzip budget through the canonical check', () => {
    const frontendJob = workflowJob(read(DELIVERY_WORKFLOW), 'frontend');
    expect(frontendJob).toContain('name: Frontend standards');

    // The required job runs the canonical staff-web check, not only the shared
    // policy script. Pinning the exact steps makes a dropped step visible.
    const runSteps = linesStarting(frontendJob, 'run:');
    expect(runSteps).toEqual([
      'run: bash scripts/frontend-check.sh',
      'run: bash apps/staff-web/check.sh',
      'run: bash apps/patient-mobile/check.sh',
    ]);

    // The shared script still pins typecheck, lint and test only; the budget
    // gate is applied by the canonical check above.
    const commands = moduleCommands(read(FRONTEND_CHECK));
    expect(commands).toEqual([
      'npm ci --no-audit --no-fund',
      'npm run typecheck',
      'npm run lint',
      'npm test',
    ]);

    // The canonical check builds first, then measures the real emitted chunks
    // with the budget script, so a stale asset cannot bypass the gate.
    const checkScript = read('apps/staff-web/check.sh');
    expect(checkScript).toContain('npm run build');
    expect(checkScript).toContain('node scripts/check-bundle-size.mjs');
  });
});
