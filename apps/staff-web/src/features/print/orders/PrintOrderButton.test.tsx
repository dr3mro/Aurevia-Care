import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import type { HandoutLanguage, PrintReason } from '../components/messages';
import { PrintOrderButton } from './PrintOrderButton';
import type { PrintableOrder, SheetLayout } from './types';

/** Synthetic placed lab order. */
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

/** Synthetic placed imaging order from the same visit. */
const imagingOrder: PrintableOrder = {
  ...labOrder,
  id: '1003',
  category: 'imaging',
  tests: [{ code: 'RAD-CT', name: 'CT abdomen with contrast' }],
  clinicalQuestion: 'Synthetic abdominal pain',
  priority: 'stat',
  preparationInstructions: 'Nothing by mouth for six hours.',
  whereToGo: 'Imaging suite, ground floor',
};

/** Synthetic draft order; it must never be printable. */
const draftOrder: PrintableOrder = { ...labOrder, id: '1002', status: 'draft' };

/** Callback shape recorded by the owning screen for the print audit. */
type OnPrint = (
  sheets: readonly (readonly PrintableOrder[])[],
  reason: PrintReason,
  language: HandoutLanguage,
) => void;

/** Render the button under the providers it needs. */
function renderButton(
  orders: readonly PrintableOrder[],
  locale: Locale = 'en',
): { onPrint: ReturnType<typeof vi.fn<OnPrint>> } {
  const onPrint = vi.fn<OnPrint>();
  render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PrintOrderButton orders={orders} onPrint={onPrint} />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { onPrint };
}

/** Open the preview and choose a reason, so a print can be confirmed. */
function openWithReason(locale: Locale = 'en') {
  fireEvent.click(
    screen.getByRole('button', { name: locale === 'ar' ? 'طباعة للمريض' : 'Print for patient' }),
  );
  fireEvent.click(screen.getByLabelText(locale === 'ar' ? 'تسليم إلى المريض' : 'Give to patient'));
}

describe('print order button gate', () => {
  it('is disabled with a reason when the only order is a draft', () => {
    const { onPrint } = renderButton([draftOrder]);
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Print for patient' });
    expect(button.disabled).toBe(true);
    expect(screen.getByText('Draft orders cannot be printed. Place the order first.')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onPrint).not.toHaveBeenCalled();
  });

  it('is enabled for a placed order and opens a preview with the preparation text', () => {
    renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    expect(screen.getByRole('dialog', { name: 'Patient requisition' })).toBeTruthy();
    expect(screen.getByText('Fast for eight hours before collection.')).toBeTruthy();
    expect(screen.getByText('ORD-1001')).toBeTruthy();
  });

  it('excludes drafts from a mixed selection and explains why', () => {
    renderButton([labOrder, draftOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    expect(screen.getByText('Draft orders are not included on the requisition.')).toBeTruthy();
    expect(screen.getByText('ORD-1001')).toBeTruthy();
    expect(screen.queryByText('ORD-1002')).toBeNull();
  });
});

describe('print order sheet layout', () => {
  it('prints several orders on one sheet by default', () => {
    const { onPrint } = renderButton([labOrder, imagingOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    expect(document.querySelectorAll('[data-order-requisition="true"]')).toHaveLength(1);
    expect(screen.getByText('Sheets: 1')).toBeTruthy();
    openWithReason();
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    const sheets = onPrint.mock.calls[0]?.[0];
    expect(sheets).toHaveLength(1);
    expect(sheets?.[0]?.map((order) => order.id)).toEqual(['1001', '1003']);
    expect(onPrint.mock.calls[0]?.[1]).toBe('give-to-patient');
    expect(onPrint.mock.calls[0]?.[2]).toBe('en');
  });

  it('prints separate sheets when the clinician chooses them', () => {
    const { onPrint } = renderButton([labOrder, imagingOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    fireEvent.click(screen.getByLabelText('One sheet per order'));
    expect(document.querySelectorAll('[data-order-requisition="true"]')).toHaveLength(2);
    expect(screen.getByText('Sheets: 2')).toBeTruthy();
    openWithReason();
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    const sheets = required(onPrint.mock.calls[0]?.[0]);
    const layout: SheetLayout = sheets.length === 2 ? 'separate' : 'combined';
    expect(layout).toBe('separate');
    expect(sheets[0]?.map((order) => order.id)).toEqual(['1001']);
    expect(sheets[1]?.map((order) => order.id)).toEqual(['1003']);
  });

  it('can switch back to a single sheet', () => {
    renderButton([labOrder, imagingOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    fireEvent.click(screen.getByLabelText('One sheet per order'));
    fireEvent.click(screen.getByLabelText('One sheet for all orders'));
    expect(document.querySelectorAll('[data-order-requisition="true"]')).toHaveLength(1);
  });

  it('hides the layout choice when only one order can be printed', () => {
    renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    expect(screen.queryByText('Print layout')).toBeNull();
  });
});

describe('print order preview commands', () => {
  it('requires a reason before printing and clears the error once chosen', () => {
    const { onPrint } = renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onPrint).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toContain('Choose why you are printing.');
    fireEvent.click(screen.getByLabelText('Referral'));
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onPrint).toHaveBeenCalledTimes(1);
    expect(onPrint.mock.calls[0]?.[1]).toBe('referral');
  });

  it('associates the reason error with its control and focuses that control', () => {
    renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    const alert = screen.getByRole('alert');
    const reasons = screen.getByRole('group', { name: 'Why are you printing?' });
    expect(alert.id.length).toBeGreaterThan(0);
    expect(reasons.getAttribute('aria-describedby')).toBe(alert.id);
    expect(document.activeElement).toBe(screen.getByLabelText('Give to patient'));
    fireEvent.click(screen.getByLabelText('Give to patient'));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(reasons.hasAttribute('aria-describedby')).toBe(false);
  });

  it('prints in the chosen handout language', () => {
    const { onPrint } = renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    fireEvent.change(screen.getByLabelText('Handout language'), { target: { value: 'ar' } });
    expect(screen.getByText('طلب المريض')).toBeTruthy();
    expect(screen.getByText('التحضير')).toBeTruthy();
    const sheet = document.querySelector('[data-order-requisition="true"]');
    expect(sheet?.getAttribute('dir')).toBe('rtl');
    expect(sheet?.getAttribute('lang')).toBe('ar');
    fireEvent.click(screen.getByLabelText('Give to patient'));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onPrint.mock.calls[0]?.[2]).toBe('ar');
  });

  it('renders an English handout left-to-right even inside an Arabic interface', () => {
    renderButton([labOrder], 'ar');
    fireEvent.click(screen.getByRole('button', { name: 'طباعة للمريض' }));
    fireEvent.change(screen.getByLabelText('لغة المطبوع'), { target: { value: 'en' } });
    const sheet = document.querySelector('[data-order-requisition="true"]');
    expect(sheet?.getAttribute('dir')).toBe('ltr');
    expect(sheet?.getAttribute('lang')).toBe('en');
    expect(screen.getByText('Preparation')).toBeTruthy();
  });

  it('reseeds the handout language from the interface locale on each opening', () => {
    renderButton([labOrder], 'ar');
    fireEvent.click(screen.getByRole('button', { name: 'طباعة للمريض' }));
    fireEvent.change(screen.getByLabelText('لغة المطبوع'), { target: { value: 'en' } });
    expect(document.querySelector('[data-order-requisition="true"]')?.getAttribute('dir')).toBe(
      'ltr',
    );
    fireEvent.click(screen.getByRole('button', { name: 'إغلاق' }));
    fireEvent.click(screen.getByRole('button', { name: 'طباعة للمريض' }));
    expect(document.querySelector('[data-order-requisition="true"]')?.getAttribute('dir')).toBe(
      'rtl',
    );
  });

  it('closes on the explicit action and on Escape, restoring focus to the opener', () => {
    const view = renderButton([labOrder]);
    const opener = screen.getByRole('button', { name: 'Print for patient' });
    opener.focus();
    fireEvent.click(opener);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(opener);
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(screen.getByRole('dialog')).toBeTruthy();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(view.onPrint).not.toHaveBeenCalled();
  });

  it('keeps Tab and Shift+Tab inside the modal dialog', () => {
    renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    const language = screen.getByLabelText('Handout language');
    const firstReason = screen.getByLabelText('Give to patient');
    const printButton = screen.getByRole('button', { name: 'Print' });
    const closeButton = screen.getByRole('button', { name: 'Close' });
    const outsideView = render(<button type="button">outside</button>);
    const outside = outsideView.getByRole('button', { name: 'outside' });

    // Initial focus is the safe Close action, which is the last control.
    expect(document.activeElement).toBe(closeButton);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(language);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(firstReason);
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(language);
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(closeButton);
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(printButton);
    outside.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(language);
  });

  it('injects the shared A4 print stylesheet while the preview is open', () => {
    renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    const style = document.querySelector('style[data-order-print-styles="true"]');
    expect(style).toBeTruthy();
  });

  it('handles a Tab delivered after the dialog unmounted', () => {
    // The dialog ref is cleared on unmount; the focus trap must use the empty
    // control list instead of reading a detached node.
    const addListener = vi.spyOn(document, 'addEventListener');
    renderButton([labOrder]);
    fireEvent.click(screen.getByRole('button', { name: 'Print for patient' }));
    const keydown = addListener.mock.calls.find(([type]) => type === 'keydown');
    addListener.mockRestore();
    expect(keydown?.[1]).toBeTypeOf('function');
    const listener = keydown?.[1];
    if (typeof listener !== 'function') {
      throw new Error('Expected the order dialog to register a keydown listener');
    }

    cleanup();
    const event = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    listener(event);

    expect(event.defaultPrevented).toBe(true);
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('print order button localization', () => {
  it('renders the button and the sheet in Arabic right-to-left', () => {
    const { onPrint } = renderButton([labOrder], 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    openWithReason('ar');
    expect(screen.getByRole('dialog', { name: 'طلب المريض' })).toBeTruthy();
    expect(screen.getByText('جلوكوم صيامي')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'طباعة' }));
    expect(onPrint).toHaveBeenCalledTimes(1);
  });

  it('renders the button in English left-to-right', () => {
    renderButton([labOrder], 'en');
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
