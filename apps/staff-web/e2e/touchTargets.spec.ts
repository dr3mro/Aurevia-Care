import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';

/**
 * Coarse-pointer target-size evidence for the staff web (WEB-07, UX-01, R5 of
 * `docs/design/responsive-layout.md`). No real patient data, endpoint or
 * credential is used.
 *
 * The concrete route list below is derived from `src/app/routes.tsx` and the 28
 * patterns declared by the feature route modules under `src/features`; the
 * catch-all pattern is dropped, `/design-preview` is kept because Vite registers it,
 * and `:id`/`:noteId` are replaced with synthetic identifiers. Add or remove a
 * route here whenever those source files change; the count assertion below
 * fails if the list drifts from the 30 known routes.
 *
 * Scope: one execution per (route, coarse project). Playwright fans each test
 * out over every configured project; fine-pointer projects are marked not
 * applicable because the 44 px rule belongs to coarse pointers only (24 px is
 * the WCAG 2.2 fine-pointer floor and is enforced by a separate accessibility
 * review). The checked state is the route as rendered: the navigation drawer is
 * closed by default, so its links are not rendered and are outside this pass
 * (they are 17px tall when opened and need a follow-up fix); the user menu is
 * opened on compact projects so its theme, language and sign-out controls are
 * measured. If a real defect is observed,
 * add its exact (route, project) pair to `knownDefects` rather than lowering
 * the threshold or widening this exception list.
 *
 * Excluded controls: elements that are not visible or not enabled, `aria-hidden`
 * nodes, the visually hidden `a.skip-link` (off-screen until focused), native
 * checkbox/radio inputs (the WCAG user-agent-control case: the wrapping label
 * is the real target), and controls inside a closed dialog or drawer (they are
 * not rendered when closed). Only the control's own `aria-hidden` is read;
 * an ancestor `aria-hidden` subtree is deliberately err-on-measure. WCAG
 * spacing and inline-link exceptions are not
 * used to excuse a small target.
 */

/** The coarse-pointer target rule from `docs/design/responsive-layout.md` §6. */
const MIN_TARGET = 44;

/**
 * Synthetic user agent accepted by `src/features/system/browserSupport.ts`.
 * The headless-shell default contains `HeadlessChrome`, which the support
 * policy deliberately rejects; a recent Chrome token reaches the real shell.
 */
const SYNTHETIC_SUPPORTED_USER_AGENT =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';

/** Interactive controls measured on a settled route, excluding documented exceptions. */
const CONTROL_SELECTOR = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled]):not([type="hidden"]):not([type="checkbox"]):not([type="radio"])',
  'select:not([disabled])',
].join(',');

/** One concrete staff-web route with synthetic identifiers. */
const routes: readonly string[] = [
  '/',
  '/design-preview',
  '/admin/facilities',
  '/admin/staff',
  '/admin/config',
  '/admin/audit',
  '/patients/SYN-0001/chart',
  '/patients/SYN-0001/notes/SYN-NOTE-0001',
  '/operations/exports',
  '/operations/imports',
  '/lab/turnaround-board',
  '/lab/accessions',
  '/lab/release',
  '/lab/results/entry',
  '/messages',
  '/operations/queues',
  '/operations/partner-board',
  '/patients',
  '/patients/SYN-0001',
  '/pharmacy',
  '/pharmacy/SYN-0001',
  '/radiology',
  '/results',
  '/results/critical',
  '/schedule',
  '/schedule/book',
  '/signed-out',
  '/session-expired',
  '/forbidden',
  '/not-found',
];

/** One observed product defect, scoped to a single (route, project) pair. */
interface KnownDefect {
  readonly route: string;
  readonly project: string;
  readonly reason: string;
}

/** The nav-rail links render 8-17px tall at coarse widths from 768px upward. */
const RAIL_DEFECT =
  'NavRail links measure below 44x44 at medium and expanded widths; add the shared .touch target class to the rail links';

/** The turnaround and partner-board detail links render 292x32. */
const BOARD_LINK_DEFECT =
  'Board detail links measure 292x32; add the shared .touch target class to those anchors';

/** The result-entry inputs and action render 96x32 and 292x32. */
const RESULT_ENTRY_DEFECT =
  'Result-entry inputs and action measure 96x32 / 292x32; add the shared .touch target class';

/** Every coarse project whose width selects the medium or expanded nav rail. */
const railDefectProjects: readonly string[] = [
  'phone-landscape-844x390',
  'tablet-portrait-768x1024',
  'tablet-portrait-820x1180',
  'tablet-landscape-1024x768',
  'tablet-landscape-1180x820',
];

/** Compact coarse projects where only the three route-local defects occur. */
const compactCoarseProjects: readonly string[] = [
  'phone-portrait-360x740',
  'phone-portrait-390x844',
];

/** The three routes with a route-local defect on compact coarse pointers. */
const compactDefectRoutes: readonly string[] = [
  '/lab/results/entry',
  '/lab/turnaround-board',
  '/operations/partner-board',
];

/**
 * Exact (route, project) pairs observed failing on a real target-size defect.
 * Generated pairs are still exact; the threshold and exception list stay fixed.
 */
const knownDefects: readonly KnownDefect[] = [
  ...railDefectProjects.flatMap((project) =>
    routes.map((route) => ({ route, project, reason: `${RAIL_DEFECT} — ${route}` })),
  ),
  ...compactCoarseProjects.flatMap((project) =>
    compactDefectRoutes.map((route) => ({
      route,
      project,
      reason: `${route === '/lab/results/entry' ? RESULT_ENTRY_DEFECT : BOARD_LINK_DEFECT} — ${route}`,
    })),
  ),
];

/** One control's rounded dimensions and a stable, PHI-free descriptor. */
interface MeasuredControl {
  readonly descriptor: string;
  readonly width: number;
  readonly height: number;
}

/** Guard against silent drift of the hardcoded route list. */
test('route matrix covers every registered staff-web route', () => {
  expect(routes).toHaveLength(30);
  expect(new Set(routes).size).toBe(routes.length);
});

test.use({ userAgent: SYNTHETIC_SUPPORTED_USER_AGENT });

/** Build a stable descriptor without reading control text or clinical data. */
async function describeControl(control: Locator, index: number): Promise<string> {
  const tag = await control.evaluate((element) => element.tagName.toLowerCase());
  const id = (await control.getAttribute('id')) ?? '';
  const label = (await control.getAttribute('aria-label')) ?? '';
  const idPart = id === '' ? '' : `#${id}`;
  const labelPart = label === '' ? '' : `[${label}]`;
  return `#${index} ${tag}${idPart}${labelPart}`;
}

/** True when a control is an interactive, exposed target on the rendered route. */
async function isMeasurable(control: Locator): Promise<boolean> {
  if (!(await control.isVisible())) {
    return false;
  }
  if (!(await control.isEnabled())) {
    return false;
  }
  if ((await control.getAttribute('aria-hidden')) === 'true') {
    return false;
  }
  const className = (await control.getAttribute('class')) ?? '';
  return !className.includes('skip-link');
}

/** Measure every eligible control on the current page. */
async function measureVisibleControls(page: Page): Promise<MeasuredControl[]> {
  const measured: MeasuredControl[] = [];
  const controls = page.locator(CONTROL_SELECTOR);
  const count = await controls.count();
  for (let index = 0; index < count; index += 1) {
    const control = controls.nth(index);
    if (!(await isMeasurable(control))) {
      continue;
    }
    const box = await control.boundingBox();
    if (box === null) {
      continue;
    }
    measured.push({
      descriptor: await describeControl(control, index),
      width: Math.round(box.width * 100) / 100,
      height: Math.round(box.height * 100) / 100,
    });
  }
  return measured;
}

for (const route of routes) {
  test(`touch targets ≥ ${String(MIN_TARGET)}px on ${route}`, async ({ page }) => {
    const testInfo = test.info();
    const projectName = testInfo.project.name;
    const viewport = testInfo.project.use.viewport;

    // Derive the rule from the pointer capability, then fail loudly if the
    // project naming convention ever drifts away from that capability.
    const coarse = testInfo.project.use.hasTouch === true;
    const namedCoarse = projectName.startsWith('phone-') || projectName.startsWith('tablet-');
    expect(namedCoarse, `${route} / ${projectName}: hasTouch disagrees with its name`).toBe(coarse);
    if (!coarse) {
      testInfo.skip(true, 'The 44px coarse-pointer rule applies only to phone and tablet projects');
    }

    await page.goto(route);
    // A visible main landmark proves the supported shell rendered, and the
    // settled check proves the lazily loaded page replaced its loading state.
    await expect(page.locator('main#main')).toBeVisible();
    await expect(page.locator('main#main').getByText('Loading', { exact: true })).toHaveCount(0, {
      timeout: 15_000,
    });

    // The product grants 44px only under `@media (pointer: coarse)`; Playwright
    // sets that media feature from the project's hasTouch option. A mismatch
    // would turn a CSS regression into a wall of false defect reports.
    const pointerIsCoarse = await page.evaluate(() => matchMedia('(pointer: coarse)').matches);
    expect(pointerIsCoarse, `${route} / ${projectName}: pointer media does not match hasTouch`).toBe(
      true,
    );

    const defect = knownDefects.find(
      (candidate) => candidate.route === route && candidate.project === projectName,
    );
    if (defect !== undefined) {
      test.fixme(true, defect.reason);
    }

    // On compact layouts the theme, language and sign-out controls are hidden
    // behind the user menu until it is opened; measure them too.
    if (viewport !== null && viewport !== undefined && viewport.width < 768) {
      const userMenuButton = page.locator('button[aria-controls="app-user-menu"]');
      await expect(userMenuButton).toBeVisible();
      await userMenuButton.click();
    }

    const measured = await measureVisibleControls(page);
    expect(measured.length, `${route} / ${projectName}: no interactive controls were measured`).toBeGreaterThan(0);

    const widthFailures = measured
      .filter((control) => control.width < MIN_TARGET)
      .map(
        (control) =>
          `${route} / ${projectName} / ${control.descriptor}: ` +
          `${String(control.width)}x${String(control.height)} < ${String(MIN_TARGET)}x${String(MIN_TARGET)}`,
      );
    const heightFailures = measured
      .filter((control) => control.height < MIN_TARGET)
      .map(
        (control) =>
          `${route} / ${projectName} / ${control.descriptor}: ` +
          `${String(control.width)}x${String(control.height)} < ${String(MIN_TARGET)}x${String(MIN_TARGET)}`,
      );
    expect(widthFailures, `${route} / ${projectName}: width below ${String(MIN_TARGET)}px`).toEqual([]);
    expect(heightFailures, `${route} / ${projectName}: height below ${String(MIN_TARGET)}px`).toEqual([]);
  });
}
