/**
 * WEB-07 clause 4 evidence, staff web: an unsupported client receives only the
 * plain message and no application data, and the client-support rule refuses to
 * show that page to a supported client.
 *
 * Synthetic user agents and viewport widths drive the real `isSupportedBrowser`
 * guard. The unsupported branch renders the real `UnsupportedBrowserPage` under
 * the providers the entry point gives it; the supported branch renders no plain
 * notice, so the "no unsupported message" assertions can only pass when the real
 * guard accepts the client. This file is `.ts` (not `.tsx`), so React elements
 * are built with `createElement`. No endpoint is contacted and no patient,
 * tenant or credential data appears in these fixtures.
 */
import { createElement } from 'react';
import type { ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { isSupportedBrowser } from '../../../src/features/system/browserSupport';
import { UnsupportedBrowserPage } from '../../../src/features/system/UnsupportedBrowserPage';
import { systemMessages, systemText } from '../../../src/features/system/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/** Current desktop Chrome, one of the WEB-07 supported desktop browsers. */
const SUPPORTED_UA =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';

/** Documented WEB-07 desktop width. */
const SUPPORTED_WIDTH = 1280;

/** Internet Explorer is explicitly unsupported and must receive no data. */
const UNSUPPORTED_UA = 'Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.2)';

/** One CSS pixel below the 320 px reflow floor of decision 0004. */
const NARROWER_THAN_REFLOW_FLOOR = 319;

/**
 * Reproduce the entry point's client decision with the real guard: an
 * unsupported client gets only the provider-wrapped plain notice, a supported
 * client gets no plain notice at all. The real `main.tsx` choice between that
 * notice and `<App />` is asserted by `tests/main.test.tsx`.
 */
function entryTree(userAgent: string, width: number): ReactElement {
  const content = isSupportedBrowser(userAgent, width)
    ? null
    : createElement(UnsupportedBrowserPage);
  const initialLocale: Locale = 'en';
  const i18nProps = { initialLocale, children: content };
  const localized = createElement(I18nProvider, i18nProps);
  const themeProps = { children: localized };
  return createElement(ThemeProvider, themeProps);
}

/** Assert that no clinical shell, fixture label or navigation is present. */
function expectNoApplicationData(): void {
  expect(screen.queryByRole('navigation')).toBeNull();
  expect(screen.queryByRole('table')).toBeNull();
  expect(screen.queryByText('Synthetic Reception')).toBeNull();
  expect(screen.queryByText('Synthetic Alpha Clinic')).toBeNull();
  expect(screen.queryByText('Staff Portal')).toBeNull();
}

/** Assert that the plain unsupported notice, and nothing else, is present. */
function expectOnlyThePlainMessage(): void {
  expect(screen.getByRole('heading', { name: systemMessages.en.unsupportedTitle })).toBeTruthy();
  expect(screen.getByText(systemText('en', 'unsupportedDetail'))).toBeTruthy();
  expect(screen.getByRole('button', { name: systemMessages.en.goHome })).toBeTruthy();
  expectNoApplicationData();
}

describe('WEB-07 clause 4 — unsupported browsers see a plain message and no data', () => {
  it('showsOnlyThePlainMessageAndNoApplicationDataForUnsupportedClient', () => {
    expect(isSupportedBrowser(UNSUPPORTED_UA, SUPPORTED_WIDTH)).toBe(false);

    render(entryTree(UNSUPPORTED_UA, SUPPORTED_WIDTH));

    expectOnlyThePlainMessage();
  });

  it('doesNotShowThePlainUnsupportedMessageToSupportedClient', () => {
    expect(isSupportedBrowser(SUPPORTED_UA, SUPPORTED_WIDTH)).toBe(true);

    render(entryTree(SUPPORTED_UA, SUPPORTED_WIDTH));

    expect(
      screen.queryByRole('heading', { name: systemMessages.en.unsupportedTitle }),
    ).toBeNull();
    expect(screen.queryByText(systemText('en', 'unsupportedDetail'))).toBeNull();
    expect(screen.queryByRole('button', { name: systemMessages.en.goHome })).toBeNull();
  });

  it('failsClosedToThePlainMessageWhenTheViewportIsNarrowerThanTheReflowFloor', () => {
    expect(isSupportedBrowser(SUPPORTED_UA, NARROWER_THAN_REFLOW_FLOOR)).toBe(false);

    render(entryTree(SUPPORTED_UA, NARROWER_THAN_REFLOW_FLOOR));

    expectOnlyThePlainMessage();
  });
});
