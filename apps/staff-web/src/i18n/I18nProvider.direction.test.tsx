import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider, useI18n } from './I18nProvider';

function DirectionConsumer() {
  const { locale, direction, toggleLocale } = useI18n();
  return (
    <div>
      <span data-testid="locale">{locale}</span>
      <span data-testid="direction">{direction}</span>
      <button type="button" onClick={toggleLocale}>
        toggle
      </button>
    </div>
  );
}

describe('UX-01 document direction follows the language', () => {
  it('flips the document from English LTR to Arabic RTL and back', () => {
    render(
      <I18nProvider>
        <DirectionConsumer />
      </I18nProvider>,
    );
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
    expect(screen.getByTestId('direction').textContent).toBe('ltr');

    fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('locale').textContent).toBe('ar');
    expect(screen.getByTestId('direction').textContent).toBe('rtl');

    fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');
  });
});
