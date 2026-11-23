import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { ApiLoader } from '../../api/useApi';
import { TransportError } from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { SessionContext } from '../../components/SessionContext';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import {
  emptyResultsSnapshot,
  matchedReconciliation,
  onTrackFinding,
  overdueFinding,
  resultsTimeZone,
  resultsViewerId,
  sampleInboxRows,
} from './fixtures';
import { ResultsPage } from './ResultsPage';
import type { ResultsSnapshot } from './types';

/** Two clearly synthetic organization identifiers. */
const ALPHA = '11111111-1111-1111-1111-111111111111';
const BETA = '22222222-2222-2222-2222-222222222222';
const NOW = '2026-03-09T08:50:00Z';

/** A fully populated snapshot for one organization. */
const snapshot: ResultsSnapshot = {
  organizationId: ALPHA,
  findings: [overdueFinding, onTrackFinding],
  rows: sampleInboxRows,
  reconciliation: undefined,
};

/** Read a required element without an unsafe assertion or cast. */
function required<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected element to be present');
  }
  return value;
}

/** Build a typed transport failure for a state test. */
function failure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-synthetic-results') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Mount the page under the shell providers with an injected loader. */
function element(
  load: ApiLoader<ResultsSnapshot>,
  organizationId = ALPHA,
  session?: { onSessionExpired: () => void; onSignIn: () => void },
): ReactElement {
  const providers = (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>
          <ResultsPage load={load} now={NOW} timeZone={resultsTimeZone} viewerId={resultsViewerId} />
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
  return session === undefined ? providers : <SessionContext.Provider value={session}>{providers}</SessionContext.Provider>;
}

/** A promise whose resolution the test controls. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('ResultsPage WEB-09 states', () => {
  it('shows the loading state until the synthetic loader resolves', async () => {
    const pending = deferred<ResultsSnapshot>();
    render(element(() => pending.promise));
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve(snapshot);
    expect(await screen.findByRole('heading', { name: 'Results' })).toBeTruthy();
  });

  it('shows the empty state when the tenant has no results', async () => {
    render(element(() => Promise.resolve(emptyResultsSnapshot(ALPHA))));
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it.each(['server', 'offline', 'timeout', 'invalid', 'rate-limited'] as const)(
    'shows the error state with the correlation id for %s',
    async (kind) => {
      render(element(() => Promise.reject(failure(kind, `corr-${kind}`))));
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('retries a failed load from the error state', async () => {
    const load = vi
      .fn<ApiLoader<ResultsSnapshot>>()
      .mockRejectedValueOnce(failure('timeout'))
      .mockResolvedValue(snapshot);
    render(element(load));
    fireEvent.click(await screen.findByRole('button', { name: stateViewText('en', 'retry') }));
    expect(load).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole('heading', { name: 'Results' })).toBeTruthy();
  });

  it('shows the forbidden state', async () => {
    render(element(() => Promise.reject(failure('forbidden'))));
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the conflict state with compare, reload and copy recovery actions', async () => {
    render(element(() => Promise.reject(failure('conflict'))));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('button', { name: stateViewText('en', 'compare') })).toBeTruthy();
    expect(screen.getByRole('button', { name: stateViewText('en', 'reload') })).toBeTruthy();
    expect(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') })).toBeTruthy();
  });

  it('shows the session-expired state and reports it once', async () => {
    const session = { onSessionExpired: vi.fn(), onSignIn: vi.fn() };
    render(element(() => Promise.reject(failure('session-expired')), ALPHA, session));
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
    await waitFor(() => expect(session.onSessionExpired).toHaveBeenCalledTimes(1));
  });
});

describe('ResultsPage screen actions', () => {
  it('exposes every screen action without a backend', async () => {
    const linked: ResultsSnapshot = { ...snapshot, reconciliation: matchedReconciliation };
    render(element(() => Promise.resolve(linked)));
    await screen.findByRole('heading', { name: 'Results' });

    const readBackActions = screen.getAllByRole('button', {
      name: 'Record contact and read-back',
    });
    fireEvent.click(required(readBackActions[1]));
    fireEvent.click(required(screen.getAllByRole('button', { name: 'Acknowledge' })[0]));
    fireEvent.click(required(screen.getAllByRole('button', { name: 'Escalate now' })[0]));
    fireEvent.click(screen.getByRole('button', { name: 'Ask the lab' }));
    fireEvent.click(screen.getByRole('button', { name: 'Assign owner' }));
    fireEvent.click(screen.getByRole('button', { name: 'Link report to order' }));
    expect(screen.getByRole('heading', { name: 'Inbox' })).toBeTruthy();
  });
});

describe('ResultsPage tenant scope', () => {
  it('shows the empty state for a tenant with no synthetic fixtures', async () => {
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={{ organizationId: BETA }}>
            <ResultsPage now={NOW} timeZone={resultsTimeZone} viewerId={resultsViewerId} />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it('clears the previous organization rows before the new tenant responds', async () => {
    const first = deferred<ResultsSnapshot>();
    const second = deferred<ResultsSnapshot>();
    const load = vi
      .fn<ApiLoader<ResultsSnapshot>>()
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => second.promise);
    const { rerender } = render(element(load, ALPHA));
    first.resolve(snapshot);
    expect(await screen.findByText(/Nadia Farouk/)).toBeTruthy();

    rerender(element(load, BETA));
    expect(screen.queryByText(/Nadia Farouk/)).toBeNull();
    second.resolve(emptyResultsSnapshot(BETA));
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });
});
