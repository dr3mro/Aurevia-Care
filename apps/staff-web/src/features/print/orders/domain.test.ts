import { describe, expect, it } from 'vitest';
import { buildSheets, humanReadableOrderNumber, printableOrders, testDisplayName } from './domain';
import type { PrintableOrder, PrintableTest } from './types';

/** Synthetic placed lab order; no real patient or identifier is used. */
const labOrder: PrintableOrder = {
  id: '1001',
  category: 'lab',
  status: 'placed',
  tests: [{ code: 'LAB-CBC', name: 'Complete blood count', nameLocal: 'تعداد دم كامل' }],
  clinicalQuestion: 'Synthetic anaemia workup',
  priority: 'routine',
  preparationInstructions: 'No fasting required.',
  whereToGo: 'Laboratory, level two',
};

/** A draft must never be grouped onto a patient requisition sheet. */
const draftOrder: PrintableOrder = { ...labOrder, id: '1002', status: 'draft' };

/** Synthetic placed imaging order. */
const imagingOrder: PrintableOrder = {
  ...labOrder,
  id: '1003',
  category: 'imaging',
  tests: [{ code: 'RAD-CT', name: 'CT abdomen', nameLocal: 'أشعة مقطعية للبطن' }],
  clinicalQuestion: 'Synthetic abdominal pain',
  priority: 'stat',
  preparationInstructions: 'Fast for six hours.',
  whereToGo: 'Imaging suite, ground floor',
};

describe('printable orders', () => {
  it('keeps only placed orders so drafts never reach a sheet', () => {
    expect(printableOrders([labOrder, draftOrder, imagingOrder])).toEqual([labOrder, imagingOrder]);
  });

  it('returns an empty list when every order is a draft', () => {
    expect(printableOrders([draftOrder])).toEqual([]);
  });
});

describe('human readable order number', () => {
  it('prefixes the identifier without encoding a barcode', () => {
    expect(humanReadableOrderNumber(labOrder)).toBe('ORD-1001');
  });
});

describe('test display name', () => {
  const bilingual: PrintableTest = { code: 'X', name: 'Latin name', nameLocal: 'اسم عربي' };
  const latinOnly: PrintableTest = { code: 'Y', name: 'Latin only' };

  it('uses the local name in Arabic when present', () => {
    expect(testDisplayName(bilingual, 'ar')).toBe('اسم عربي');
  });

  it('falls back to the Latin name in Arabic when no local name exists', () => {
    expect(testDisplayName(latinOnly, 'ar')).toBe('Latin only');
  });

  it('uses the Latin name in English even when a local name exists', () => {
    expect(testDisplayName(bilingual, 'en')).toBe('Latin name');
  });
});

describe('sheet grouping', () => {
  it('puts every order on one sheet in the combined layout', () => {
    expect(buildSheets([labOrder, imagingOrder], 'combined')).toEqual([[labOrder, imagingOrder]]);
  });

  it('creates no sheets for an empty combined selection', () => {
    expect(buildSheets([], 'combined')).toEqual([]);
  });

  it('creates one sheet per order in the separate layout', () => {
    expect(buildSheets([labOrder, imagingOrder], 'separate')).toEqual([[labOrder], [imagingOrder]]);
  });

  it('creates no sheets for an empty separate selection', () => {
    expect(buildSheets([], 'separate')).toEqual([]);
  });
});
