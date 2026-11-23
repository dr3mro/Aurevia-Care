import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import {
  alphaOrganizationId,
  statusWithoutEvents,
  syntheticAcceptedStatus,
  syntheticDispensedStatus,
  syntheticInboxItem,
  syntheticStatus,
} from './fixtures';
import { pharmacyText } from './labels';
import { PharmacyItemPage } from './PharmacyItemPage';
import { routes } from './routes';
import type { StatusViewResponse } from './types';

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
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RouterProvider
            patterns={['/pharmacy/:id']}
            initialPath={`/pharmacy/${syntheticInboxItem.id}`}
          >
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = `corr-${kind}`,
) {
  return new TransportError(kind, { correlationId, code: undefined, retryAfterSeconds: undefined });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Queue the two reads the detail page makes for one prescription. */
function queueDetail(status: StatusViewResponse = syntheticStatus) {
  sendMock.mockResolvedValueOnce(syntheticInboxItem);
  sendMock.mockResolvedValueOnce(status);
}

async function reachDetail(status: StatusViewResponse = syntheticStatus) {
  queueDetail(status);
  wrap(<PharmacyItemPage />);
  await screen.findByRole('heading', { level: 2, name: pharmacyText('en', 'detailTitle') });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('PharmacyItemPage states', () => {
  it('shows the loading state until the prescription and status resolve', async () => {
    const pendingItem = deferred<typeof syntheticInboxItem>();
    const pendingStatus = deferred<typeof syntheticStatus>();
    sendMock.mockReturnValueOnce(pendingItem.promise);
    sendMock.mockReturnValueOnce(pendingStatus.promise);
    wrap(<PharmacyItemPage />);

    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pendingItem.resolve(syntheticInboxItem);
    pendingStatus.resolve(syntheticStatus);
    expect(
      await screen.findByRole('heading', { level: 2, name: pharmacyText('en', 'detailTitle') }),
    ).toBeTruthy();
  });

  it('shows the error state with a correlation id and retry', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    wrap(<PharmacyItemPage />);

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-server')).toBeTruthy();
    expect(screen.getByRole('button', { name: stateViewText('en', 'retry') })).toBeTruthy();
  });

  it('shows the forbidden state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<PharmacyItemPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<PharmacyItemPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows the conflict state when the status is stale', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    wrap(<PharmacyItemPage />);
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });
});

describe('PharmacyItemPage card and history', () => {
  it('shows medication, dose, quantity, instructions, prescriber and separate flags', async () => {
    await reachDetail(syntheticDispensedStatus);

    expect(screen.getByText('Synthetic Amoxicillin')).toBeTruthy();
    expect(screen.getByText('500 mg')).toBeTruthy();
    expect(screen.getByText('20')).toBeTruthy();
    expect(screen.getByText('10 mg; oral; twice daily')).toBeTruthy();
    expect(screen.getByText('synthetic.prescriber')).toBeTruthy();
    expect(screen.getByTestId('accepted-flag').textContent).toContain(pharmacyText('en', 'flagNo'));
    expect(screen.getByTestId('dispensed-flag').textContent).toContain(
      pharmacyText('en', 'flagYes'),
    );
  });

  it('renders the status history with both detailed and detail-free facts', async () => {
    await reachDetail();
    expect(screen.getByText('delivery_confirmed')).toBeTruthy();
    expect(screen.getByText('Synthetic partner')).toBeTruthy();
    expect(screen.getByText('submitted')).toBeTruthy();
  });

  it('renders the status history empty state', async () => {
    await reachDetail(statusWithoutEvents);
    expect(screen.getByText(pharmacyText('en', 'noEvents'))).toBeTruthy();
  });

  it('renders the detail in Arabic under RTL', async () => {
    queueDetail();
    wrap(<PharmacyItemPage />, alphaOrganizationId, 'ar');
    await screen.findByRole('heading', { level: 2, name: pharmacyText('ar', 'detailTitle') });

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(pharmacyText('ar', 'instructions'))).toBeTruthy();
    expect(screen.getByRole('button', { name: pharmacyText('ar', 'accept') })).toBeTruthy();
  });
});

describe('PharmacyItemPage route binding', () => {
  it('falls back to an empty prescription id when no route parameter is bound', async () => {
    queueDetail();
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
            <PharmacyItemPage />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>,
    );

    expect(
      await screen.findByRole('heading', { level: 2, name: pharmacyText('en', 'detailTitle') }),
    ).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/inbox/');
  });

  it('resolves the exported detail route through its normal lazy entry point', async () => {
    const detailRoute = routes.find((route) => route.pattern === '/pharmacy/:id');
    expect(detailRoute).toBeDefined();
    queueDetail();
    const Component = required(detailRoute).component;
    wrap(
      <Suspense fallback={null}>
        <Component />
      </Suspense>,
    );

    expect(
      await screen.findByRole('heading', { level: 2, name: pharmacyText('en', 'detailTitle') }),
    ).toBeTruthy();
  });
});

describe('PharmacyItemPage commands', () => {
  it('requires a reason before rejecting and keeps the typed reason on conflict', async () => {
    await reachDetail();
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'submitReject') }));

    expect(screen.getByText(pharmacyText('en', 'reasonRequired'))).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);

    const reason = screen.getByLabelText<HTMLInputElement>(pharmacyText('en', 'reasonLabel'));
    fireEvent.change(reason, { target: { value: 'out of stock' } });
    sendMock.mockRejectedValueOnce(transportFailure('conflict', 'corr-stale'));
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'submitReject') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(reason.value).toBe('out of stock');
    expect(descriptorAt(2).url).toBe(`/api/v1/pharmacy/status/${syntheticInboxItem.id}/reject`);
  });

  it('requires a question before asking for clarification', async () => {
    await reachDetail();
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'submitClarify') }));

    expect(screen.getByText(pharmacyText('en', 'questionRequired'))).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);

    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'questionLabel')), {
      target: { value: 'Which strength?' },
    });
    sendMock.mockResolvedValueOnce({ id: 'event-clarify' });
    queueDetail();
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'submitClarify') }));

    await waitFor(() => expect(descriptorAt(2).body).toEqual({ question: 'Which strength?' }));
    expect(descriptorAt(2).url).toBe(
      `/api/v1/pharmacy/status/${syntheticInboxItem.id}/clarification`,
    );
    expect(await screen.findByText(pharmacyText('en', 'commandDone'))).toBeTruthy();
  });

  it('rejects with a reason and reports the command outcome', async () => {
    await reachDetail();
    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'reasonLabel')), {
      target: { value: 'out of stock' },
    });
    sendMock.mockResolvedValueOnce({ id: 'event-reject' });
    queueDetail();
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'submitReject') }));

    await waitFor(() => expect(descriptorAt(2).body).toEqual({ reason: 'out of stock' }));
    expect(descriptorAt(2).url).toBe(`/api/v1/pharmacy/status/${syntheticInboxItem.id}/reject`);
    expect(await screen.findByText(pharmacyText('en', 'commandDone'))).toBeTruthy();
  });

  it('accepts with the status version and does not imply dispensing', async () => {
    await reachDetail();
    sendMock.mockResolvedValueOnce({ id: 'event-accept' });
    queueDetail(syntheticAcceptedStatus);

    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'accept') }));

    await waitFor(() =>
      expect(descriptorAt(2).url).toBe(`/api/v1/pharmacy/status/${syntheticInboxItem.id}/accept`),
    );
    expect(descriptorAt(2).headers['If-Match']).toBe(String(syntheticStatus.version));
    expect(descriptorAt(2).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => {
      expect(screen.getByTestId('accepted-flag').textContent).toContain(
        pharmacyText('en', 'flagYes'),
      );
      expect(screen.getByTestId('dispensed-flag').textContent).toContain(
        pharmacyText('en', 'flagNo'),
      );
    });
    expect(await screen.findByText(pharmacyText('en', 'commandDone'))).toBeTruthy();
  });

  it('keeps dispensing evidence in a separate step from acceptance', async () => {
    await reachDetail();
    expect(screen.queryByLabelText(pharmacyText('en', 'dispensedBy'))).toBeNull();
    expect(screen.getByRole('button', { name: pharmacyText('en', 'accept') })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'beginDispensing') }));

    expect(
      screen.getByRole('heading', { name: pharmacyText('en', 'dispensingTitle') }),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: pharmacyText('en', 'accept') })).toBeNull();
    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'dispensedBy')), {
      target: { value: 'synthetic.pharmacist' },
    });
    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'dispensedAt')), {
      target: { value: '2026-11-21T10:30:00Z' },
    });
    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'handedOver')), {
      target: { value: '30 tablets' },
    });
    sendMock.mockResolvedValueOnce({ id: 'event-dispense' });
    queueDetail(syntheticDispensedStatus);
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'recordDispensing') }));

    await waitFor(() =>
      expect(descriptorAt(2).url).toBe(
        `/api/v1/pharmacy/status/${syntheticInboxItem.id}/dispensing-evidence`,
      ),
    );
    expect(descriptorAt(2).body).toEqual({
      dispensedBy: 'synthetic.pharmacist',
      dispensedAt: '2026-11-21T10:30:00Z',
      handedOver: '30 tablets',
    });
    await waitFor(() => {
      expect(screen.getByTestId('accepted-flag').textContent).toContain(pharmacyText('en', 'flagNo'));
      expect(screen.getByTestId('dispensed-flag').textContent).toContain(
        pharmacyText('en', 'flagYes'),
      );
    });
    expect(await screen.findByText(pharmacyText('en', 'commandDone'))).toBeTruthy();
  });

  it('returns from the dispensing step to the review actions', async () => {
    await reachDetail();
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'beginDispensing') }));
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'backToReview') }));

    expect(screen.getByRole('button', { name: pharmacyText('en', 'accept') })).toBeTruthy();
  });

  it('asks before leaving while a command form holds unsaved text', async () => {
    await reachDetail();
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'reasonLabel')), {
      target: { value: 'draft reason' },
    });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  it('reports a command failure without clearing the dispensing form', async () => {
    await reachDetail();
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'beginDispensing') }));
    fireEvent.change(screen.getByLabelText(pharmacyText('en', 'handedOver')), {
      target: { value: '30 tablets' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    fireEvent.click(screen.getByRole('button', { name: pharmacyText('en', 'recordDispensing') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>(pharmacyText('en', 'handedOver')).value).toBe(
      '30 tablets',
    );
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
