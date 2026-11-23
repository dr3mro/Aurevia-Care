import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';

function Consumer() {
  const { locale, direction, t, setLocale, toggleLocale } = useI18n();
  return (
    <div>
      <span data-testid="locale">{locale}</span>
      <span data-testid="direction">{direction}</span>
      <span data-testid="message">{t('signIn')}</span>
      <button type="button" onClick={toggleLocale}>
        toggle
      </button>
      <button type="button" onClick={() => setLocale('en')}>
        english
      </button>
    </div>
  );
}

describe('i18n provider', () => {
  it('defaults to English LTR and sets the document language and direction', () => {
    render(
      <I18nProvider>
        <Consumer />
      </I18nProvider>,
    );
    expect(screen.getByTestId('locale').textContent).toBe('en');
    expect(screen.getByTestId('direction').textContent).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('toggles to Arabic RTL and back, retranslating messages', () => {
    render(
      <I18nProvider>
        <Consumer />
      </I18nProvider>,
    );
    const english = screen.getByTestId('message').textContent;
    fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
    expect(screen.getByTestId('locale').textContent).toBe('ar');
    expect(screen.getByTestId('direction').textContent).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('message').textContent).not.toBe(english);
    fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
    expect(screen.getByTestId('locale').textContent).toBe('en');
  });

  it('accepts an explicit initial locale and a direct setter', () => {
    render(
      <I18nProvider initialLocale="ar">
        <Consumer />
      </I18nProvider>,
    );
    expect(screen.getByTestId('locale').textContent).toBe('ar');
    fireEvent.click(screen.getByRole('button', { name: 'english' }));
    expect(screen.getByTestId('locale').textContent).toBe('en');
  });

  it('throws when used outside its provider', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Consumer />)).toThrow(/useI18n/);
    error.mockRestore();
  });
});

describe('i18n provider composition', () => {
  it('passes children through unchanged', () => {
    render(
      <I18nProvider>
        <span>synthetic child</span>
      </I18nProvider>,
    );
    expect(screen.getByText('synthetic child')).toBeTruthy();
  });
});
