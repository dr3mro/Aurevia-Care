import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { AppShell } from '../../src/app/AppShell';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

function renderShell() {
  return render(
    <ThemeProvider>
      <I18nProvider>
        <AppShell />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('staff web app shell', () => {
  it('renders landmarks, a skip link and the product title from project.json', () => {
    renderShell();
    expect(screen.getByRole('banner')).toBeTruthy();
    expect(screen.getByRole('navigation')).toBeTruthy();
    expect(screen.getByRole('main')).toBeTruthy();
    expect(screen.getByRole('contentinfo')).toBeTruthy();
    expect(screen.getByRole('link', { name: /skip to main/i }).getAttribute('href')).toBe('#main');
    expect(screen.getByRole('banner').textContent).toContain('Aurevia Care');
  });

  it('exposes theme and language toggles with pressed state', () => {
    renderShell();
    const theme = screen.getByRole('button', { name: 'Theme' });
    const language = screen.getByRole('button', { name: 'Language' });
    expect(theme.getAttribute('aria-pressed')).toBe('false');
    expect(language.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(theme);
    expect(theme.getAttribute('aria-pressed')).toBe('true');
    expect(document.documentElement.dataset.theme).toBe('dark');
    fireEvent.click(language);
    expect(language.getAttribute('aria-pressed')).toBe('true');
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('shows the tenant switcher, sign-in and protected placeholders', () => {
    renderShell();
    expect(screen.getByLabelText('Organization')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0);
  });

  it('shows a synthetic facility table, mixed-script text and formatted values', () => {
    renderShell();
    expect(screen.getByRole('table', { name: 'Facilities' })).toBeTruthy();
    expect(screen.getByText('ليلى Haddad · MAIN')).toBeTruthy();
    expect(screen.getByText(/cm/)).toBeTruthy();
  });

  it('opens a labelled dialog and closes it through the explicit action', () => {
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
    expect(screen.getByRole('dialog', { name: 'Facility details' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('accepts a note through an accessible field and echoes it', () => {
    renderShell();
    const input = screen.getByLabelText('Shift note');
    fireEvent.change(input, { target: { value: 'synthetic handoff' } });
    fireEvent.submit(screen.getByRole('form', { name: 'Shift note form' }));
    expect(screen.getByRole('status', { name: 'Echo' }).textContent).toBe('synthetic handoff');
  });
});
