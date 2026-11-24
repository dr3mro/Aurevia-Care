/**
 * WEB-09 clause 2 evidence, staff web: every screen defines a loading, empty,
 * error, timeout or offline, forbidden, stale-version conflict and
 * session-expired state.
 *
 * Synthetic fixtures only. This file is `.ts` (not `.tsx`), so React elements
 * are built with `createElement`; the assertions still exercise the real
 * `StateViews`, `StateView.forTransportError`, `ApiPage`-owned shared views and
 * `SettingsPage` boundaries in jsdom. Each state is rendered and unmounted on
 * its own so that role queries are never ambiguous between two alert panels.
 */
import { Fragment, createElement } from 'react';
import type { ReactElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TransportError } from '../../../src/api/transport';
import type { TransportErrorKind } from '../../../src/api/transport';
import {
  ConflictState,
  EmptyState,
  ErrorState,
  ForbiddenState,
  LoadingState,
  SessionExpiredState,
  StateView,
} from '../../../src/components/StateViews';
import { stateViewText } from '../../../src/components/stateViewMessages';
import { SettingsPage } from '../../../src/features/staffmsg/settings/SettingsPage';
import type { SettingsPageProps } from '../../../src/features/staffmsg/settings/SettingsPage';
import { settingsText } from '../../../src/features/staffmsg/settings/labels';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { must } from '../../must';

/** The one synthetic correlation id used to prove which states expose it. */
const correlationId = 'corr-synthetic-web09';

/** Build a typed transport failure carrying the synthetic correlation id. */
function failure(kind: TransportErrorKind): TransportError {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Mount one state view through the same theme and locale providers the shell uses. */
function renderState(view: ReactElement, locale: Locale = 'en') {
  const i18nProps = { initialLocale: locale, children: view };
  const themeProps = { children: createElement(I18nProvider, i18nProps) };
  return render(createElement(ThemeProvider, themeProps));
}

/** Mount one mapped transport error through the real StateView dispatcher. */
function renderTransportState(kind: TransportErrorKind) {
  return renderState(
    createElement(Fragment, null, StateView.forTransportError(failure(kind))),
  );
}

/** The four mappings that deliberately carry no diagnostic identifier. */
const anonymousKinds = new Set<TransportErrorKind>([
  'session-expired',
  'forbidden',
  'conflict',
  'precondition-required',
]);

/** Expected heading for every TransportError kind, proving the full mapping. */
const expectedMapping: Readonly<Record<TransportErrorKind, string>> = {
  'csrf-missing': 'Something went wrong',
  'session-expired': 'Your session has expired',
  forbidden: 'You do not have access',
  'not-found': 'Something went wrong',
  conflict: 'This record changed',
  invalid: 'Something went wrong',
  'precondition-required': 'This record changed',
  'rate-limited': 'Something went wrong',
  server: 'Something went wrong',
  offline: 'Something went wrong',
  timeout: 'Something went wrong',
};

/** Typed keys of the exhaustive record, so a new kind cannot silently pass. */
const expectedKinds = Object.keys(expectedMapping) as TransportErrorKind[];

describe('WEB-09 clause 2 — resilient screen states', () => {
  it('rendersEveryResilientStateWithHeadingsAndRecoveryActions', () => {
    const loading = renderState(createElement(LoadingState));
    expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Loading…');
    loading.unmount();

    const empty = renderState(createElement(EmptyState));
    expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Nothing to show');
    empty.unmount();

    const onRetry = vi.fn();
    const error = renderState(createElement(ErrorState, { correlationId, onRetry }));
    const errorAlert = screen.getByRole('alert');
    expect(errorAlert.getAttribute('aria-live')).toBe('assertive');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Something went wrong');
    expect(errorAlert.textContent).toContain(correlationId);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    error.unmount();

    const unknownCorrelation = renderState(createElement(ErrorState));
    expect(screen.getByRole('alert').textContent).toContain(
      stateViewText('en', 'correlationUnavailable'),
    );
    unknownCorrelation.unmount();

    const forbidden = renderState(createElement(ForbiddenState));
    expect(screen.getByRole('alert').getAttribute('aria-live')).toBe('assertive');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('You do not have access');
    forbidden.unmount();

    const onCompare = vi.fn();
    const onReload = vi.fn();
    const onCopy = vi.fn();
    const conflict = renderState(
      createElement(ConflictState, { onCompare, onReload, onCopy }),
    );
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('This record changed');
    expect(screen.getByRole('alert').textContent).toContain('Your changes are kept.');
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    fireEvent.click(screen.getByRole('button', { name: 'Copy my changes' }));
    expect(onCompare).toHaveBeenCalledTimes(1);
    expect(onReload).toHaveBeenCalledTimes(1);
    expect(onCopy).toHaveBeenCalledTimes(1);
    conflict.unmount();

    const onSignIn = vi.fn();
    const expired = renderState(createElement(SessionExpiredState, { onSignIn }));
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Your session has expired');
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
    expired.unmount();

    for (const kind of expectedKinds) {
      const heading = expectedMapping[kind];
      const view = renderTransportState(kind);
      const alert = screen.getByRole('alert');
      expect(alert.getAttribute('aria-live')).toBe('assertive');
      expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(heading);
      // Only the generic error state carries the correlation id; the four
      // dedicated blocking states deliberately show no diagnostic metadata.
      if (anonymousKinds.has(kind)) {
        expect(alert.textContent).not.toContain(correlationId);
      } else {
        expect(alert.textContent).toContain(correlationId);
      }
      view.unmount();
    }
  });

  it('refusesWrongStateAndPreservesTypedWork', async () => {
    const forbidden = renderTransportState('forbidden');
    const forbiddenAlert = screen.getByRole('alert');
    expect(forbiddenAlert.textContent).toContain('You do not have access');
    expect(forbiddenAlert.textContent).not.toContain('Something went wrong');
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    forbidden.unmount();

    const conflict = renderTransportState('conflict');
    expect(screen.getByRole('alert').textContent).not.toContain('Something went wrong');
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    conflict.unmount();

    const typedReply = 'Synthetic reply kept through conflict';
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(failure('conflict'));
    renderState(createElement(SettingsPage, { onSave }));
    const reply = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: settingsText('en', 'replyLabel'),
    });
    fireEvent.change(reply, { target: { value: typedReply } });
    fireEvent.submit(must(reply.closest('form'), 'settings form'));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('This record changed');
    expect(reply.value).toBe(typedReply);

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByTestId('settings-compare').textContent).toContain(typedReply);
    expect(reply.value).toBe(typedReply);

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByTestId('settings-working-copy').textContent).toContain(typedReply);
    expect(reply.value).toBe(typedReply);
  });
});
