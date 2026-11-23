import type { ReactElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Locale } from '../../../i18n/messages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { ReferenceInserter, referenceText } from './index';

const benignSubject = 'Care plan review';

/** Mount the inserter with the providers the staff shell uses. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('ReferenceInserter', () => {
  it('has a labelled input and button and inserts a benign subject without a warning', () => {
    const onInsert = vi.fn<(subject: string) => void>();
    wrap(<ReferenceInserter onInsert={onInsert} />);

    const input = screen.getByRole('textbox', { name: referenceText('en', 'subjectLabel') });
    const button = screen.getByRole('button', { name: referenceText('en', 'insert') });
    expect(button.getAttribute('type')).toBe('button');
    expect(input.hasAttribute('aria-describedby')).toBe(false);
    expect(screen.queryByRole('alert')).toBeNull();

    fireEvent.change(input, { target: { value: benignSubject } });
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(button);
    expect(onInsert).toHaveBeenCalledExactlyOnceWith(benignSubject);
  });

  it('inserts on Enter but not on another key', () => {
    const onInsert = vi.fn<(subject: string) => void>();
    wrap(<ReferenceInserter onInsert={onInsert} />);

    const input = screen.getByRole('textbox', { name: referenceText('en', 'subjectLabel') });
    fireEvent.change(input, { target: { value: benignSubject } });
    fireEvent.keyDown(input, { key: 'Tab' });
    expect(onInsert).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onInsert).toHaveBeenCalledExactlyOnceWith(benignSubject);
  });

  it.each([
    { subject: 'Patient 123e4567-e89b-12d3-a456-426614174000', code: 'UUID_LIKE' },
    { subject: 'Call 1234567 today', code: 'DIGIT_RUN' },
    { subject: 'Contact staff@synthetic.invalid', code: 'EMAIL_LIKE' },
    { subject: 'mrn: 12345', code: 'MRN_LIKE' },
  ] as const)('warns with fixed $code text for a suspicious subject', ({ subject, code }) => {
    const onInsert = vi.fn<(subject: string) => void>();
    wrap(<ReferenceInserter onInsert={onInsert} />);

    const input = screen.getByRole('textbox', { name: referenceText('en', 'subjectLabel') });
    fireEvent.change(input, { target: { value: subject } });

    const alert = screen.getByRole('alert');
    expect(input.getAttribute('aria-describedby')).toBe(alert.id);
    expect(alert.textContent).toContain(referenceText('en', code));
    expect(alert.textContent).not.toContain(subject);
    expect(document.body.textContent).not.toContain(subject);
    expect(onInsert).not.toHaveBeenCalled();
  });

  it('translates the warning under Arabic RTL without echoing the subject', () => {
    const subject = 'mrn: 12345';
    wrap(<ReferenceInserter onInsert={vi.fn<(subject: string) => void>()} />, 'ar');

    const input = screen.getByRole('textbox', { name: referenceText('ar', 'subjectLabel') });
    fireEvent.change(input, { target: { value: subject } });

    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(referenceText('ar', 'MRN_LIKE'));
    expect(alert.textContent).not.toContain(referenceText('en', 'MRN_LIKE'));
    expect(document.body.textContent).not.toContain(subject);
  });
});
