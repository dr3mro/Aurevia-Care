import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';
import { canAccessRoute } from '../src/app/navigation';
import { stateViewMessages } from '../src/components/stateViewMessages';

/**
 * Responsive WCAG 2.2 AA sweep of every registered staff-web route (WEB-07,
 * WEB-10, UX-01). The matrix is route x layout width x locale; each generated
 * test also runs under every configured Playwright project, and the project name
 * is part of every failure message so a browser-matrix failure is attributable.
 *
 * This is a synthetic sweep. The in-page API boundary is stubbed and every
 * identifier is an opaque test value; no patient record, credential or real
 * endpoint is contacted. The shell's signed-in role is fixed to `reception`; a
 * registered pattern that role cannot open renders the shell's denial screen
 * instead of the intended one, so those cells are marked `test.fixme` rather
 * than reporting a green scan of a substitute page. The remaining cells assert
 * that a real screen, not a loading/error/denial state panel, reached the DOM
 * before the axe scan.
 */

/** Axe rule groups that together make up the WCAG 2.2 AA gate for this sweep. */
const WCAG_22_AA_TAGS = [
  'wcag2a',
  'wcag2aa',
  'wcag21a',
  'wcag21aa',
  'wcag22aa',
] as const;

/** Widest viewport whose navigation hides the language control behind a menu. */
const COMPACT_MAX_WIDTH = 768;

/** Synthetic signed-in role used by the staff shell in this harness. */
const HARNESS_ROLE = 'reception' as const;

/**
 * Headings of substitute state surfaces, derived from the shared bilingual
 * labels so a translation change cannot silently disable this guard. A scan
 * that lands on one of these has not reached the route's own screen. The polite
 * empty state is excluded on purpose: it is an intended screen state.
 */
const SUBSTITUTE_HEADINGS: ReadonlySet<string> = new Set(
  Object.values(stateViewMessages).flatMap((message) => [
    message.loading,
    message.error,
    message.forbidden,
    message.sessionExpired,
    message.conflict,
  ]),
);

/** Opaque, synthetic identifiers for the four parameterised route patterns. */
const SYNTHETIC_IDS = {
  patient: 'patient-00000000-0000-0000-0000-000000000001',
  note: 'note-00000000-0000-0000-0000-000000000001',
  prescription: 'prescription-00000000-0000-0000-0000-000000000001',
} as const;

/** One layout band from decision 0004 (compact, medium and expanded bands). */
interface ViewportBand {
  readonly name: 'phone' | 'tablet' | 'desktop';
  readonly width: number;
  readonly height: number;
}

/** The three widths required by the story, each at its representative height. */
const VIEWPORT_BANDS: readonly ViewportBand[] = [
  { name: 'phone', width: 360, height: 740 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 720 },
];

/** A locale and the document direction its provider must apply (UX-01). */
interface LocaleCase {
  readonly locale: 'en' | 'ar';
  readonly direction: 'ltr' | 'rtl';
}

const LOCALES: readonly LocaleCase[] = [
  { locale: 'en', direction: 'ltr' },
  { locale: 'ar', direction: 'rtl' },
];

/** A concrete route to scan, with a stable short name for the test title. */
interface ScanTarget {
  readonly name: string;
  /** Concrete path with synthetic ids; the `*` catch-all is not a route. */
  readonly path: string;
  /** Registered pattern when the path carries parameters; otherwise the path. */
  readonly pattern?: string | undefined;
  /** True when a state view IS the intended screen for this target. */
  readonly isStateScreen?: boolean | undefined;
}

/** Every registered route from `src/app/routes.tsx` and the feature route modules. */
const SCAN_TARGETS: readonly ScanTarget[] = [
  { name: 'welcome', path: '/' },
  { name: 'design-preview', path: '/design-preview' },
  { name: 'schedule', path: '/schedule' },
  { name: 'schedule-book', path: '/schedule/book' },
  { name: 'patients', path: '/patients' },
  {
    name: 'patient-overview',
    path: `/patients/${SYNTHETIC_IDS.patient}`,
    pattern: '/patients/:id',
  },
  {
    name: 'patient-chart',
    path: `/patients/${SYNTHETIC_IDS.patient}/chart`,
    pattern: '/patients/:id/chart',
  },
  {
    name: 'patient-note',
    path: `/patients/${SYNTHETIC_IDS.patient}/notes/${SYNTHETIC_IDS.note}`,
    pattern: '/patients/:id/notes/:noteId',
  },
  { name: 'lab-results-entry', path: '/lab/results/entry' },
  { name: 'admin-facilities', path: '/admin/facilities' },
  { name: 'admin-staff', path: '/admin/staff' },
  { name: 'admin-config', path: '/admin/config' },
  { name: 'admin-audit', path: '/admin/audit' },
  { name: 'messages', path: '/messages' },
  { name: 'lab-release', path: '/lab/release' },
  { name: 'signed-out', path: '/signed-out' },
  { name: 'session-expired', path: '/session-expired', isStateScreen: true },
  { name: 'forbidden', path: '/forbidden', isStateScreen: true },
  { name: 'not-found', path: '/not-found' },
  { name: 'partner-board', path: '/operations/partner-board' },
  { name: 'results', path: '/results' },
  { name: 'critical-results', path: '/results/critical' },
  { name: 'lab-turnaround-board', path: '/lab/turnaround-board' },
  { name: 'lab-accessions', path: '/lab/accessions' },
  { name: 'radiology', path: '/radiology' },
  { name: 'pharmacy', path: '/pharmacy' },
  {
    name: 'pharmacy-item',
    path: `/pharmacy/${SYNTHETIC_IDS.prescription}`,
    pattern: '/pharmacy/:id',
  },
  { name: 'operations-exports', path: '/operations/exports' },
  { name: 'operations-imports', path: '/operations/imports' },
  { name: 'operations-queues', path: '/operations/queues' },
];

/** A shape-valid but content-free prescription for the pharmacy detail route. */
const SYNTHETIC_PRESCRIPTION = {
  id: SYNTHETIC_IDS.prescription,
  version: 1,
  patientId: 'synthetic-dispensing-reference',
  medication: 'Synthetic medication',
  strength: '10 mg',
  dose: '1 tablet',
  route: 'oral',
  frequency: 'daily',
  quantity: 10,
  state: 'submitted',
  submissionId: 'synthetic-submission-0001',
  prescriberSubject: 'synthetic.prescriber',
  destinationPharmacy: 'Synthetic Pharmacy',
  createdAt: '2026-03-08T06:30:00Z',
} as const;

/** A shape-valid status projection with an empty append-only fact trail. */
const SYNTHETIC_PHARMACY_STATUS = {
  organizationId: '11111111-1111-1111-1111-111111111111',
  prescriptionId: SYNTHETIC_IDS.prescription,
  version: 1,
  accepted: false,
  dispensed: false,
  events: [],
} as const;

/**
 * Build the empty synthetic payload for one versioned API request. Each shape
 * matches the real wire contract of a reachable API-backed screen: a cursor page
 * with `items` for appointments, a bare list for patient search, and the
 * prescription/status pair for pharmacy detail. Other reachable screens use
 * local fixtures and the remaining endpoints belong to role-denied pages. No
 * payload carries a patient record, credential or real identifier (WEB-10).
 *
 * @param pathname requested API path
 * @returns a deterministic, empty JSON-serialisable body
 */
function syntheticBody(pathname: string): unknown {
  if (pathname.endsWith('/api/v1/appointments') || pathname.endsWith('/api/v1/pharmacy/inbox')) {
    return { items: [], nextCursor: null };
  }
  if (pathname.endsWith('/api/v1/patients')) {
    return [];
  }
  if (pathname.startsWith('/api/v1/pharmacy/inbox/')) {
    return SYNTHETIC_PRESCRIPTION;
  }
  if (pathname.startsWith('/api/v1/pharmacy/status/')) {
    return SYNTHETIC_PHARMACY_STATUS;
  }
  return {};
}

/**
 * Answer every versioned API request from this page with deterministic, empty
 * synthetic JSON so a loader reaches its `ready` state on the real transport
 * path without a backend or a network timeout (WEB-10).
 *
 * @param route intercepted API request
 */
async function answerSyntheticApi(route: Route): Promise<void> {
  const url = new URL(route.request().url());
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(syntheticBody(url.pathname)),
  });
}

/**
 * Switch the document to Arabic through the supported in-page control. The
 * compact band hides that control behind the user menu; the medium and expanded
 * bands expose it in the header, so the locator must follow the width band
 * (WEB-07, UX-01).
 *
 * @param page browser page already showing the English document
 * @param bandWidth measured viewport width that decides the control's location
 */
async function switchToArabic(page: Page, bandWidth: number): Promise<void> {
  if (bandWidth < COMPACT_MAX_WIDTH) {
    await page.getByRole('button', { name: 'User menu' }).click();
  }
  await page.getByRole('button', { name: 'Language' }).click();
}

for (const target of SCAN_TARGETS) {
  const pattern = target.pattern ?? target.path;
  const reachable = canAccessRoute(HARNESS_ROLE, pattern);
  for (const band of VIEWPORT_BANDS) {
    for (const localeCase of LOCALES) {
      const title = `WCAG 2.2 AA: ${target.name} ${target.path} at ${band.width}px (${localeCase.locale})`;
      const runScan = async ({ page }: { page: Page }): Promise<void> => {
        const project = test.info().project.name;
        const context = `route ${target.path} | ${band.name} ${band.width}px | ${localeCase.locale}-${localeCase.direction} | project ${project}`;
        await page.route('**/api/v1/**', answerSyntheticApi);

        await page.setViewportSize({ width: band.width, height: band.height });
        await page.goto(target.path);

        await expect(page.locator('html'), context).toHaveAttribute('lang', 'en');
        await expect(page.locator('html'), context).toHaveAttribute('dir', 'ltr');
        if (localeCase.locale === 'ar') {
          await switchToArabic(page, band.width);
          await expect(page.locator('html'), context).toHaveAttribute('lang', 'ar');
          await expect(page.locator('html'), context).toHaveAttribute('dir', 'rtl');
        }

        const pathname = await page.evaluate(() => window.location.pathname);
        expect(pathname, `rendered path at ${context}`).toBe(target.path);
        const measuredWidth = await page.evaluate(() => window.innerWidth);
        expect(measuredWidth, `measured width at ${context}`).toBe(band.width);

        await expect(page.locator('main#main'), context).toBeVisible();
        // System notice pages expose an h1 and every state panel an h2, so
        // accept either as the screen title. For ordinary routes, wait until a
        // transient loading or error heading has been replaced before scanning;
        // a state panel is never a result for a different route.
        const title = page.locator('main#main h1, main#main h2').first();
        await expect(title, context).toBeVisible();
        if (target.isStateScreen !== true) {
          const substituteHeadings = [...SUBSTITUTE_HEADINGS];
          await expect
            .poll(
              async () => {
                const isStateHeading = await title.evaluate(
                  (element, headings) =>
                    element.tagName === 'H2' &&
                    headings.includes((element.textContent ?? '').trim()),
                  substituteHeadings,
                );
                return !isStateHeading;
              },
              { message: `settled screen at ${context}` },
            )
            .toBe(true);
        }

        const results = await new AxeBuilder({ page }).withTags([...WCAG_22_AA_TAGS]).analyze();
        const blocking = results.violations.filter(
          (violation) => violation.impact === 'serious' || violation.impact === 'critical',
        );
        expect(
          blocking.map((violation) => `${violation.id}: ${violation.help}`),
          `serious/critical WCAG 2.2 AA violations at ${context}`,
        ).toEqual([]);
      };

      if (reachable) {
        test(title, runScan);
      } else {
        // The shell denies this pattern to the synthetic role, so the only
        // reachable screen is the denial page. The reason and concrete path are
        // in the title, as the story's fixme policy requires, and the same cells
        // are listed in the task notes under "Why this is not finished".
        test.fixme(
          `${title} [fixme: shell role ${HARNESS_ROLE} cannot open ${pattern}; route ${target.path}]`,
          runScan,
        );
      }
    }
  }
}
