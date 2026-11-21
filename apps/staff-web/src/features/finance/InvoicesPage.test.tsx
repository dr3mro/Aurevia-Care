import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { InvoicePage, InvoiceState } from './domain';
import { alphaOrganizationId, betaOrganizationId, syntheticInvoice, syntheticInvoiceView } from './fixtures';
import { financeText } from './messages';
import { formatMoney } from './money';
import { InvoicesPage } from './InvoicesPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RouterProvider
            patterns={['/finance', '/finance/:id']}
            initialPath="/finance"
          >
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement = <InvoicesPage />, organizationId?: string, locale?: Locale) {
  return render(element(ui, organizationId, locale));
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function pageFor(
  states: readonly InvoiceState[],
  nextCursor: string | null = null,
  start = 1,
): InvoicePage {
  return {
    items: states.map((state, index) =>
      syntheticInvoiceView({ id: `invoice-${start + index}`, state }),
    ),
    nextCursor,
  };
}

function failure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('InvoicesPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    let resolve!: (value: InvoicePage) => void;
    sendMock.mockReturnValueOnce(new Promise<InvoicePage>((done) => { resolve = done; }));

    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    resolve(pageFor(['issued']));
    expect(await screen.findByText('invoice-1')).toBeTruthy();
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
      sendMock.mockRejectedValueOnce(failure(kind, `corr-${kind}`));

      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden and session-expired states from the transport kind', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    const view = wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    view.unmount();

    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('InvoicesPage list', () => {
  it('renders the opaque patient id, state word and integer minor-unit money', async () => {
    sendMock.mockResolvedValueOnce({
      items: [syntheticInvoiceView({ totalMinor: 5000, balanceMinor: 2000 })],
      nextCursor: null,
    });

    wrap();
    expect(await screen.findByRole('rowheader', { name: 'invoice-001' })).toBeTruthy();
    const table = screen.getByRole('table');
    expect(within(table).getByText('patient-001')).toBeTruthy();
    expect(within(table).getByText(financeText('en', 'stateIssued'))).toBeTruthy();
    expect(within(table).getByText(formatMoney(5000, 'USD', 'en'))).toBeTruthy();
    expect(screen.getByText(formatMoney(2000, 'USD', 'en'))).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/finance/invoices');
  });

  it('renders a word for every invoice state', async () => {
    sendMock.mockResolvedValueOnce(
      pageFor(['draft', 'issued', 'partially_paid', 'paid', 'void']),
    );

    wrap();
    const table = await screen.findByRole('table');
    expect(within(table).getByText(financeText('en', 'stateDraft'))).toBeTruthy();
    expect(within(table).getByText(financeText('en', 'stateIssued'))).toBeTruthy();
    expect(within(table).getByText(financeText('en', 'statePartiallyPaid'))).toBeTruthy();
    expect(within(table).getByText(financeText('en', 'statePaid'))).toBeTruthy();
    expect(within(table).getByText(financeText('en', 'stateVoid'))).toBeTruthy();
  });

  it('opens an invoice through an opaque, encoded detail link', async () => {
    sendMock.mockResolvedValueOnce(pageFor(['issued']));

    wrap();
    const link = await screen.findByRole('link', {
      name: `${financeText('en', 'open')} invoice-1`,
    });
    expect(link.getAttribute('href')).toBe('/finance/invoice-1');
  });

  it('filters by exact status and drops the previous rows', async () => {
    sendMock
      .mockResolvedValueOnce(pageFor(['issued']))
      .mockResolvedValueOnce(pageFor(['paid'], null, 2));

    wrap();
    expect(await screen.findByText('invoice-1')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(financeText('en', 'status')), {
      target: { value: 'paid' },
    });

    expect(await screen.findByText('invoice-2')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
    expect(descriptorAt(1).url).toContain('status=paid');
    expect(screen.queryByRole('rowheader', { name: 'invoice-1' })).toBeNull();
  });

  it('clears every accumulated page when the active organization changes', async () => {
    sendMock.mockResolvedValue(pageFor(['issued']));
    const view = wrap();

    expect(await screen.findByText('invoice-1')).toBeTruthy();
    view.rerender(element(<InvoicesPage />, betaOrganizationId));

    expect(screen.queryByText('invoice-1')).toBeNull();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('pages through the cursor and keeps earlier rows', async () => {
    sendMock.mockResolvedValueOnce(pageFor(['draft'], 'cursor-2'));
    sendMock.mockResolvedValueOnce(pageFor(['paid'], null, 2));

    wrap();
    expect(await screen.findByText('invoice-1')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: financeText('en', 'loadMore') }));

    expect(await screen.findByText('invoice-2')).toBeTruthy();
    expect(screen.getByText('invoice-1')).toBeTruthy();
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');
    expect(screen.queryByRole('button', { name: financeText('en', 'loadMore') })).toBeNull();
  });

  it('renders Arabic right-to-left without losing the table', async () => {
    sendMock.mockResolvedValueOnce(pageFor(['paid']));

    wrap(<InvoicesPage />, alphaOrganizationId, 'ar');
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(within(screen.getByRole('table')).getByText(financeText('ar', 'statePaid'))).toBeTruthy();
  });
});

describe('finance route registration', () => {
  it('registers both finance screens through the self-registering route module', async () => {
    sendMock.mockResolvedValue({
      items: [],
      nextCursor: null,
      invoice: syntheticInvoice(),
      entries: [],
    });
    const routes = createRoutes(false);
    const listRoute = routes.find((route) => route.pattern === '/finance');
    const detailRoute = routes.find((route) => route.pattern === '/finance/:id');
    expect(listRoute).toBeDefined();
    expect(detailRoute).toBeDefined();

    const ListComponent = listRoute!.component;
    const DetailComponent = detailRoute!.component;
    const view = render(
      element(
        <Suspense fallback={<div>loading chunk</div>}>
          <ListComponent />
          <DetailComponent />
        </Suspense>,
      ),
    );

    expect(
      (await screen.findAllByRole('heading', { name: stateViewText('en', 'loading') })).length,
    ).toBeGreaterThan(0);
    view.unmount();
  });
});
