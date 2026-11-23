import type { ReactElement } from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import {
  unreadCountMany,
  unreadCountOne,
  unreadCountSeven,
  unreadCountZero,
} from './fixtures';
import { settingsText, unreadBadgeLabel } from './labels';
import { UnreadBadge } from './UnreadBadge';

/** Narrow an optional DOM lookup without a non-null assertion. */
function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected a rendered element');
  }
  return value;
}

/** Mount the badge under the providers its theme and i18n hooks require. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** The single rendered status badge. */
function badge() {
  return screen.getByRole('status');
}

describe('UnreadBadge', () => {
  it('renders no badge for an empty inbox', () => {
    const view = wrap(<UnreadBadge count={unreadCountZero} />);
    expect(view.container.firstChild).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('shows the exact digit run for one and seven unread messages', () => {
    wrap(<UnreadBadge count={unreadCountOne} />);
    expect(badge().textContent).toBe('1');
    // Assert the singular form explicitly so a plural-only label would fail.
    expect(badge().getAttribute('aria-label')).toBe(
      settingsText('en', 'unreadBadgeOne').replace('{count}', '1'),
    );

    wrap(<UnreadBadge count={unreadCountSeven} />);
    expect(screen.getAllByRole('status')).toHaveLength(2);
    expect(screen.getAllByRole('status')[1]?.textContent).toBe('7');
  });

  it('caps a large count at 99+ and isolates the numeral as a left-to-right run', () => {
    wrap(<UnreadBadge count={unreadCountMany} />);
    expect(badge().textContent).toBe('99+');
    const numeral = required(within(badge()).getByText('99+'));
    expect(numeral.tagName).toBe('B');
    const isolation = required(numeral.parentElement);
    expect(isolation.tagName).toBe('BDI');
    expect(isolation.getAttribute('dir')).toBe('ltr');
  });

  it('announces the count in Arabic under a right-to-left document', () => {
    wrap(<UnreadBadge count={unreadCountMany} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(
      screen.getByRole('status', { name: unreadBadgeLabel('ar', 120) }).textContent,
    ).toBe('99+');
    expect(required(required(screen.getByRole('status').querySelector('bdi')).parentElement)
      .textContent).toBe('99+');
  });

  it('uses only theme tokens and logical styling properties', () => {
    wrap(<UnreadBadge count={unreadCountSeven} />);
    const style = badge().getAttribute('style') ?? '';
    expect(style).toContain('padding-inline');
    expect(style).toContain('padding-block');
    expect(style).not.toMatch(/margin-left|padding-right|text-align:\s*(left|right)/);
  });
});
