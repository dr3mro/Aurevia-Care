import { fireEvent, render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import type { ComponentType } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ForbiddenPage } from '../../../src/features/system/ForbiddenPage';
import { NotFoundPage } from '../../../src/features/system/NotFoundPage';
import { SignedOutPage } from '../../../src/features/system/SignedOutPage';
import {
  assignHomeLocation,
  UnsupportedBrowserPage,
} from '../../../src/features/system/UnsupportedBrowserPage';
import { SessionExpiredPage } from '../../../src/features/system/SessionExpiredPage';
import { routes as systemRoutes } from '../../../src/features/system/routes';
import { systemKeys, systemMessages, systemText } from '../../../src/features/system/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

afterEach(() => {
  window.history.replaceState(null, '', '/');
});

/** Render one system page under the providers it needs, in a chosen locale. */
function renderPage(Page: ComponentType, locale: Locale) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <Page />
      </I18nProvider>
    </ThemeProvider>,
  );
}

interface PageCase {
  name: string;
  Page: ComponentType;
  enTitle: string;
  arTitle: string;
  enAction: string;
  arAction: string;
}

/** The five fixed system pages and their bilingual title and primary action. */
const pageCases: readonly PageCase[] = [
  {
    name: 'signed out',
    Page: SignedOutPage,
    enTitle: 'You have signed out',
    arTitle: 'لقد سجّلت الخروج',
    enAction: 'Sign in',
    arAction: 'تسجيل الدخول',
  },
  {
    name: 'session expired',
    Page: SessionExpiredPage,
    enTitle: 'Your session has expired',
    arTitle: 'انتهت جلستك',
    enAction: 'Sign in',
    arAction: 'تسجيل الدخول',
  },
  {
    name: 'forbidden',
    Page: ForbiddenPage,
    enTitle: 'You do not have access',
    arTitle: 'لا تملك صلاحية الوصول',
    enAction: 'Go home',
    arAction: 'الصفحة الرئيسية',
  },
  {
    name: 'not found',
    Page: NotFoundPage,
    enTitle: 'Page not found',
    arTitle: 'الصفحة غير موجودة',
    enAction: 'Go home',
    arAction: 'الصفحة الرئيسية',
  },
  {
    name: 'unsupported browser',
    Page: UnsupportedBrowserPage,
    enTitle: 'Unsupported browser or screen size',
    arTitle: 'متصفح أو حجم شاشة غير مدعوم',
    enAction: 'Go home',
    arAction: 'الصفحة الرئيسية',
  },
];

describe('system pages', () => {
  it.each(pageCases)('$name renders in English with one primary action', (testCase) => {
    renderPage(testCase.Page, 'en');
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
    expect(screen.getByRole('heading', { name: testCase.enTitle })).toBeTruthy();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.getByRole('button', { name: testCase.enAction })).toBeTruthy();
  });

  it.each(pageCases)('$name renders in Arabic with one primary action', (testCase) => {
    renderPage(testCase.Page, 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { name: testCase.arTitle })).toBeTruthy();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.getByRole('button', { name: testCase.arAction })).toBeTruthy();
  });

  it.each(pageCases.filter((testCase) => testCase.Page !== UnsupportedBrowserPage))(
    '$name primary action returns to the home page in the app',
    (testCase) => {
      window.history.replaceState(null, '', '/system-page');
      renderPage(testCase.Page, 'en');
      fireEvent.click(screen.getByRole('button', { name: testCase.enAction }));
      expect(window.location.pathname).toBe('/');
    },
  );

  it('uses a document load, not app navigation, for the unsupported page action', () => {
    const assign = vi.fn<(url: string | URL) => void>();
    assignHomeLocation({ assign });
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith('/');

    window.history.replaceState(null, '', '/unsupported-browser');
    renderPage(UnsupportedBrowserPage, 'en');
    fireEvent.click(screen.getByRole('button', { name: 'Go home' }));
    expect(window.location.pathname).toBe('/unsupported-browser');
  });

  it('shows no application data on the unsupported page', () => {
    renderPage(UnsupportedBrowserPage, 'en');
    expect(
      screen.getByRole('heading', { name: 'Unsupported browser or screen size' }),
    ).toBeTruthy();
    expect(screen.queryByText('Synthetic Reception')).toBeNull();
    expect(screen.queryByText('Synthetic Alpha Clinic')).toBeNull();
    expect(screen.queryByText('Staff Portal')).toBeNull();
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(systemRoutes)('registered route $pattern resolves its page', async (route) => {
    const Page = route.component;
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Suspense fallback={<p role="status">Loading</p>}>
            <Page />
          </Suspense>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByRole('heading')).toBeTruthy();
  });
});

describe('system messages', () => {
  it('has identical key sets in English and Arabic', () => {
    const expected = [...systemKeys].sort();
    expect(Object.keys(systemMessages.en).sort()).toEqual(expected);
    expect(Object.keys(systemMessages.ar).sort()).toEqual(expected);
  });

  it.each(systemKeys)('resolves %s to a non-empty string in both languages', (key) => {
    expect(systemText('en', key)).toBe(systemMessages.en[key]);
    expect(systemText('ar', key)).toBe(systemMessages.ar[key]);
    expect(systemText('en', key).length).toBeGreaterThan(0);
    expect(systemText('ar', key).length).toBeGreaterThan(0);
  });
});
