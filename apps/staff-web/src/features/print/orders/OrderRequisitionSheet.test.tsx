import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { OrderRequisitionSheet } from './OrderRequisitionSheet';
import type { PrintableOrder } from './types';

/** Synthetic placed orders with both clinical text scripts. */
const labOrder: PrintableOrder = {
  id: '1001',
  category: 'lab',
  status: 'placed',
  tests: [{ code: 'LAB-GLU', name: 'Fasting glucose', nameLocal: 'جلوكوم صيامي' }],
  clinicalQuestion: 'Synthetic polyuria workup',
  priority: 'routine',
  preparationInstructions: 'Fast for eight hours before collection.',
  whereToGo: 'Laboratory, level two',
};

const imagingOrder: PrintableOrder = {
  id: '1003',
  category: 'imaging',
  status: 'placed',
  tests: [{ code: 'RAD-CT', name: 'CT abdomen with contrast' }],
  clinicalQuestion: 'Synthetic abdominal pain',
  priority: 'stat',
  preparationInstructions: 'Nothing by mouth for six hours.',
  whereToGo: 'Imaging suite, ground floor',
};

/** Render a sheet under the providers the feature needs. */
function renderSheet(orders: readonly PrintableOrder[], language: 'en' | 'ar' = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={language}>
        <OrderRequisitionSheet orders={orders} language={language} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('order requisition sheet', () => {
  it('shows every required field of a lab order in English', () => {
    renderSheet([labOrder]);
    expect(screen.getByText('ORD-1001')).toBeTruthy();
    expect(screen.getByText('Fasting glucose')).toBeTruthy();
    expect(screen.getByText('Synthetic polyuria workup')).toBeTruthy();
    expect(screen.getByText('Routine')).toBeTruthy();
    expect(screen.getByText('Fast for eight hours before collection.')).toBeTruthy();
    expect(screen.getByText('Laboratory, level two')).toBeTruthy();
    expect(screen.getByText('LAB-GLU')).toBeTruthy();
  });

  it('shows every required field of an imaging study in Arabic with the local test name', () => {
    renderSheet([imagingOrder], 'ar');
    expect(screen.getByText('ORD-1003')).toBeTruthy();
    expect(screen.getByText('CT abdomen with contrast')).toBeTruthy();
    expect(screen.getByText('Synthetic abdominal pain')).toBeTruthy();
    expect(screen.getByText('طفري')).toBeTruthy();
    expect(screen.getByText('Nothing by mouth for six hours.')).toBeTruthy();
    expect(screen.getByText('Imaging suite, ground floor')).toBeTruthy();
  });

  it('prefers the local test name when the sheet is in Arabic', () => {
    renderSheet([labOrder], 'ar');
    expect(screen.getByText('جلوكوم صيامي')).toBeTruthy();
    expect(screen.queryByText('Fasting glucose')).toBeNull();
  });

  it('isolates an order number as a left-to-right run inside the sheet', () => {
    renderSheet([labOrder]);
    const number = screen.getByText('ORD-1001');
    expect(number.tagName).toBe('BDI');
    expect(number.getAttribute('dir')).toBe('ltr');
  });

  it('renders each supplied order as its own entry on one sheet', () => {
    renderSheet([labOrder, imagingOrder]);
    expect(document.querySelectorAll('[data-order-entry="true"]')).toHaveLength(2);
  });

  it('renders no order entries when a sheet has no orders', () => {
    const { container } = renderSheet([]);
    expect(container.querySelectorAll('[data-order-entry="true"]')).toHaveLength(0);
  });
});
