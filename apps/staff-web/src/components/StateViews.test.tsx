import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { TransportError } from '../api/transport';
import type { TransportErrorKind } from '../api/transport';
import { I18nProvider } from '../i18n/I18nProvider';
import type { Locale } from '../i18n/messages';
import { ThemeProvider } from '../theme/ThemeProvider';
import {
  ConflictState,
  EmptyState,
  ErrorState,
  ForbiddenState,
  LoadingState,
  SessionExpiredState,
  StateView,
} from './StateViews';

function renderView(locale: Locale, view: ReactNode) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{view}</I18nProvider>
    </ThemeProvider>,
  );
}

/** A native button is reachable by keyboard and can be activated with a click. */
function expectKeyboardReachable(button: HTMLElement) {
  expect(button.tagName).toBe('BUTTON');
  expect((button as HTMLButtonElement).disabled).toBe(false);
  button.focus();
  expect(document.activeElement).toBe(button);
}

describe('required screen states (WEB-09)', () => {
  it('renders the loading state as a polite status in English and Arabic RTL', () => {
    const english = renderView('en', <LoadingState />);
    expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
    expect(screen.getByRole('status').textContent).toContain('Loading…');
    expect(document.documentElement.dir).toBe('ltr');
    english.unmount();

    renderView('ar', <LoadingState />);
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('status').textContent).toContain('جار التحميل…');
  });

  it('renders the empty state as a polite status in both languages', () => {
    const english = renderView('en', <EmptyState />);
    const status = screen.getByRole('status');
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.textContent).toContain('Nothing to show');
    english.unmount();

    renderView('ar', <EmptyState />);
    expect(screen.getByRole('status').textContent).toContain('لا يوجد شيء لعرضه');
  });

  it('renders the error state with its correlation ID and a working Retry button', () => {
    const onRetry = vi.fn();
    const english = renderView('en', <ErrorState correlationId="corr-123" onRetry={onRetry} />);
    const alert = screen.getByRole('alert');
    expect(alert.getAttribute('aria-live')).toBe('assertive');
    expect(alert.textContent).toContain('Something went wrong');
    expect(alert.textContent).toContain('corr-123');
    const retry = screen.getByRole('button', { name: 'Retry' });
    expectKeyboardReachable(retry);
    fireEvent.click(retry);
    expect(onRetry).toHaveBeenCalledTimes(1);
    english.unmount();

    renderView('ar', <ErrorState correlationId="corr-123" onRetry={onRetry} />);
    expect(screen.getByRole('alert').textContent).toContain('معرّف الارتباط');
    expect(screen.getByRole('button', { name: 'إعادة المحاولة' })).toBeTruthy();
  });

  it('shows an explicit fallback when no correlation ID is available and uses a no-op Retry', () => {
    renderView('en', <ErrorState />);
    expect(screen.getByRole('alert').textContent).toContain('not available');
    const retry = screen.getByRole('button', { name: 'Retry' });
    expectKeyboardReachable(retry);
    expect(() => fireEvent.click(retry)).not.toThrow();
  });

  it('renders the forbidden state in both languages', () => {
    const english = renderView('en', <ForbiddenState />);
    const alert = screen.getByRole('alert');
    expect(alert.getAttribute('aria-live')).toBe('assertive');
    expect(alert.textContent).toContain('You do not have access');
    english.unmount();

    renderView('ar', <ForbiddenState />);
    expect(screen.getByRole('alert').textContent).toContain('لا تملك صلاحية الوصول');
  });

  it('renders the conflict state with Compare, Reload and Copy my changes callbacks', () => {
    const onCompare = vi.fn();
    const onReload = vi.fn();
    const onCopy = vi.fn();
    const english = renderView(
      'en',
      <ConflictState onCompare={onCompare} onReload={onReload} onCopy={onCopy} />,
    );
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('This record changed');
    const compare = screen.getByRole('button', { name: 'Compare' });
    const reload = screen.getByRole('button', { name: 'Reload' });
    const copy = screen.getByRole('button', { name: 'Copy my changes' });
    for (const button of [compare, reload, copy]) {
      expectKeyboardReachable(button);
    }
    fireEvent.click(compare);
    fireEvent.click(reload);
    fireEvent.click(copy);
    expect(onCompare).toHaveBeenCalledTimes(1);
    expect(onReload).toHaveBeenCalledTimes(1);
    expect(onCopy).toHaveBeenCalledTimes(1);
    english.unmount();

    renderView('ar', <ConflictState />);
    expect(screen.getByRole('alert').textContent).toContain('تغيّر هذا السجل');
    expect(screen.getByRole('button', { name: 'نسخ تغييراتي' })).toBeTruthy();
  });

  it('renders the session-expired state with a working Sign in button', () => {
    const onSignIn = vi.fn();
    const english = renderView('en', <SessionExpiredState onSignIn={onSignIn} />);
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Your session has expired');
    const signIn = screen.getByRole('button', { name: 'Sign in' });
    expectKeyboardReachable(signIn);
    fireEvent.click(signIn);
    expect(onSignIn).toHaveBeenCalledTimes(1);
    english.unmount();

    renderView('ar', <SessionExpiredState />);
    expect(screen.getByRole('alert').textContent).toContain('انتهت جلستك');
    expect(screen.getByRole('button', { name: 'تسجيل الدخول' })).toBeTruthy();
  });
});

describe('StateView.forTransportError', () => {
  const expected: readonly (readonly [TransportErrorKind, string])[] = [
    ['csrf-missing', 'Something went wrong'],
    ['session-expired', 'Your session has expired'],
    ['forbidden', 'You do not have access'],
    ['not-found', 'Something went wrong'],
    ['conflict', 'This record changed'],
    ['invalid', 'Something went wrong'],
    ['precondition-required', 'This record changed'],
    ['rate-limited', 'Something went wrong'],
    ['server', 'Something went wrong'],
    ['offline', 'Something went wrong'],
    ['timeout', 'Something went wrong'],
  ];

  it('maps every transport error kind to the correct blocking state', () => {
    for (const [kind, heading] of expected) {
      const error = new TransportError(kind, {
        correlationId: 'corr-map',
        code: undefined,
        retryAfterSeconds: undefined,
      });
      const { unmount } = renderView('en', <>{StateView.forTransportError(error)}</>);
      const alert = screen.getByRole('alert');
      expect(alert.getAttribute('aria-live')).toBe('assertive');
      expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(heading);
      // Only the generic error state carries diagnostic metadata; the other
      // blocking states deliberately show no identifiers.
      if (heading === 'Something went wrong') {
        expect(alert.textContent).toContain('corr-map');
      }
      unmount();
    }
  });

  it('gives the mapped views safe no-op actions when no callbacks are supplied', () => {
    const conflict = new TransportError('conflict', {
      correlationId: undefined,
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const expired = new TransportError('session-expired', {
      correlationId: undefined,
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const server = new TransportError('server', {
      correlationId: undefined,
      code: undefined,
      retryAfterSeconds: undefined,
    });

    const first = renderView('en', <>{StateView.forTransportError(conflict)}</>);
    for (const name of ['Compare', 'Reload', 'Copy my changes']) {
      fireEvent.click(screen.getByRole('button', { name }));
    }
    screen.getByRole('alert');
    first.unmount();

    const second = renderView('en', <>{StateView.forTransportError(expired)}</>);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    screen.getByRole('alert');
    second.unmount();

    renderView('en', <>{StateView.forTransportError(server)}</>);
    expect(screen.getByRole('alert').textContent).toContain('not available');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    screen.getByRole('alert');
  });
});
