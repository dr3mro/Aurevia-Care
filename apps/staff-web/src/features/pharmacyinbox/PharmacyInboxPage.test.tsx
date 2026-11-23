import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { PharmacyInboxPage } from './PharmacyInboxPage';
import { alphaOrganizationId, betaOrganizationId, syntheticInboxItem } from './fixtures';
import { pharmacyText } from './labels';
import { routes } from './routes';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = `corr-${kind}`,
) {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function filterButton(key: 'filterAccepted' | 'filterDispensed', locale: Locale = 'en') {
  return screen.getByRole('button', {
    name: `${pharmacyText(locale, 'filterLabel')}: ${pharmacyText(locale, key)}`,
  });
}

beforeEach(() => {
  sendMock.mockReset();
  window.history.replaceState(null, '', '/');
});

describe('PharmacyInboxPage states', () => {
  it('shows the loading state until the routed page resolves', async () => {
    const pending = deferred<{ items: (typeof syntheticInboxItem)[]; nextCursor: string | null }>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<PharmacyInboxPage />);

    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve({ items: [syntheticInboxItem], nextCursor: null });
    expect(
      await screen.findByRole('table', { name: pharmacyText('en', 'worklistCaption') }),
    ).toBeTruthy();
  });

  it('shows the empty state when no prescriptions are routed here', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    wrap(<PharmacyInboxPage />);

    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id and retry for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));
      wrap(<PharmacyInboxPage />);

      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
      expect(screen.getByRole('button', { name: stateViewText('en', 'retry') })).toBeTruthy();
    },
  );

  it('shows the forbidden state for a pharmacy user without access', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<PharmacyInboxPage />);

    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the stale-version conflict state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    wrap(<PharmacyInboxPage />);

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('shows the session-expired state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<PharmacyInboxPage />);

    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('PharmacyInboxPage worklist', () => {
  it('renders the routed prescription with its medication, dose, quantity and state', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: null });
    wrap(<PharmacyInboxPage />);

    expect(
      await screen.findByRole('table', { name: pharmacyText('en', 'worklistCaption') }),
    ).toBeTruthy();
    expect(screen.getByText('Synthetic Amoxicillin')).toBeTruthy();
    expect(screen.getByText('500 mg')).toBeTruthy();
    expect(screen.getByText('20')).toBeTruthy();
    expect(screen.getByText(pharmacyText('en', 'stateDeliveryConfirmed'))).toBeTruthy();
    expect(screen.getByText(syntheticInboxItem.patientId)).toBeTruthy();
  });

  it('changes the status filter and refetches with the new status', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: null });
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    wrap(<PharmacyInboxPage />);
    await screen.findByRole('table', { name: pharmacyText('en', 'worklistCaption') });

    const accepted = filterButton('filterAccepted');
    expect(accepted.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(accepted);

    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(accepted.getAttribute('aria-pressed')).toBe('true');
    expect(descriptorAt(1).url).toContain('status=accepted');
  });

  it('appends the next page when Load more is activated', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: 'cursor-2' });
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    wrap(<PharmacyInboxPage />);
    await screen.findByRole('table', { name: pharmacyText('en', 'worklistCaption') });

    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'loadMore') }));

    await waitFor(() => expect(descriptorAt(1).url).toContain('cursor=cursor-2'));
    expect(screen.queryByRole('button', { name: pharmacyText('en', 'loadMore') })).toBeNull();
  });

  it('keeps the first page on screen when a Load more request fails', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: 'cursor-2' });
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    wrap(<PharmacyInboxPage />);
    await screen.findByRole('table', { name: pharmacyText('en', 'worklistCaption') });

    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'loadMore') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(screen.getByText('Synthetic Amoxicillin')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: stateViewText('en', 'error') })).toBeNull();
  });

  it('exposes the primary row action as a native keyboard-activatable button', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: null });
    wrap(<PharmacyInboxPage />);
    const open = await screen.findByRole('button', {
      name: new RegExp(`${pharmacyText('en', 'openItem')}.*${syntheticInboxItem.patientId}`),
    });

    expect(open.tagName).toBe('BUTTON');
    expect(open.getAttribute('tabindex')).toBeNull();
    open.focus();
    expect(document.activeElement).toBe(open);
    fireEvent.click(open);
    expect(open).toBeTruthy();
  });

  it('renders the worklist in Arabic under RTL', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: null });
    wrap(<PharmacyInboxPage />, alphaOrganizationId, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(
      await screen.findByRole('table', { name: pharmacyText('ar', 'worklistCaption') }),
    ).toBeTruthy();
    expect(screen.getByText(pharmacyText('ar', 'stateDeliveryConfirmed'))).toBeTruthy();
  });

  it('clears the previous tenant rows on an organization switch', async () => {
    sendMock.mockResolvedValueOnce({ items: [syntheticInboxItem], nextCursor: null });
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    const view = wrap(<PharmacyInboxPage />);
    await screen.findByText(syntheticInboxItem.patientId);

    view.rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={{ organizationId: betaOrganizationId }}>
            <PharmacyInboxPage />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>,
    );

    await waitFor(() => expect(screen.queryByText(syntheticInboxItem.patientId)).toBeNull());
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});

describe('pharmacy route export', () => {
  it('contributes the pharmacy inbox and detail patterns through feature registration', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/pharmacy');
    expect(patterns).toContain('/pharmacy/:id');
    expect(new Set(patterns).size).toBe(patterns.length);
  });

  it('resolves the exported inbox route through its normal lazy entry point', async () => {
    const inboxRoute = routes.find((route) => route.pattern === '/pharmacy');
    expect(inboxRoute).toBeDefined();
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    const Component = required(inboxRoute).component;
    wrap(
      <Suspense fallback={null}>
        <Component />
      </Suspense>,
    );

    expect(
      await screen.findByRole('heading', { name: pharmacyText('en', 'pharmacy') }),
    ).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
