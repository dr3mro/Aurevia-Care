import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { ImagingOrderPageResponse, ImagingOrderResponse } from './api';
import { alphaOrganizationId, receivedOrder, scheduledOrder } from './fixtures';
import { OrdersPage } from './OrdersPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function wire(order: typeof receivedOrder): ImagingOrderResponse {
  return {
    id: order.id,
    version: order.version,
    patientId: order.patientId,
    patientDisplayName: order.patientName.latinName,
    patientLocalName: order.patientName.localName ?? null,
    urgency: order.urgency,
    clinicalQuestion: order.clinicalQuestion,
    state: order.state === 'study-linked' ? 'study_linked' : order.state,
    accessionNo: order.accessionNo,
    modality: order.modality,
    scheduledAt: order.scheduledAt,
    studyInstanceUid: order.studyInstanceUid,
    interpretation: null,
  };
}

function page(items: readonly ImagingOrderResponse[], nextCursor: string | null = null): ImagingOrderPageResponse {
  return { items, nextCursor };
}

function element(ui: ReactElement, organizationId: string, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(ui, organizationId, locale));
}

function failure(kind: ConstructorParameters<typeof TransportError>[0]) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
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

beforeEach(() => {
  sendMock.mockReset();
});

describe('OrdersPage states', () => {
  it('shows loading until the first page resolves', async () => {
    const pending = deferred<ImagingOrderPageResponse>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<OrdersPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(page([wire(receivedOrder)]));
    expect(await screen.findByText('Received')).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind));
      wrap(<OrdersPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state on 403', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    wrap(<OrdersPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the conflict state on 412', async () => {
    sendMock.mockRejectedValueOnce(failure('conflict'));
    wrap(<OrdersPage />);
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('shows the session-expired state on 401', async () => {
    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap(<OrdersPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows the empty state when no orders exist', async () => {
    sendMock.mockResolvedValueOnce(page([]));
    wrap(<OrdersPage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });
});

describe('OrdersPage content and paging', () => {
  it('renders the order list with the tenant header on the imaging path', async () => {
    sendMock.mockResolvedValueOnce(page([wire(scheduledOrder)]));
    wrap(<OrdersPage />);

    expect(await screen.findByText('ACC-2026-0001')).toBeTruthy();
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/imaging/orders');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('appends the next page while a cursor exists', async () => {
    sendMock
      .mockResolvedValueOnce(page([wire(receivedOrder)], 'cursor-2'))
      .mockResolvedValueOnce(page([wire(scheduledOrder)]));
    wrap(<OrdersPage />);

    expect(await screen.findByText('Received')).toBeTruthy();
    screen.getByRole('button', { name: 'Load more' }).click();
    expect(await screen.findByText('ACC-2026-0001')).toBeTruthy();
    expect(descriptorAt(1).url).toBe('/api/v1/imaging/orders?cursor=cursor-2');
  });

  it('clears the previous tenant rows when the organization switches', async () => {
    sendMock.mockResolvedValue(page([wire(receivedOrder)]));
    const view = wrap(<OrdersPage />);
    await screen.findByText('Received');

    view.rerender(element(<OrdersPage />, '22222222-2222-2222-2222-222222222222'));
    await screen.findByText('Received');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(
      '22222222-2222-2222-2222-222222222222',
    );
  });

  it('renders Arabic labels under RTL', async () => {
    sendMock.mockResolvedValueOnce(page([wire(receivedOrder)]));
    wrap(<OrdersPage />, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByText('طلبات التصوير')).toBeTruthy();
  });
});
