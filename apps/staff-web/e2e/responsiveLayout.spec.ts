import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { BREAKPOINT_WIDTHS, breakpointFor, type Breakpoint } from '../src/theme/breakpoints';

/**
 * Responsive layout journeys for the staff web (WEB-07, UX-01).
 *
 * Every registered route is opened in every Playwright project from
 * `playwright.config.ts`. The shell is reached through the real router and the
 * production `AppShell`; no harness or stub replaces it. At each width the
 * journey asserts that the document does not scroll on the inline axis, that
 * the shell reports the band derived from the measured viewport, that
 * navigation takes the form the band requires, and that the main and heading
 * landmarks are present. A separate journey exercises the language toggle to
 * keep right-to-left evidence in the same matrix.
 *
 * All identifiers are synthetic. No patient data, credential or real endpoint
 * appears here. Width changes presentation only; it never changes what the
 * signed-in role may see or do.
 */

/** One registered route pattern and a synthetic path that matches it. */
interface ResponsiveRoute {
  /** The registered pattern, named verbatim in failure messages. */
  readonly pattern: string;
  /** A concrete path with `:id` and `:noteId` replaced by synthetic IDs. */
  readonly path: string;
}

/**
 * Every route the app registers: the app-owned welcome, development preview
 * and catch-all, plus one case per pattern declared by a feature `routes.ts`.
 * The catch-all uses a path no registered pattern matches. Navigation-only
 * patterns without a registered screen resolve through that same catch-all and
 * need no separate case.
 */
const responsiveRoutes: readonly ResponsiveRoute[] = [
  { pattern: '/', path: '/' },
  { pattern: '/design-preview', path: '/design-preview' },
  { pattern: '*', path: '/no-such-route' },
  { pattern: '/admin/facilities', path: '/admin/facilities' },
  { pattern: '/admin/staff', path: '/admin/staff' },
  { pattern: '/admin/config', path: '/admin/config' },
  { pattern: '/admin/audit', path: '/admin/audit' },
  { pattern: '/patients/:id/chart', path: '/patients/SYN-0001/chart' },
  { pattern: '/patients/:id/notes/:noteId', path: '/patients/SYN-0001/notes/SYN-0002' },
  { pattern: '/operations/exports', path: '/operations/exports' },
  { pattern: '/operations/imports', path: '/operations/imports' },
  { pattern: '/lab/turnaround-board', path: '/lab/turnaround-board' },
  { pattern: '/lab/accessions', path: '/lab/accessions' },
  { pattern: '/lab/release', path: '/lab/release' },
  { pattern: '/lab/results/entry', path: '/lab/results/entry' },
  { pattern: '/messages', path: '/messages' },
  { pattern: '/operations/queues', path: '/operations/queues' },
  { pattern: '/operations/partner-board', path: '/operations/partner-board' },
  { pattern: '/patients', path: '/patients' },
  { pattern: '/patients/:id', path: '/patients/SYN-0001' },
  { pattern: '/pharmacy', path: '/pharmacy' },
  { pattern: '/pharmacy/:id', path: '/pharmacy/SYN-0001' },
  { pattern: '/radiology', path: '/radiology' },
  { pattern: '/results', path: '/results' },
  { pattern: '/results/critical', path: '/results/critical' },
  { pattern: '/schedule', path: '/schedule' },
  { pattern: '/schedule/book', path: '/schedule/book' },
  { pattern: '/signed-out', path: '/signed-out' },
  { pattern: '/session-expired', path: '/session-expired' },
  { pattern: '/forbidden', path: '/forbidden' },
  { pattern: '/not-found', path: '/not-found' },
];

/** Read the layout viewport width that drives the shell's media queries. */
async function measuredWidth(page: Page): Promise<number> {
  return page.evaluate(() => window.innerWidth);
}

/**
 * Assert the document has no horizontal overflow. `innerWidth` is the layout
 * viewport; under mobile emulation the meta viewport maps it to device width.
 */
async function expectNoHorizontalScroll(page: Page, context: string): Promise<void> {
  const widths = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }));
  expect(
    widths.scrollWidth <= widths.innerWidth,
    `${context}: horizontal page scroll — scrollWidth=${widths.scrollWidth}, innerWidth=${widths.innerWidth}`,
  ).toBe(true);
}

/** Assert the shell's reported band matches the one derived from the width. */
async function expectBand(page: Page, context: string, band: Breakpoint): Promise<void> {
  const actual = await page
    .locator('.app-shell[data-breakpoint]')
    .getAttribute('data-breakpoint');
  expect(
    actual,
    `${context}: expected ${band} band (thresholds ${BREAKPOINT_WIDTHS.medium}/${BREAKPOINT_WIDTHS.expanded}), got ${String(actual)}`,
  ).toBe(band);
}

/**
 * Assert the navigation form the band requires: a drawer on compact, an icon
 * rail on medium and the labelled rail on expanded. The drawer button is named
 * by its `aria-controls` target so the assertion follows the rendered shell.
 */
async function expectNavigation(page: Page, context: string, band: Breakpoint): Promise<void> {
  const drawerButton = page.locator('button[aria-controls="staff-navigation-drawer"]');
  const rail = page.locator('.app-shell__nav');
  if (band === 'compact') {
    await expect(drawerButton, `${context}: compact band missing drawer button`).toHaveCount(1);
    await expect(rail, `${context}: compact band must not render a rail`).toHaveCount(0);
    return;
  }
  const railName = band === 'medium' ? 'icon' : 'full';
  await expect(
    page.locator(`.app-shell__nav[data-rail="${railName}"]`),
    `${context}: ${band} band missing ${railName} rail`,
  ).toHaveCount(1);
  await expect(
    drawerButton,
    `${context}: ${band} band must not render a drawer button`,
  ).toHaveCount(0);
}

/** Assert the persistent shell and route landmarks are present and visible. */
async function expectLandmarks(page: Page, context: string): Promise<void> {
  await expect(page.locator('main#main'), `${context}: main landmark missing`).toBeVisible();
  await expect(
    page.locator('header[role="banner"] > h1'),
    `${context}: shell banner heading missing`,
  ).toBeVisible();
  await expect(
    page.locator('main#main h1, main#main h2').first(),
    `${context}: route page has no visible heading`,
  ).toBeVisible();
}

/** Wait for the production shell to mount before measuring the document. */
async function expectShellMounted(page: Page, context: string): Promise<void> {
  await expect(
    page.locator('.app-shell[data-breakpoint]'),
    `${context}: application shell did not mount`,
  ).toBeVisible();
}

/**
 * Wait for the lazily imported route page, not the Suspense fallback. A
 * heading inside `main` proves a real screen mounted, and the network settle
 * waits out an API loading skeleton so the width is measured on the settled
 * screen rather than on its transient placeholder. This app has no polling or
 * long-lived request, so the settle has a finite end.
 */
async function expectPageReady(page: Page, context: string): Promise<void> {
  await expectShellMounted(page, context);
  await expect(
    page.locator('main#main h1, main#main h2').first(),
    `${context}: route page did not render a heading (Suspense fallback still mounted)`,
  ).toBeVisible();
  await page.waitForLoadState('networkidle');
}

/**
 * Apply the full WEB-07/UX-01 shell contract to the current page. Assertions
 * run in order: overflow, band, navigation form, main landmark, heading.
 */
async function expectResponsiveShell(
  page: Page,
  project: string,
  pattern: string,
  path: string,
): Promise<void> {
  const context = `${pattern} -> ${path} on project ${project}`;
  await expectPageReady(page, context);
  const band = breakpointFor(await measuredWidth(page));
  await expectNoHorizontalScroll(page, context);
  await expectBand(page, context, band);
  await expectNavigation(page, context, band);
  await expectLandmarks(page, context);
}

for (const route of responsiveRoutes) {
  test(`responsive layout ${route.pattern} -> ${route.path}`, async ({ page }, testInfo) => {
    await page.goto(route.path);
    await expectResponsiveShell(page, testInfo.project.name, route.pattern, route.path);
  });
}

test('language toggle keeps the responsive shell intact in right-to-left', async ({
  page,
}, testInfo) => {
  await page.goto('/');
  const context = `RTL route '/' -> '/' on project ${testInfo.project.name}`;
  await expectPageReady(page, context);
  const band = breakpointFor(await measuredWidth(page));

  // The compact band keeps the language control inside the user menu; wider
  // bands show it directly in the header.
  if (band === 'compact') {
    await page.getByRole('button', { name: 'User menu' }).click();
  }
  await page.getByRole('button', { name: 'Language' }).click();
  await expect(page.locator('html'), `${context}: language toggle did not set RTL`).toHaveAttribute(
    'dir',
    'rtl',
  );

  await expectNoHorizontalScroll(page, `${context} at RTL`);
  await expectBand(page, `${context} at RTL`, band);
  await expectNavigation(page, `${context} at RTL`, band);
  await expectLandmarks(page, `${context} at RTL`);
});
