import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { printStylesheet } from '../styles/printStyles';
import { PrintPreviewDialog } from './PrintPreviewDialog';
import type {
  HandoutLanguage,
  PrintPreviewState,
  PrintReason,
  RefusalReason,
} from './PrintPreviewDialog';

/** A synthetic handout: no real PDF bytes or patient data are used. */
const readyState: PrintPreviewState = {
  kind: 'ready',
  documentUrl: 'data:application/pdf;base64,JVBERi0xLjQK',
  title: 'Synthetic handout',
};

/** Render the dialog under the providers it needs, in a chosen locale. */
function renderDialog(state: PrintPreviewState, locale: Locale = 'en', open = true) {
  const onPrint = vi.fn<(reason: PrintReason, language: HandoutLanguage) => void>();
  const onDownload = vi.fn<(language: HandoutLanguage) => void>();
  const onClose = vi.fn<() => void>();
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PrintPreviewDialog
          open={open}
          state={state}
          onPrint={onPrint}
          onDownload={onDownload}
          onClose={onClose}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, onPrint, onDownload, onClose };
}

const refusals: readonly (readonly [RefusalReason, string])[] = [
  ['draft-source', 'The source is still a draft.'],
  ['unsigned-source', 'The source is not signed.'],
  ['order-not-placed', 'The order has not been placed.'],
  ['wrong-organization', 'The handout belongs to another organization.'],
  ['missing-print-permission', 'Your role has no permission to print this handout.'],
];

describe('print preview dialog states', () => {
  it('renders nothing when closed', () => {
    renderDialog(readyState, 'en', false);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows a labelled modal with the handout and every ready-state control', () => {
    renderDialog(readyState);
    const dialog = screen.getByRole('dialog', { name: 'Print preview' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).toBeTruthy();
    const frame = screen.getByTitle('Synthetic handout');
    expect(frame.getAttribute('src')).toBe(readyState.documentUrl);
    expect(frame.getAttribute('data-print-handout')).toBe('true');
    expect(screen.getByRole('group', { name: 'Why are you printing?' })).toBeTruthy();
    expect(screen.getByLabelText('Handout language')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Print' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy();
  });

  it('injects the A4 browser print stylesheet', () => {
    renderDialog(readyState);
    const style = document.querySelector('style#smartclinic-print-preview-styles');
    expect(style).toBeTruthy();
    expect(style?.textContent).toBe(printStylesheet);
  });

  it('shows the loading state without print controls', () => {
    renderDialog({ kind: 'loading' });
    expect(screen.getByRole('status').textContent).toContain('Loading…');
    expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Print' })).toBeNull();
  });

  it('shows the error state with a correlation id and a working retry', () => {
    const onRetry = vi.fn<() => void>();
    renderDialog({ kind: 'error', correlationId: 'SYN-CORR-7', onRetry });
    expect(screen.getByRole('alert').textContent).toContain('Something went wrong');
    expect(screen.getByText('SYN-CORR-7')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('shows the error state when no correlation id is available', () => {
    renderDialog({ kind: 'error' });
    expect(screen.getByText('not available')).toBeTruthy();
  });

  it('shows the forbidden state', () => {
    renderDialog({ kind: 'forbidden' });
    expect(screen.getByRole('alert').textContent).toContain('You do not have access');
    expect(screen.queryByRole('button', { name: 'Print' })).toBeNull();
  });

  it.each(refusals)('shows the refusal reason in words for %s', (reason, words) => {
    renderDialog({ kind: 'refused', reason });
    expect(screen.getByRole('alert').textContent).toContain(words);
    expect(screen.queryByRole('button', { name: 'Print' })).toBeNull();
  });

  it('shows the Arabic refusal reason in words', () => {
    renderDialog({ kind: 'refused', reason: 'unsigned-source' }, 'ar');
    expect(screen.getByRole('alert').textContent).toContain('المصدر غير موقّع.');
  });
});

describe('print preview dialog commands', () => {
  it('requires a reason before printing and focuses the error summary', () => {
    const { onPrint } = renderDialog(readyState);
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onPrint).not.toHaveBeenCalled();
    const summary = screen.getByRole('alert');
    expect(summary.textContent).toContain('Choose a reason before printing');
    expect(document.activeElement).toBe(summary);
  });

  it('prints with the chosen reason and clears the error summary', () => {
    const { onPrint } = renderDialog(readyState);
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    fireEvent.click(screen.getByLabelText('Referral'));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onPrint).toHaveBeenCalledTimes(1);
    expect(onPrint).toHaveBeenCalledWith('referral', 'en');
    expect(screen.queryByText('Choose a reason before printing')).toBeNull();
  });

  it('prints in the chosen handout language', () => {
    const { onPrint } = renderDialog(readyState);
    fireEvent.change(screen.getByLabelText('Handout language'), { target: { value: 'ar' } });
    fireEvent.click(screen.getByLabelText('Give to patient'));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onPrint).toHaveBeenCalledWith('give-to-patient', 'ar');
  });

  it('downloads the current handout language without requiring a reason', () => {
    const { onDownload } = renderDialog(readyState);
    fireEvent.click(screen.getByRole('button', { name: 'Download' }));
    expect(onDownload).toHaveBeenCalledWith('en');
    fireEvent.change(screen.getByLabelText('Handout language'), { target: { value: 'ar' } });
    fireEvent.click(screen.getByRole('button', { name: 'Download' }));
    expect(onDownload).toHaveBeenLastCalledWith('ar');
  });

  it('closes on the explicit action', () => {
    const { onClose } = renderDialog(readyState);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('print preview dialog keyboard behaviour', () => {
  it('moves focus inside when opened and restores it on close', () => {
    const triggerView = render(<button type="button">trigger</button>);
    const trigger = triggerView.getByRole('button', { name: 'trigger' });
    trigger.focus();
    const view = renderDialog(readyState);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
    view.unmount();
    expect(document.activeElement).toBe(trigger);
  });

  it('closes on Escape and ignores unrelated keys', () => {
    const { onClose } = renderDialog(readyState);
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('contains Tab within the dialog in both directions', () => {
    renderDialog(readyState);
    const first = screen.getByLabelText('Give to patient');
    const second = screen.getByLabelText('Referral');
    const closeButton = screen.getByRole('button', { name: 'Close' });

    // Initial focus is the safe Close action; Tab wraps to the first control.
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(second);
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(closeButton);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(first);

    // Focus outside the dialog is brought back to the first control.
    const outsideView = render(<button type="button">outside</button>);
    const outside = outsideView.getByRole('button', { name: 'outside' });
    outside.focus();
    expect(document.activeElement).toBe(outside);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(first);
  });

  it('keeps Tab on the only control of a non-ready dialog', () => {
    renderDialog({ kind: 'loading' });
    const closeButton = screen.getByRole('button', { name: 'Close' });
    expect(document.activeElement).toBe(closeButton);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(closeButton);
  });
});

describe('print preview dialog localization', () => {
  it('renders in Arabic with right-to-left direction and translated controls', () => {
    const { onPrint } = renderDialog(readyState, 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('dialog', { name: 'معاينة الطباعة' })).toBeTruthy();
    expect(screen.getByRole('group', { name: 'لماذا تطبع؟' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'طباعة' }));
    expect(screen.getByText('اختر سببًا قبل الطباعة')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('إحالة'));
    fireEvent.click(screen.getByRole('button', { name: 'طباعة' }));
    expect(onPrint).toHaveBeenCalledWith('referral', 'ar');
  });

  it('renders in English with left-to-right direction', () => {
    renderDialog(readyState, 'en');
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
  });
});
