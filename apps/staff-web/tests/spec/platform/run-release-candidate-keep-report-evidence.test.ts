/**
 * WEB-10 clause 3 evidence for the staff web: the end-to-end suite must run on
 * every release candidate and its report must be kept as evidence.
 *
 * This snapshot has the suite and its npm script but no workflow that invokes
 * them and no durable report, so the clause is not built. The tests read the
 * real CI files with Node's file API and assert that current gap, which keeps
 * the assessment green without a skipped test. The inline workflow strings are
 * a synthetic detector self-test: they show what a satisfied workflow would
 * look like and that a workflow missing either half of the clause is refused.
 * No application code, dependency, database or patient data is involved.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/** Directory of this file, used only to locate repository files for the survey. */
const here = dirname(fileURLToPath(import.meta.url));

/** Absolute path to the staff-web module root (three levels above this file). */
const staffWebRoot = resolve(here, '..', '..', '..');

/** Absolute path to the repository root, where the CI workflows live. */
const repoRoot = resolve(staffWebRoot, '..', '..');

/** Read a staff-web file as UTF-8 text. */
function readFromStaffWeb(path: string): string {
  return readFileSync(resolve(staffWebRoot, path), 'utf8');
}

/** Read a repository-root file as UTF-8 text. */
function readFromRepo(path: string): string {
  return readFileSync(resolve(repoRoot, path), 'utf8');
}

/** Match a workflow step that actually runs the end-to-end suite. */
const e2eStepPattern = /npm run e2e|playwright test/;

/** Match a workflow artifact step that keeps a Playwright report on disk. */
const reportArtifactPattern = /playwright-report|e2e-report|blob-report|test-results/;

/** Join synthetic workflow lines into one YAML-like text block. */
function workflowText(...lines: string[]): string {
  return lines.join('\n');
}

describe('WEB-10 clause 3 — run the end-to-end suite on every release candidate and keep the report', () => {
  it('recordsThatTheSuiteExistsButItsReporterWritesOnlyToTheConsole', () => {
    const scripts = readFromStaffWeb('package.json');
    const config = readFromStaffWeb('e2e/playwright.config.ts');
    const smoke = readFromStaffWeb('e2e/smoke.spec.ts');

    expect(scripts).toContain('"e2e": "playwright test --config e2e/playwright.config.ts"');
    expect(config).toContain("testMatch: '**/*.spec.ts'");
    expect(config).toContain("reporter: 'list'");
    expect(config).not.toMatch(/reporter:.*(?:html|json|junit|blob)/);
    expect(smoke).toContain('expectNoBlockingViolations');
    expect(smoke).toContain('AxeBuilder');
    expect(smoke).toContain('/design-preview');
    expect(smoke).toContain("'rtl'");
  });

  it('detectsACandidateWorkflowThatRunsE2eAndKeepsAReport', () => {
    // Synthetic detector self-test: this is what a satisfied workflow would
    // look like, not a claim that such a workflow exists in this repository.
    const satisfied = workflowText(
      'name: Release candidate',
      'jobs:',
      '  e2e:',
      '    steps:',
      '      - run: npm run e2e -- --reporter=html',
      '      - uses: actions/upload-artifact@v4',
      '        with:',
      '          name: playwright-report',
      '          path: playwright-report/',
    );

    expect(e2eStepPattern.test(satisfied)).toBe(true);
    expect(reportArtifactPattern.test(satisfied)).toBe(true);
  });

  it('refusesAWorkflowWithoutAnE2eStepOrWithoutAKeptReport', () => {
    const withoutStep = workflowText(
      'name: Release candidate',
      'jobs:',
      '  smoke:',
      '    steps:',
      '      - run: npm run build',
    );
    expect(e2eStepPattern.test(withoutStep)).toBe(false);

    const withoutReport = workflowText(
      'name: Release candidate',
      'jobs:',
      '  e2e:',
      '    steps:',
      '      - run: npm run e2e',
    );
    expect(e2eStepPattern.test(withoutReport)).toBe(true);
    expect(reportArtifactPattern.test(withoutReport)).toBe(false);
  });

  it('recordsThatNoRealWorkflowRunsTheSuiteOrKeepsAReport', () => {
    const workflowDir = resolve(repoRoot, '.github', 'workflows');
    const fileNames = readdirSync(workflowDir).filter((fileName) => fileName.endsWith('.yml'));
    expect(fileNames.length).toBeGreaterThan(0);

    const workflows = fileNames.map((fileName) => ({
      fileName,
      text: readFromRepo(`.github/workflows/${fileName}`),
    }));
    const withE2e = workflows.filter((workflow) => e2eStepPattern.test(workflow.text));
    const withReport = workflows.filter((workflow) => reportArtifactPattern.test(workflow.text));

    expect(withE2e.map((workflow) => workflow.fileName)).toEqual([]);
    expect(withReport.map((workflow) => workflow.fileName)).toEqual([]);
  });
});
