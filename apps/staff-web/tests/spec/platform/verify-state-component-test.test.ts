import { createElement } from 'react';
import type { ReactNode } from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TransportError } from '../../../src/api/transport';
import type { TransportErrorKind } from '../../../src/api/transport';
import {
  EmptyState,
  ErrorState,
  LoadingState,
  StateView,
} from '../../../src/components/StateViews';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/**
 * WEB-09 clause 6: every required screen state has an executable component
 * test. This suite is independent of the co-located StateViews tests: it proves
 * the clause through the same public state components the shell renders, with
 * one positive table-driven proof and two refusal proofs.
 *
 * Views are built with `createElement` because this specification file is
 * plain TypeScript rather than JSX. Each view is mounted through the theme and
 * locale providers the staff shell uses, and is unmounted before the next one:
 * the locale provider writes document.dir/lang in an effect, so leaving two
 * mounted would let both compete for the same document root.
 */
function renderView(view: ReactNode) {
  const localeProps: { initialLocale: Locale; children: ReactNode } = {
    initialLocale: 'en',
    children: view,
  };
  return render(createElement(ThemeProvider, null, createElement(I18nProvider, localeProps)));
}

/** Build a typed transport failure with only synthetic diagnostic metadata. */
function failure(kind: TransportErrorKind, correlationId?: string) {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** The announcement and controls a screen state must present to the user. */
interface StateCase {
  readonly name: string;
  readonly view: ReactNode;
  readonly region: 'status' | 'alert';
  readonly live: 'polite' | 'assertive';
  readonly heading: string;
  readonly controls: readonly string[];
}

/** All states named by WEB-09, including both stale-version failure kinds. */
function requiredStates(): readonly StateCase[] {
  const genericError = {
    region: 'alert',
    live: 'assertive',
    heading: 'Something went wrong',
    controls: ['Retry'],
  } as const;
  const conflict = {
    region: 'alert',
    live: 'assertive',
    heading: 'This record changed',
    controls: ['Compare', 'Reload', 'Copy my changes'],
  } as const;
  return [
    {
      name: 'loading',
      view: createElement(LoadingState),
      region: 'status',
      live: 'polite',
      heading: 'Loading…',
      controls: [],
    },
    {
      name: 'empty',
      view: createElement(EmptyState),
      region: 'status',
      live: 'polite',
      heading: 'Nothing to show',
      controls: [],
    },
    {
      name: 'error',
      view: createElement(ErrorState, { correlationId: 'corr-proof' }),
      ...genericError,
    },
    {
      name: 'timeout',
      view: StateView.forTransportError(failure('timeout')),
      ...genericError,
    },
    {
      name: 'offline',
      view: StateView.forTransportError(failure('offline')),
      ...genericError,
    },
    {
      name: 'forbidden',
      view: StateView.forTransportError(failure('forbidden')),
      region: 'alert',
      live: 'assertive',
      heading: 'You do not have access',
      controls: [],
    },
    {
      name: 'conflict',
      view: StateView.forTransportError(failure('conflict')),
      ...conflict,
    },
    {
      name: 'stale-version',
      view: StateView.forTransportError(failure('precondition-required')),
      ...conflict,
    },
    {
      name: 'session-expired',
      view: StateView.forTransportError(failure('session-expired')),
      region: 'alert',
      live: 'assertive',
      heading: 'Your session has expired',
      controls: ['Sign in'],
    },
  ];
}

describe('WEB-09 clause 6 — verify each state with a component test', () => {
  it('eachRequiredStateHasAnExecutableComponentTest', () => {
    for (const state of requiredStates()) {
      const mounted = renderView(state.view);
      try {
        const region = screen.getByRole(state.region);
        expect(region.getAttribute('aria-live')).toBe(state.live);
        expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(state.heading);

        // The exact button set matters: an extra recovery control on a
        // forbidden or stale state would offer an unsafe action.
        expect(screen.queryAllByRole('button')).toHaveLength(state.controls.length);
        for (const control of state.controls) {
          expect(screen.getByRole('button', { name: control })).toBeTruthy();
        }
      } finally {
        mounted.unmount();
      }
    }
  });

  it('forbiddenRefusesRecoveryAndCorrelation', () => {
    const onRetry = vi.fn();
    const onReload = vi.fn();
    const onCompare = vi.fn();
    const onCopy = vi.fn();
    const onSignIn = vi.fn();
    const mounted = renderView(
      StateView.forTransportError(failure('forbidden', 'corr-forbidden-proof'), {
        onRetry,
        onReload,
        onCompare,
        onCopy,
        onSignIn,
      }),
    );
    try {
      const alert = screen.getByRole('alert');
      expect(alert.getAttribute('aria-live')).toBe('assertive');
      expect(alert.textContent).toContain('You do not have access');
      // A permission failure is not a request failure: it must not show a
      // correlation id or a generic retry that would repeat a refused action.
      expect(alert.textContent).not.toContain('Correlation ID');
      expect(alert.textContent).not.toContain('corr-forbidden-proof');
      expect(alert.textContent).not.toContain('Something went wrong');
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      expect(onRetry).not.toHaveBeenCalled();
      expect(onReload).not.toHaveBeenCalled();
      expect(onCompare).not.toHaveBeenCalled();
      expect(onCopy).not.toHaveBeenCalled();
      expect(onSignIn).not.toHaveBeenCalled();
    } finally {
      mounted.unmount();
    }
  });

  it('staleVersionRefusesSilentLoss', () => {
    for (const kind of ['conflict', 'precondition-required'] as const) {
      const mounted = renderView(StateView.forTransportError(failure(kind)));
      try {
        // A stale write must never fall back to the generic error view: the
        // user has to be able to compare, reload or copy their typed work.
        expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('This record changed');
        expect(screen.queryByText('Something went wrong')).toBeNull();
        for (const control of ['Compare', 'Reload', 'Copy my changes']) {
          expect(screen.getByRole('button', { name: control })).toBeTruthy();
        }
      } finally {
        mounted.unmount();
      }
    }
  });
});
