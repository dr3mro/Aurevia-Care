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
import { ThemeProvider } from '../../theme/ThemeProvider';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import { OrdersPage } from './OrdersPage';

const alphaOrganizationId = syntheticOrganizations[0]!.id;
const betaOrganizationId = syntheticOrganizations[1]!.id;

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function orderWire(id: string, code: string) {
  return {
    id,
    version: 1,
    patientId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    encounterRef: 'enc-1',
    orderCode: code,
    priority: 'urgent' as const,
    clinicalQuestion: 'Synthetic question',
    destinationOrg: '22222222-2222-2222-2222-222222222222',
    sourceActor: 'synthetic.source',
    responsibleClinician: 'synthetic.clinician',
    workQueue: 'queue-1',
    authorizationId: 'auth-1',
    snapshotId: 'snapshot-1',
    snapshotFingerprint: 'fingerprint-1',
    disclosureId: 'disclosure-1',
    workItemId: 'work-1',
    createdAt: '2026-11-21T09:00:00Z',
  };
}

function element(ui: ReactElement, organizationId: string, locale: 'en' | 'ar' = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement = <OrdersPage />, organizationId = alphaOrganizationId, locale: 'en' | 'ar' = 'en') {
  return render(element(ui, organizationId, locale));
}

function descriptorAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
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

beforeEach(() => {
  sendMock.mockReset();
  window.history.replaceState(null, '', '/');
});

describe('OrdersPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<{ items: ReturnType<typeof orderWire>[]; nextCursor: string | null }>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve({ items: [orderWire('order-001', 'ORD-1')], nextCursor: null });
    expect(await screen.findByText('ORD-1')).toBeTruthy();
  });

  it('shows the empty state for an empty first page', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    wrap();
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValue(transportFailure(kind, `corr-${kind}`));
      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the role cannot list orders', async () => {
    sendMock.mockRejectedValue(transportFailure('forbidden'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state when the session ended', async () => {
    sendMock.mockRejectedValue(transportFailure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('OrdersPage worklist', () => {
  it('renders one linked row per order with no load-more control on the last page', async () => {
    sendMock.mockResolvedValueOnce({
      items: [orderWire('order-001', 'ORD-1'), orderWire('order-002', 'ORD-2')],
      nextCursor: null,
    });
    wrap();
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(screen.getAllByRole('rowheader')).toHaveLength(2);
    const link = screen.getAllByRole('link', { name: 'Open detail' })[0]!;
    expect(link.getAttribute('href')).toBe('/orders/order-001');
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(screen.getAllByText('Urgent')).toHaveLength(2);
  });

  it('activates the detail link as a native keyboard control', async () => {
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-001', 'ORD-1')], nextCursor: null });
    wrap();
    const link = await screen.findByRole('link', { name: 'Open detail' });
    fireEvent.click(link);
    expect(window.location.pathname).toBe('/orders/order-001');
  });

  it('re-requests with the chosen state filter after clearing the previous rows', async () => {
    sendMock.mockResolvedValue({ items: [orderWire('order-001', 'ORD-1')], nextCursor: null });
    wrap();
    expect(await screen.findByText('ORD-1')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Work-item state'), { target: { value: 'requested' } });

    expect(await screen.findByText(stateViewText('en', 'loading'))).toBeTruthy();
    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    expect(descriptorAt(1).url).toContain('status=requested');
    expect(await screen.findByText('ORD-1')).toBeTruthy();
  });

  it('appends the next cursor page and hides load more at the end', async () => {
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-001', 'ORD-1')], nextCursor: 'cursor-2' });
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-002', 'ORD-2')], nextCursor: null });
    wrap();
    expect(await screen.findByText('ORD-1')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('ORD-2')).toBeTruthy();
    expect(screen.getByText('ORD-1')).toBeTruthy();
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('shows a retryable error and keeps loaded rows when Load more fails', async () => {
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-001', 'ORD-1')], nextCursor: 'cursor-2' });
    sendMock.mockRejectedValueOnce(transportFailure('server', 'corr-page'));
    wrap();
    expect(await screen.findByText('ORD-1')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('ORD-1')).toBeTruthy();
    expect(screen.getByText('corr-page')).toBeTruthy();
  });

  it('clears the previous tenant rows before the replacement page responds', async () => {
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-001', 'ORD-1')], nextCursor: null });
    sendMock.mockResolvedValue({ items: [], nextCursor: null });
    const { rerender } = wrap();
    expect(await screen.findByText('ORD-1')).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    rerender(element(<OrdersPage />, betaOrganizationId));
    await waitFor(() => expect(screen.queryByText('ORD-1')).toBeNull());
    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('renders the table right-to-left with Arabic labels', async () => {
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-001', 'ORD-1')], nextCursor: null });
    wrap(<OrdersPage />, alphaOrganizationId, 'ar');
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
  });
});

describe('orders routes', () => {
  it('registers both order paths before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/orders');
    expect(patterns).toContain('/orders/:id');
    expect(patterns.indexOf('/orders')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered list page with live data', async () => {
    sendMock.mockResolvedValueOnce({ items: [orderWire('order-001', 'ORD-1')], nextCursor: null });
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/orders')!;
    const Page = route.component;
    render(<Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>);
    expect(await screen.findByText('ORD-1')).toBeTruthy();
  });
});
