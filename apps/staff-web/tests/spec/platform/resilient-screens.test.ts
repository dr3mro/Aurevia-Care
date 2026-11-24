import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TransportError } from '../../../src/api/transport';
import type { TransportErrorKind } from '../../../src/api/transport';
import { clearRenderFailure, ErrorBoundary } from '../../../src/app/ErrorBoundary';
import { EmptyState, LoadingState, StateView } from '../../../src/components/StateViews';
import { stateViewText } from '../../../src/components/stateViewMessages';
import type { StateViewMessageKey } from '../../../src/components/stateViewMessages';
import { outOfOfficeSettings } from '../../../src/features/staffmsg/settings/fixtures';
import { settingsText } from '../../../src/features/staffmsg/settings/labels';
import { SettingsPage } from '../../../src/features/staffmsg/settings/SettingsPage';
import type { SettingsPageProps } from '../../../src/features/staffmsg/settings/SettingsPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/** Narrow an optional DOM lookup without a non-null assertion. */
function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected a rendered element');
  }
  return value;
}

/** Mount a state view under the same providers the staff shell supplies. */
function renderView(view: ReactNode, locale: Locale = 'en') {
  const i18nProps = { initialLocale: locale, children: view };
  return render(createElement(ThemeProvider, null, createElement(I18nProvider, i18nProps)));
}

/** The labelled textarea that carries typed work across a stale conflict. */
function replyField() {
  return screen.getByRole<HTMLTextAreaElement>('textbox', {
    name: settingsText('en', 'replyLabel'),
  });
}

/** Submit the settings form exactly as a browser does. */
function submitSettings() {
  fireEvent.submit(required(replyField().closest('form')));
}

/** Mount the representative integrating screen with its injected command. */
function renderSettings(props: SettingsPageProps) {
  return renderView(createElement(SettingsPage, props));
}

/** One expected state mapping for a member of the closed transport-error union. */
interface KindExpectation {
  readonly kind: TransportErrorKind;
  readonly headingKey: StateViewMessageKey;
  readonly controlKeys: readonly StateViewMessageKey[];
  readonly showsCorrelation: boolean;
}

/**
 * The full WEB-09 matrix. Each row names the heading and the only controls the
 * mapped state may expose, so a control leaking into a state that must not offer
 * it (forbidden, conflict, expired) fails this proof.
 */
const expectations: readonly KindExpectation[] = [
  { kind: 'csrf-missing', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
  {
    kind: 'session-expired',
    headingKey: 'sessionExpired',
    controlKeys: ['signIn'],
    showsCorrelation: false,
  },
  { kind: 'forbidden', headingKey: 'forbidden', controlKeys: [], showsCorrelation: false },
  { kind: 'not-found', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
  {
    kind: 'conflict',
    headingKey: 'conflict',
    controlKeys: ['compare', 'reload', 'copyMyChanges'],
    showsCorrelation: false,
  },
  { kind: 'invalid', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
  {
    kind: 'precondition-required',
    headingKey: 'conflict',
    controlKeys: ['compare', 'reload', 'copyMyChanges'],
    showsCorrelation: false,
  },
  { kind: 'rate-limited', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
  { kind: 'server', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
  { kind: 'offline', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
  { kind: 'timeout', headingKey: 'error', controlKeys: ['retry'], showsCorrelation: true },
];

/**
 * Throws with a synthetic patient-shaped token in the message. The token stands
 * in for clinical data that must never reach a log line or the fallback view.
 */
function ThrowingChild(): ReactNode {
  const failure = new Error('Synthetic patient token SYN-PAT-0001 must never be logged');
  failure.name = 'RenderFailure';
  throw failure;
}

describe('WEB-09 clause 1 — resilient screens', () => {
  // The boundary keeps one module-level failure across React's render replay,
  // so each case starts and ends with an explicit reset.
  afterEach(() => {
    vi.restoreAllMocks();
    clearRenderFailure();
  });

  it.each(expectations)(
    'definesEveryRequiredStateForEachTransportFailureKind: $kind',
    ({ kind, headingKey, controlKeys, showsCorrelation }) => {
      // Loading and empty are the two polite states and are never returned by
      // the transport-error mapper, so prove them at their own entry points.
      const loading = renderView(createElement(LoadingState));
      const loadingStatus = screen.getByRole('status');
      expect(loadingStatus.getAttribute('aria-live')).toBe('polite');
      expect(loadingStatus.textContent).toContain(stateViewText('en', 'loading'));
      loading.unmount();

      const empty = renderView(createElement(EmptyState));
      const emptyStatus = screen.getByRole('status');
      expect(emptyStatus.getAttribute('aria-live')).toBe('polite');
      expect(emptyStatus.textContent).toContain(stateViewText('en', 'empty'));
      empty.unmount();

      const onCompare = vi.fn();
      const onReload = vi.fn();
      const onCopy = vi.fn();
      const onRetry = vi.fn();
      const onSignIn = vi.fn();
      const actions = { onCompare, onReload, onCopy, onRetry, onSignIn };
      const correlationId = `corr-${kind}`;
      const error = new TransportError(kind, {
        correlationId,
        code: 'UNTRUSTED_RESPONSE_TEXT',
        retryAfterSeconds: undefined,
      });

      const view = renderView(StateView.forTransportError(error, actions));
      const alert = screen.getByRole('alert');
      expect(alert.getAttribute('aria-live')).toBe('assertive');
      expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(
        stateViewText('en', headingKey),
      );
      expect(
        within(alert)
          .queryAllByRole('button')
          .map((button) => button.textContent),
      ).toEqual(controlKeys.map((key) => stateViewText('en', key)));
      // Only opacity-bearing metadata is ever rendered; untrusted body text is
      // discarded by the transport layer before a state view receives it.
      expect(alert.textContent).not.toContain('UNTRUSTED_RESPONSE_TEXT');
      if (showsCorrelation) {
        expect(alert.textContent).toContain(correlationId);
        expect(alert.textContent).toContain(stateViewText('en', 'correlation'));
      } else {
        expect(alert.textContent).not.toContain(correlationId);
      }

      const callbacks: Partial<Record<StateViewMessageKey, () => void>> = {
        compare: onCompare,
        reload: onReload,
        copyMyChanges: onCopy,
        retry: onRetry,
        signIn: onSignIn,
      };
      for (const key of controlKeys) {
        fireEvent.click(screen.getByRole('button', { name: stateViewText('en', key) }));
        expect(callbacks[key]).toHaveBeenCalledTimes(1);
      }
      expect(
        onCompare.mock.calls.length +
          onReload.mock.calls.length +
          onCopy.mock.calls.length +
          onRetry.mock.calls.length +
          onSignIn.mock.calls.length,
      ).toBe(controlKeys.length);
      view.unmount();

      if (showsCorrelation) {
        const withoutId = renderView(
          StateView.forTransportError(
            new TransportError(kind, {
              correlationId: undefined,
              code: undefined,
              retryAfterSeconds: undefined,
            }),
            actions,
          ),
        );
        expect(screen.getByRole('alert').textContent).toContain(
          stateViewText('en', 'correlationUnavailable'),
        );
        withoutId.unmount();
      }
    },
  );

  it('keepsTypedWorkAndWiresCompareReloadAndCopyOnAStaleConflict', async () => {
    const conflict = new TransportError('conflict', {
      correlationId: 'corr-stale-conflict',
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(conflict);
    renderSettings({ onSave, initialSettings: outOfOfficeSettings });

    fireEvent.change(replyField(), { target: { value: 'Synthetic surviving reply' } });
    const dirtyLeave = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirtyLeave);
    expect(dirtyLeave.defaultPrevented).toBe(true);

    submitSettings();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(stateViewText('en', 'conflict'));
    expect(replyField().value).toBe('Synthetic surviving reply');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByTestId('settings-compare').textContent).toContain(
      'Synthetic surviving reply',
    );
    expect(screen.getByTestId('settings-compare').textContent).toContain(
      settingsText('en', 'compareTitle'),
    );

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByTestId('settings-working-copy').textContent).toContain(
      'Synthetic surviving reply',
    );
    expect(replyField().value).toBe('Synthetic surviving reply');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByTestId('settings-compare')).toBeNull();
    expect(replyField().value).toBe(outOfOfficeSettings.outOfOfficeReply);
    // The passive effect that removes the listener can run after the status
    // paints, so poll the observable guard state instead of racing one dispatch.
    await waitFor(() => {
      const reloadedLeave = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(reloadedLeave);
      expect(reloadedLeave.defaultPrevented).toBe(false);
    });

    onSave.mockResolvedValue(undefined);
    fireEvent.change(replyField(), { target: { value: 'Synthetic corrected reply' } });
    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    // The dirty guard is removed by a passive effect after the successful save
    // commits. Under parallel load that cleanup can lag the "saved" text that
    // findByText observes, so poll until the listener is genuinely gone rather
    // than asserting on the same tick the status appears. waitFor re-dispatches
    // a fresh cancelable event each pass and retries while the guard still
    // calls preventDefault, keeping the assertion strict and deterministic.
    await waitFor(() => {
      const leaveAfterSave = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(leaveAfterSave);
      expect(leaveAfterSave.defaultPrevented).toBe(false);
    });
  });

  it('logsOnlyTheCorrelationIdAndErrorNameFromTheGlobalBoundary', () => {
    clearRenderFailure();
    const correlationId = '00000000-0000-0000-0000-0000000000c1';
    const randomUuid = vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(correlationId);
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    renderView(createElement(ErrorBoundary, null, createElement(ThrowingChild)));

    expect(randomUuid).toHaveBeenCalled();
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(correlationId);
    expect(alert.textContent).not.toContain('SYN-PAT-0001');

    // React logs the caught error itself with a printf-style first argument,
    // while this boundary logs a plain string. Assert the plain-string set is
    // exactly the boundary's one line, so a regression that logs patient text
    // as its own line is caught even when that line carries no correlation ID.
    const plainLogLines = errorLog.mock.calls
      .filter((args) => typeof args[0] === 'string' && !args[0].includes('%'))
      .map((args) => String(args[0]));
    expect(plainLogLines).toEqual([`${correlationId} RenderFailure`]);

    // Also isolate only this boundary's single call, identified by the opaque
    // correlation ID it must carry, and check that line for patient data.
    const ownCalls = errorLog.mock.calls.filter(
      (args) => typeof args[0] === 'string' && args[0].includes(correlationId),
    );
    expect(ownCalls).toHaveLength(1);
    expect(ownCalls[0]?.[0]).toBe(`${correlationId} RenderFailure`);
    expect(ownCalls[0]?.[0]).not.toContain('SYN-PAT-0001');
  });

  it('refusesConflictRecoveryAndDataEchoForForbiddenAndExpiredSessions', () => {
    const onCompare = vi.fn();
    const onReload = vi.fn();
    const onCopy = vi.fn();
    const onRetry = vi.fn();
    const onSignIn = vi.fn();
    const actions = { onCompare, onReload, onCopy, onRetry, onSignIn };

    const forbidden = new TransportError('forbidden', {
      correlationId: 'corr-forbidden-echo',
      code: 'FORBIDDEN_REASON_PAYLOAD',
      retryAfterSeconds: undefined,
    });
    const forbiddenView = renderView(StateView.forTransportError(forbidden, actions));
    const forbiddenAlert = screen.getByRole('alert');
    expect(forbiddenAlert.textContent).toContain(stateViewText('en', 'forbidden'));
    expect(within(forbiddenAlert).queryAllByRole('button')).toHaveLength(0);
    expect(forbiddenAlert.textContent).not.toContain('corr-forbidden-echo');
    expect(forbiddenAlert.textContent).not.toContain('FORBIDDEN_REASON_PAYLOAD');
    expect(onCompare).not.toHaveBeenCalled();
    expect(onReload).not.toHaveBeenCalled();
    expect(onCopy).not.toHaveBeenCalled();
    expect(onRetry).not.toHaveBeenCalled();
    forbiddenView.unmount();

    const expired = new TransportError('session-expired', {
      correlationId: 'corr-expired-echo',
      code: 'EXPIRED_REASON_PAYLOAD',
      retryAfterSeconds: undefined,
    });
    renderView(StateView.forTransportError(expired, actions));
    const expiredAlert = screen.getByRole('alert');
    expect(expiredAlert.textContent).toContain(stateViewText('en', 'sessionExpired'));
    expect(
      within(expiredAlert)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([stateViewText('en', 'signIn')]);
    expect(expiredAlert.textContent).not.toContain('corr-expired-echo');
    expect(expiredAlert.textContent).not.toContain('EXPIRED_REASON_PAYLOAD');
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'signIn') }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
    expect(onCompare).not.toHaveBeenCalled();
    expect(onReload).not.toHaveBeenCalled();
    expect(onCopy).not.toHaveBeenCalled();
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('wiresTheGlobalErrorBoundaryAtTheApplicationRoot', () => {
    const source = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'src', 'app', 'AppShell.tsx'),
      'utf8',
    );
    // Structural assertions on comment-free source: no line numbers and no
    // dependence on how the return statement is formatted. A comment cannot
    // satisfy the nesting checks, and both the shell and its main region must
    // be inside the boundary for the word "global" to mean anything.
    const withoutComments = source.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
    expect(withoutComments).toMatch(
      /import\s+\{\s*ErrorBoundary\s*\}\s+from\s+['"]\.\/ErrorBoundary['"]/,
    );
    expect(withoutComments).toMatch(
      /<ErrorBoundary>[\s\S]*className="app-shell"[\s\S]*<\/ErrorBoundary>/,
    );
    expect(withoutComments).toMatch(/<ErrorBoundary>[\s\S]*id="main"[\s\S]*<\/ErrorBoundary>/);
  });
});
