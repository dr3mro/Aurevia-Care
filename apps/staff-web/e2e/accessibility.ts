import AxeBuilder from '@axe-core/playwright';
import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * Shared WCAG gate for the synthetic browser journeys (WEB-10, UX-01).
 *
 * The serious and critical filter is the one the smoke suite uses: moderate and
 * minor findings belong to the full accessibility review, while a finding that
 * can block a clinical task fails the journey. The filter is deliberately copied
 * rather than widened, so a new screen cannot quietly relax the gate. Axe is
 * injected by `AxeBuilder`; no page script or external service is contacted.
 *
 * @param page browser page whose current document is scanned
 * @throws an assertion error listing every blocking rule id and help text
 */
export async function expectNoBlockingViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  expect(blocking.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([]);
}
