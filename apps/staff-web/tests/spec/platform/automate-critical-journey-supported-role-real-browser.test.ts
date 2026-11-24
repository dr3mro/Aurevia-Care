/**
 * WEB-10 clause 2 evidence, staff web: real-browser role journeys in English
 * and Arabic RTL with a blocking accessibility gate.
 *
 * This file inspects the committed end-to-end harness as an artifact. It proves
 * only the slice that exists today: seven domain journeys carry an Arabic RTL
 * path and a shared serious/critical axe gate is wired into three of them. The
 * remaining halves of the clause (every supported role, a staging release-
 * candidate run whose report is kept, and light/dark visual checks) are pinned
 * by an active GAP tripwire below and described in the clause note, rather
 * than by a skipped test (the repository forbids added skips). No application
 * endpoint, database or patient data is involved; all fixtures are synthetic.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type { Role } from '../../../src/app/navigation';
import {
  canAccessRoute,
  navigationFor,
  routeAccessRoles,
} from '../../../src/app/navigation';

const here = dirname(fileURLToPath(import.meta.url));
const moduleRoot = resolve(here, '..', '..', '..');
const repoRoot = resolve(moduleRoot, '..', '..');

/** Read a file inside the staff-web module as text. */
const readModule = (path: string): string => readFileSync(resolve(moduleRoot, path), 'utf8');

/** Read a file inside the repository as text. */
const readRepo = (path: string): string => readFileSync(resolve(repoRoot, path), 'utf8');

/**
 * List every file below a directory, relative to that directory.
 *
 * Used only to count committed test and workflow artifacts; it never resolves
 * a path outside the given root and never contacts an application endpoint.
 */
function filesBelow(root: string, prefix = ''): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const relative = prefix !== '' ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      found.push(...filesBelow(resolve(root, entry.name), relative));
    } else {
      found.push(relative);
    }
  }
  return found;
}

/** Domain journeys that currently drive a real browser in both locales. */
const journeySpecs: readonly string[] = [
  'reception/reception-journey.spec.ts',
  'labflow/lab-bilingual-journey.spec.ts',
  'orderentry/orderentry-journey.spec.ts',
  'radflow/radiology-journey.spec.ts',
  'clinicalnote/clinicalnote-journey.spec.ts',
  'pharmacyflow/pharmacy-journey.spec.ts',
  'print/print-journey.spec.ts',
];

/** Journeys parameterised over an explicit English and Arabic word set. */
const parameterizedJourneys: readonly string[] = [
  'reception/reception-journey.spec.ts',
  'labflow/lab-bilingual-journey.spec.ts',
  'orderentry/orderentry-journey.spec.ts',
  'clinicalnote/clinicalnote-journey.spec.ts',
  'print/print-journey.spec.ts',
];

/** Journeys that actually invoke the shared blocking axe gate. */
const axeGateConsumers: readonly string[] = [
  'reception/reception-journey.spec.ts',
  'labflow/lab-bilingual-journey.spec.ts',
  'clinicalnote/clinicalnote-journey.spec.ts',
];

/** Every e2e spec committed under the module, as module-relative paths. */
const e2eSpecs = (): string[] =>
  filesBelow(resolve(moduleRoot, 'e2e')).filter((path) => path.endsWith('.spec.ts'));

/** The exact e2e spec set committed today; a new journey must replace this pin. */
const currentSpecs: readonly string[] = [
  'adminflow/admin-journey.spec.ts',
  'clinicalnote/clinicalnote-journey.spec.ts',
  'financeflow/finance-journey.spec.ts',
  'labflow/lab-bilingual-journey.spec.ts',
  'labflow/lab-journey.spec.ts',
  'orderentry/orderentry-journey.spec.ts',
  'pharmacyflow/pharmacy-bilingual-journey.spec.ts',
  'pharmacyflow/pharmacy-journey.spec.ts',
  'print/print-journey.spec.ts',
  'radflow/radiology-bilingual-journey.spec.ts',
  'radflow/radiology-journey.spec.ts',
  'reception/reception-journey.spec.ts',
  'responsiveLayout.spec.ts',
  'smoke.spec.ts',
  'therapyflow/therapy-journey.spec.ts',
];

describe('WEB-10 clause 2 — real-browser role journeys', () => {
  /**
   * Positive proof of the implemented half. Each assertion reads a committed
   * artifact and would fail if the journey, its Arabic RTL path, the injectable
   * staging base URL or the serious/critical axe filter were removed.
   */
  it('browserJourneysCoverSupportedRolesBilinguallyWithBlockingAxeGate', () => {
    for (const path of journeySpecs) {
      const spec = readModule(`e2e/${path}`);
      // The visible Arabic toggle and an asserted dir prove the RTL path, and
      // reading the file proves the journey exists at all.
      expect(spec).toContain("'العربية'");
      expect(spec).toMatch(/toHaveAttribute\('dir'/);
    }

    for (const path of parameterizedJourneys) {
      const spec = readModule(`e2e/${path}`);
      expect(spec).toContain("locale: 'en'");
      expect(spec).toContain("locale: 'ar'");
    }

    const axe = readModule('e2e/accessibility.ts');
    expect(axe).toContain("violation.impact === 'serious' || violation.impact === 'critical'");
    expect(axe).toContain('new AxeBuilder({ page }).analyze()');
    for (const path of axeGateConsumers) {
      const spec = readModule(`e2e/${path}`);
      expect(spec).toContain("import { expectNoBlockingViolations } from '../accessibility'");
      expect(spec).toContain('await expectNoBlockingViolations(page)');
    }

    expect(readModule('e2e/playwright.config.ts')).toContain('E2E_BASE_URL');
  });

  /**
   * Negative case: the rail and the route guard must refuse a role another
   * role's journey. These are the real navigation boundaries the journeys rely
   * on, so a reception user cannot open the tenant-admin or laboratory paths.
   */
  it('wrongRoleIsRefusedAnotherRolesJourney', () => {
    expect(routeAccessRoles('/admin/facilities')).toEqual(['tenant_admin']);
    expect(routeAccessRoles('/lab/accessions')).toEqual(['lab']);

    expect(canAccessRoute('reception', '/admin/facilities')).toBe(false);
    expect(canAccessRoute('reception', '/lab/accessions')).toBe(false);
    expect(canAccessRoute('lab', '/lab/accessions')).toBe(true);
    expect(canAccessRoute('tenant_admin', '/admin/facilities')).toBe(true);

    const receptionItems = navigationFor('reception').map((item) => item.id);
    expect(receptionItems).not.toContain('admin-facilities');
    expect(receptionItems).not.toContain('accessions');
    expect(navigationFor('tenant_admin').map((item) => item.id)).toContain('admin-facilities');
    expect(navigationFor('lab').map((item) => item.id)).toContain('accessions');
  });

  /**
   * GAP tripwire: the clause is not fully evidenced yet. These assertions pin
   * the current artifact shape and the exact supported-role union. They are
   * active (not skipped) so the suite stays honest; each one fails on the day
   * the missing evidence lands, at which point it must be replaced by a real
   * positive assertion and the clause note updated to match. The last one is a
   * compile-time tripwire: it is enforced by the typecheck gate in check.sh,
   * not at runtime. The recognised visual-check vocabulary is screenshot and
   * snapshot comparators plus theme attributes and computed-style reads.
   */
  it('GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded', () => {
    // Pin the whole spec set, not just the seven names we recognise: a new
    // per-role journey would otherwise slip past this tripwire unnoticed.
    const specs = e2eSpecs();
    expect(specs.sort()).toEqual([...currentSpecs].sort());

    const workflows = filesBelow(resolve(repoRoot, '.github/workflows'))
      .filter((path) => /\.(yml|yaml)$/.test(path))
      .filter((path) => /playwright|e2e/i.test(readRepo(`.github/workflows/${path}`)));
    expect(workflows.sort()).toEqual([]);

    const visualChecks = specs.filter((path) =>
      /toHaveScreenshot|toMatchSnapshot|getComputedStyle|data-theme|colorScheme/.test(
        readModule(`e2e/${path}`),
      ),
    );
    expect(visualChecks.sort()).toEqual([]);

    expectTypeOf<Role>().toEqualTypeOf<
      | 'staff'
      | 'reception'
      | 'clinician'
      | 'lab'
      | 'radiology'
      | 'therapist'
      | 'pharmacy'
      | 'finance'
      | 'operator'
      | 'unit_staff'
      | 'tenant_admin'
      | 'privacy_auditor'
    >();
  });
});
