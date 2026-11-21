import { describe, expect, it } from 'vitest';
import type { Locale } from '../../i18n/messages';
import { entryTypeMessageKey, financeKeys, financeText, invoiceStateMessageKey } from './messages';

describe('finance messages', () => {
  it.each(['en', 'ar'] as const)('provides every key in %s', (locale: Locale) => {
    for (const key of financeKeys) {
      expect(financeText(locale, key)).not.toBe('');
    }
  });

  it('keeps the English and Arabic key sets identical', () => {
    expect(new Set(financeKeys).size).toBe(financeKeys.length);
    expect(financeKeys).toContain('refundForm');
    expect(financeKeys).toContain('requiredReason');
    expect(financeKeys).toContain('requiredApprover');
  });

  it('maps every invoice state and ledger entry type to a label key', () => {
    expect(invoiceStateMessageKey).toEqual({
      draft: 'stateDraft',
      issued: 'stateIssued',
      partially_paid: 'statePartiallyPaid',
      paid: 'statePaid',
      void: 'stateVoid',
    });
    expect(entryTypeMessageKey).toEqual({
      payment: 'entryPayment',
      refund: 'entryRefund',
      credit: 'entryCredit',
    });
  });
});
