/**
 * WEB-08 clause 3 — "Each route chunk is at most 250 KB gzip, enforced in CI."
 *
 * Evidence for this clause is split. The behavioural proof that the budget
 * refuses an oversized built chunk already lives in the existing
 * `scripts/check-bundle-size.test.mjs` suite and the real post-build gate at
 * `check.sh:18-19`. This file pins the checked-in ceiling, measurement and
 * refusal message of that gate so a silent change to the budget is caught, and
 * holds the CI-enforcement half as an ACTIVE positive proof: the required
 * `Frontend standards` job runs the staff-web canonical check.sh, which builds
 * the app and invokes the budget script. Dropping that run step fails the proof.
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
const STAFF_CHECK = 'apps/staff-web/check.sh';
const DELIVERY_WORKFLOW = '.github/workflows/delivery.yml';
// WEB-08 states 250 KB; the gate is expressed in decimal bytes for determinism.
const BUDGET_BYTES = 256_000;

/**
 * Return the shell command of every `run:` step in a CI job body, accepting both
 * the inline form (`- run: cmd`) and the named form (`- name: ...` with an
 * indented `run: cmd`). This lets the enforcement proof survive a harmless YAML
 * reformat of the step list.
 */
function runCommands(job: string): string[] {
  const commands: string[] = [];
  for (const line of job.split('\n')) {
    const match = /^\s*(?:-\s*)?run:\s*(.+)$/.exec(line);
    const command = match?.[1];
    if (command !== undefined) {
      commands.push(command.trim());
    }
  }
  return commands;
}

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
   * Positive proof for the CI-enforcement half of WEB-08 clause 3. The required
   * "Frontend standards" job now runs the staff-web canonical check.sh, which
   * builds the app and invokes the WEB-08 budget gate. This case pins that
   * chain so a future regression that drops the build, the budget script or the
   * run step fails the build. Synthetic fixtures only.
   */
  it('CI Frontend standards job builds and enforces the WEB-08 gzip budget gate', () => {
    const frontendJob = workflowJob(read(DELIVERY_WORKFLOW), 'frontend');
    expect(frontendJob).toContain('name: Frontend standards');

    const commands = runCommands(frontendJob);
    expect(commands).toContain('bash scripts/frontend-check.sh');
    expect(commands).toContain('bash apps/staff-web/check.sh');

    // The canonical check really builds and runs the budget script, so CI
    // refuses an oversized route chunk rather than only typecheck/lint/testing.
    const staffCheck = read(STAFF_CHECK);
    expect(staffCheck).toContain('npm run build');
    expect(staffCheck).toContain('node scripts/check-bundle-size.mjs');
  });
});
