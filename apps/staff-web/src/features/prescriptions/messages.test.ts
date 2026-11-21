import { describe, expect, it } from 'vitest';
import type { PrescriptionEventKind } from './domain';
import {
  prescriptionEventMessageKey,
  prescriptionKeys,
  prescriptionText,
} from './messages';

describe('prescription messages', () => {
  it('defines every label in English and Arabic', () => {
    for (const key of prescriptionKeys) {
      expect(prescriptionText('en', key).length).toBeGreaterThan(0);
      expect(prescriptionText('ar', key).length).toBeGreaterThan(0);
    }
  });

  it('maps every event kind to a defined label in both languages', () => {
    const kinds: PrescriptionEventKind[] = [
      'submitted',
      'delivery_confirmed',
      'accepted',
      'rejected',
      'dispensed',
      'clarification',
      'renewal',
      'cancelled',
    ];
    for (const kind of kinds) {
      const key = prescriptionEventMessageKey[kind];
      expect(prescriptionKeys).toContain(key);
      expect(prescriptionText('en', key)).toBeTruthy();
      expect(prescriptionText('ar', key)).toBeTruthy();
    }
  });
});
