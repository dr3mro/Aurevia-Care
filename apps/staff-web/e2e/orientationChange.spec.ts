import { expect, test } from '@playwright/test';
import type { Page, ViewportSize } from '@playwright/test';

/**
 * Rotation checks for the staff web against the real Vite dev harness
 * (WEB-07, UX-01, WEB-09).
 *
 * A phone or tablet that changes orientation may reflow its layout, but it must
 * keep the current route, the focused control and any unsaved form value, and it
 * must never swap the application for the plain unsupported-browser notice.
 * Every route here is data-free and renders the real application shell; only
 * synthetic identifiers and note text appear. No application endpoint,
 * credential or patient record is contacted.
 *
 * The harness runs one test per route, and Playwright runs that test in every
 * configured project. Each project therefore rotates from its own starting
 * viewport to the counterpart in the device matrix, so phone, tablet and
 * desktop classes are all covered without changing the Playwright config.
 */

/** Exact English heading rendered by the unsupported-browser page (WEB-07). */
const unsupportedHeading = 'Unsupported browser or screen size';

/** Synthetic organization selector mounted in the shell on every route. */
const organizationSelector = '#active-organization';

/** Synthetic note field mounted only by the development preview page. */
const noteSelector = '#shift-note';

/** Synthetic unsaved text typed into the development preview form. */
const unsavedNote = 'SYN-0001 SYN-FAC-01 unsaved shift note';

/** One concrete, data-free route that the development harness can render. */
interface OrientationRoute {
  /** Human-readable name included in every failure message. */
  readonly label: string;
  /** Concrete path passed to page.goto. */
  readonly path: string;
  /** True when the route exposes the synthetic note field. */
  readonly hasNoteField: boolean;
}

/**
 * Routes used here are the ones that render a stable shell in the dev harness.
 * Backend-backed clinical screens need an API stub harness of their own and are
 * deliberately outside this rotation slice; no clinical data is involved here.
 */
const routes: readonly OrientationRoute[] = [
  { label: 'welcome /', path: '/', hasNoteField: false },
  { label: 'design preview /design-preview', path: '/design-preview', hasNoteField: true },
  { label: 'signed out /signed-out', path: '/signed-out', hasNoteField: false },
  { label: 'session expired /session-expired', path: '/session-expired', hasNoteField: false },
  { label: 'forbidden /forbidden', path: '/forbidden', hasNoteField: false },
  { label: 'not found /not-found', path: '/not-found', hasNoteField: false },
  { label: 'catch-all /synthetic-missing-page', path: '/synthetic-missing-page', hasNoteField: false },
];

/**
 * Rotation target for each starting viewport in the device matrix. Widths are
 * unique across the configured projects, so cross-browser variants of a width
 * share its target. The unknown-width failure is deliberate: a config change
 * must not silently turn a rotation check into a no-op.
 */
const rotationTargets: Readonly<Record<string, ViewportSize>> = {
  '360x740': { width: 844, height: 390 },
  '390x844': { width: 844, height: 390 },
  '844x390': { width: 390, height: 844 },
  '768x1024': { width: 1024, height: 768 },
  '1024x768': { width: 768, height: 1024 },
  '820x1180': { width: 1180, height: 820 },
  '1180x820': { width: 820, height: 1180 },
  '1280x720': { width: 1920, height: 1080 },
  '1920x1080': { width: 1280, height: 720 },
};

/** Id of the element that currently owns browser focus. */
async function focusedId(page: Page): Promise<string> {
  return page.evaluate(() => document.activeElement?.id ?? '');
}

/** Live value of one form control, read from the mounted element. */
async function controlValue(page: Page, selector: string): Promise<string> {
  return page.locator(selector).inputValue();
}

/**
 * Assert that a rotation from this project's starting viewport is defined, and
 * return its target. Failing loudly keeps a stale device matrix from producing
 * a test that never actually rotates.
 */
function targetFor(project: string, key: string): ViewportSize {
  const target = rotationTargets[key];
  if (target === undefined) {
    throw new Error(`${project}: no rotation target is defined for viewport ${key}`);
  }
  return target;
}

for (const route of routes) {
  test(`rotation preserves route, focus and unsaved work on ${route.label}`, async (
    { page },
    testInfo,
  ) => {
    const scope = `${route.label} / ${testInfo.project.name}`;
    const startingViewport = page.viewportSize();
    if (startingViewport === null) {
      throw new Error(`${scope}: the project has no starting viewport`);
    }
    const key = `${startingViewport.width}x${startingViewport.height}`;
    const target = targetFor(scope, key);

    await page.goto(route.path);
    // A rendered shell proves this is the application, not a blank document or
    // the unsupported page, so the negative heading check cannot pass vacuously.
    await expect(page.locator('main#main'), `${scope}: application main landmark`).toBeVisible();
    await expect(
      page.locator(organizationSelector),
      `${scope}: organization control in the shell`,
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { name: unsupportedHeading, exact: true }),
      `${scope}: no unsupported notice before rotation`,
    ).toHaveCount(0);

    const pathnameBefore = new URL(page.url()).pathname;
    expect(pathnameBefore, `${scope}: starting pathname`).toBe(route.path);

    // Change a shell control so an unsaved selection exists on every route.
    const organizationBefore = await controlValue(page, organizationSelector);
    await page.selectOption(organizationSelector, { index: 1 });
    const organizationSelected = await controlValue(page, organizationSelector);
    expect(organizationSelected, `${scope}: organization selection changed`).not.toBe(
      organizationBefore,
    );

    // The preview also owns a controlled text field: type a synthetic note and
    // leave focus there at rotation time. Elsewhere focus stays on the shell
    // control, which is stable across every responsive navigation variant.
    let noteBefore = '';
    if (route.hasNoteField) {
      await page.locator(noteSelector).fill(unsavedNote);
      await page.locator(noteSelector).focus();
      noteBefore = await controlValue(page, noteSelector);
      expect(noteBefore, `${scope}: typed note recorded`).toBe(unsavedNote);
    } else {
      await page.locator(organizationSelector).focus();
    }
    const focusedIdBefore = await focusedId(page);
    const expectedFocus = route.hasNoteField ? 'shift-note' : 'active-organization';
    expect(focusedIdBefore, `${scope}: focused control before rotation`).toBe(expectedFocus);

    await page.setViewportSize(target);
    // Confirm the browser applied the new size before measuring the invariants;
    // auto-retrying assertions then let the resize re-render settle.
    await expect.poll(
      () => page.evaluate(() => window.innerWidth),
      `${scope}: rotated viewport width`,
    ).toBe(target.width);
    await expect.poll(
      () => page.evaluate(() => window.innerHeight),
      `${scope}: rotated viewport height`,
    ).toBe(target.height);

    await expect.poll(
      () => new URL(page.url()).pathname,
      `${scope}: pathname after rotation`,
    ).toBe(pathnameBefore);
    await expect.poll(
      () => focusedId(page),
      `${scope}: focused element identity after rotation`,
    ).toBe(focusedIdBefore);
    await expect(
      page.locator(route.hasNoteField ? noteSelector : organizationSelector),
      `${scope}: same control still focused after rotation`,
    ).toBeFocused();
    await expect.poll(
      () => controlValue(page, organizationSelector),
      `${scope}: unsaved organization selection after rotation`,
    ).toBe(organizationSelected);
    if (route.hasNoteField) {
      await expect.poll(
        () => controlValue(page, noteSelector),
        `${scope}: typed unsaved note after rotation`,
      ).toBe(noteBefore);
    }
    await expect(
      page.getByRole('heading', { name: unsupportedHeading, exact: true }),
      `${scope}: no unsupported notice after rotation`,
    ).toHaveCount(0);
    await expect(page.locator('main#main'), `${scope}: application still rendered`).toBeVisible();
  });
}
