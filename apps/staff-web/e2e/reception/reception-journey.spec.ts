import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { installSyntheticReceptionApi } from './synthetic-reception-api';
import type { SyntheticReceptionApi } from './synthetic-reception-api';

/**
 * Synthetic reception journey: register a patient, book a visit and check the
 * patient in (WEB-10, CLN-01, IAM-03). The real patients and scheduling pages
 * are mounted through a dev-only harness with their real providers; only the
 * `/api/v1/patients` and `/api/v1/appointments/**` boundaries are stubbed. The
 * journey runs in English and Arabic RTL and scans each completed locale.
 */

/** Words the interface shows for one locale, asserted verbatim in the browser. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  register: string;
  registerTitle: string;
  registerSubmit: string;
  registeredPrefix: string;
  bookingForm: string;
  book: string;
  checkIn: string;
  booked: string;
  checkedIn: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  register: 'Register',
  registerTitle: 'Register patient',
  registerSubmit: 'Save registration',
  registeredPrefix: 'Registered',
  bookingForm: 'Book an appointment',
  book: 'Book',
  checkIn: 'Check in',
  booked: 'Booked',
  checkedIn: 'Checked in',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  register: 'تسجيل',
  registerTitle: 'تسجيل مريض',
  registerSubmit: 'حفظ التسجيل',
  registeredPrefix: 'تم تسجيل',
  bookingForm: 'حجز موعد',
  book: 'حجز',
  checkIn: 'تسجيل الوصول',
  booked: 'محجوز',
  checkedIn: 'تم تسجيل الوصول',
};

/** The synthetic identity typed into the registration dialog in both locales. */
const syntheticName = 'Synthetic New Patient';

/** Drive one complete reception journey in the chosen locale. */
async function runJourney(page: Page, api: SyntheticReceptionApi, words: JourneyWords): Promise<void> {
  await page.goto('/e2e/reception/reception-harness.html');
  await expect(page.getByTestId('reception-harness')).toBeVisible();

  if (words.locale === 'ar') {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);

  await test.step('register a patient through the real registration page', async () => {
    await page.getByRole('button', { name: words.register, exact: true }).click();
    const dialog = page.getByRole('dialog', { name: words.registerTitle });
    await expect(dialog).toBeVisible();
    await dialog.locator('#patient-displayName').fill(syntheticName);
    await dialog.locator('#patient-mrn').fill('MRN-9001');
    await dialog.locator('#patient-issuer').fill('SYNTHETIC');
    await dialog.locator('#patient-dob').fill('1992-08-03');
    await dialog.locator('#patient-language').fill('en');
    await dialog.getByRole('button', { name: words.registerSubmit }).click();

    await expect(page.getByTestId('patients-registration-status')).toContainText(
      words.registeredPrefix,
    );
    await expect(page.getByTestId('patients-registration-status')).toContainText(syntheticName);
    await expect.poll(() => api.calls).toContain('POST /api/v1/patients');
  });

  const booking = page.getByRole('form', { name: words.bookingForm });

  await test.step('an incomplete booking sends no command', async () => {
    await booking.getByRole('button', { name: words.book, exact: true }).click();
    expect(api.calls).not.toContain('POST /api/v1/appointments');
    await expect(page.getByTestId('scheduling-status')).toBeEmpty();
  });

  await test.step('book a visit for the registered patient', async () => {
    await booking.locator('#booking-facility').fill('MAIN');
    await booking.locator('#booking-patient').fill('patient-book');
    await booking.locator('#booking-visit').fill('Synthetic follow-up');
    await booking.locator('#booking-resource').fill('clinician-7');
    await booking.locator('#booking-start').fill('2026-03-09T07:00:00Z');
    await booking.getByRole('button', { name: words.book, exact: true }).click();

    await expect.poll(() => api.calls).toContain('POST /api/v1/appointments');
    // The status line is optimistic and is cleared by the reload; the reloaded
    // row is the durable proof that the booking was written.
    const bookedRow = page.locator('tr').filter({ hasText: 'patient-book' });
    await expect(bookedRow).toContainText(words.booked);
    await expect(bookedRow.getByRole('button', { name: words.checkIn })).toBeEnabled();
  });

  await test.step('check the booked patient in from the worklist', async () => {
    const seededRow = page.locator('tr').filter({ hasText: 'patient-seed' });
    await expect(seededRow).toBeVisible();
    await seededRow.getByRole('button', { name: words.checkIn }).click();

    await expect
      .poll(() =>
        api.calls.some(
          (call) => call.startsWith('POST /api/v1/appointments/') && call.endsWith('/check-in'),
        ),
      )
      .toBe(true);
    // The reloaded row is the durable proof that the command reached the
    // booking API and changed the visit; the status line is only optimistic.
    await expect(seededRow).toContainText(words.checkedIn);
    await expect(seededRow.getByRole('button', { name: words.checkIn })).toBeDisabled();
  });

  await expectNoBlockingViolations(page);
}

test('reception journey registers, books and checks in in English', async ({ page, baseURL }) => {
  const api = await installSyntheticReceptionApi(page, baseURL);
  await runJourney(page, api, english);
});

test('reception journey registers, books and checks in in Arabic with right-to-left', async ({
  page,
  baseURL,
}) => {
  const api = await installSyntheticReceptionApi(page, baseURL);
  await runJourney(page, api, arabic);
});
