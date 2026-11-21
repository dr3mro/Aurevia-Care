/** UX-01: the prescriptions copy must stay bilingual and parity-checked. */
import { PRESCRIPTION_COPY, STATUS_WORDS } from './messages';
import type { PrescriptionLocale, PrescriptionStatus } from './types';

const locales: PrescriptionLocale[] = ['en', 'ar'];
const statuses: PrescriptionStatus[] = ['sent', 'accepted', 'needsCheck', 'handedOver'];

describe('prescription copy parity (UX-01)', () => {
  it('defines identical screen-copy keys for English and Arabic', () => {
    expect(Object.keys(PRESCRIPTION_COPY.en).sort()).toEqual(Object.keys(PRESCRIPTION_COPY.ar).sort());
  });

  it('defines a non-empty status word in every locale', () => {
    for (const status of statuses) {
      for (const locale of locales) {
        expect(STATUS_WORDS[status][locale].length).toBeGreaterThan(0);
      }
    }
  });
});
