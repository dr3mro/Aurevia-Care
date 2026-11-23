import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { navigationFor } from '../../app/navigation';
import { stateViewText } from '../../components/stateViewMessages';
import { formatInstant } from '../../i18n/format';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { adminText } from './messages';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { AuditPage } from './AuditPage';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function event(overrides: Record<string, unknown> = {}) {
  return {
    id: 'audit-1',
    occurredAt: '2026-03-08T06:30:00Z',
    actorId: 'synthetic.actor',
    action: 'CONFIG_CREATE',
    resourceType: 'configuration',
    resourceId: 'cfg-1',
    purpose: 'Synthetic configuration review',
    scope: 'facility:MAIN',
    correlationId: 'synthetic-corr',
    outcome: 'SUCCESS',
    seq: 3,
    ...overrides,
  };
}

function page(events: Record<string, unknown>[] = [event()], nextCursor: string | null = null) {
  return { events, nextCursor };
}

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
}

function element(ui: ReactElement, organizationId: string, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(ui, organizationId, locale));
}

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0], code?: string) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code,
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

function fillFilters(overrides: Partial<Record<string, string>> = {}) {
  const values = {
    action: 'CONFIG_CREATE',
    outcome: 'DENIED',
    resourceType: 'configuration',
    from: '2026-03-01T00:00:00Z',
    to: '2026-03-08T00:00:00Z',
    ...overrides,
  };
  fireEvent.change(screen.getByLabelText(adminText('en', 'auditActionFilter')), {
    target: { value: values.action },
  });
  fireEvent.change(screen.getByLabelText(adminText('en', 'auditOutcomeFilter')), {
    target: { value: values.outcome },
  });
  fireEvent.change(screen.getByLabelText(adminText('en', 'auditResourceFilter')), {
    target: { value: values.resourceType },
  });
  fireEvent.change(screen.getByLabelText(adminText('en', 'auditFrom')), {
    target: { value: values.from },
  });
  fireEvent.change(screen.getByLabelText(adminText('en', 'auditTo')), {
    target: { value: values.to },
  });
  fireEvent.click(screen.getByRole('button', { name: adminText('en', 'auditApplyFilters') }));
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('AuditPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<ReturnType<typeof page>>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<AuditPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve(page());
    expect(await screen.findByText('Synthetic configuration review')).toBeTruthy();
  });

  it('shows a clear no-events state with a way to reset the filters', async () => {
    sendMock.mockResolvedValueOnce(page([]));
    sendMock.mockResolvedValueOnce(page([]));
    sendMock.mockResolvedValueOnce(page());
    wrap(<AuditPage />);
    expect(await screen.findByText(adminText('en', 'auditNoEvents'))).toBeTruthy();
    expect(screen.getByText(adminText('en', 'auditNoEventsHint'))).toBeTruthy();

    fillFilters({ action: 'CONFIG_CREATE' });
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    fireEvent.click(
      within(screen.getByRole('status', { name: adminText('en', 'auditNoEvents') })).getByRole(
        'button',
        { name: adminText('en', 'auditClearFilters') },
      ),
    );

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(await screen.findByText('Synthetic configuration review')).toBeTruthy();
    expect(screen.queryByText(adminText('en', 'auditNoEvents'))).toBeNull();
    expect(
      screen.getByLabelText<HTMLInputElement>(adminText('en', 'auditActionFilter')).value,
    ).toBe('');
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));
      wrap(<AuditPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<AuditPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<AuditPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows events in the facility zone with a UTC hint and no fabricated chain verdict', async () => {
    sendMock.mockResolvedValueOnce(page());
    wrap(<AuditPage />);
    expect(await screen.findByText('Synthetic configuration review')).toBeTruthy();
    expect(
      screen.getByText(adminText('en', 'auditFacilityZone').replace('{zone}', 'America/New_York')),
    ).toBeTruthy();
    expect(
      screen.getByText(formatInstant('2026-03-08T06:30:00Z', 'America/New_York', 'en')),
    ).toBeTruthy();
    expect(screen.queryByTestId('chain-badge')).toBeNull();
  });

  it('renders the filter form and events under RTL in Arabic', async () => {
    sendMock.mockResolvedValueOnce(page());
    wrap(<AuditPage />, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: adminText('ar', 'auditEvents') })).toBeTruthy();
    expect(screen.getByRole('button', { name: adminText('ar', 'auditApplyFilters') })).toBeTruthy();
  });
});

describe('AuditPage filters and paging', () => {
  it('submits the exact contract query string for the supplied filters', async () => {
    sendMock.mockResolvedValue(page());
    wrap(<AuditPage />);
    await screen.findByText('Synthetic configuration review');

    fillFilters();

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).method).toBe('GET');
    expect(descriptorAt(1).url).toBe(
      '/api/v1/audit-events?action=CONFIG_CREATE&outcome=DENIED&resourceType=configuration' +
        '&from=2026-03-01T00%3A00%3A00Z&to=2026-03-08T00%3A00%3A00Z',
    );
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('blocks an invalid instant with a linked error and no query', async () => {
    sendMock.mockResolvedValue(page());
    wrap(<AuditPage />);
    await screen.findByText('Synthetic configuration review');

    fillFilters({ from: 'next tuesday' });

    expect(screen.getByRole('alert', { name: adminText('en', 'errorSummary') })).toBeTruthy();
    expect(screen.getByText(adminText('en', 'invalidAuditInstant'))).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('treats a page without an events field as empty', async () => {
    sendMock.mockResolvedValueOnce({ nextCursor: null });
    wrap(<AuditPage />);
    expect(await screen.findByText(adminText('en', 'auditNoEvents'))).toBeTruthy();
  });

  it('applies an empty filter set without sending a redundant query', async () => {
    sendMock.mockResolvedValueOnce(page());
    wrap(<AuditPage />);
    await screen.findByText('Synthetic configuration review');

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'auditApplyFilters') }));

    expect(sendMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('links an invalid upper-bound instant to the to field', async () => {
    sendMock.mockResolvedValue(page());
    wrap(<AuditPage />);
    await screen.findByText('Synthetic configuration review');

    fillFilters({ to: 'tomorrow' });

    const summary = screen.getByRole('alert', { name: adminText('en', 'errorSummary') });
    expect(summary.querySelector('a[href="#audit-to"]')).toBeTruthy();
    expect(screen.getByText(adminText('en', 'invalidAuditInstant'))).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('appends the next page while a cursor exists', async () => {
    sendMock.mockResolvedValueOnce(page([event()], 'seq-2'));
    sendMock.mockResolvedValueOnce(
      page([
        event({ id: 'audit-2', seq: 2, action: 'AUDIT_READ', purpose: 'Synthetic second page' }),
      ]),
    );
    wrap(<AuditPage />);
    await screen.findByText('Synthetic configuration review');

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'loadMore') }));

    expect(await screen.findByText('Synthetic second page')).toBeTruthy();
    expect(screen.getByText('Synthetic configuration review')).toBeTruthy();
    expect(descriptorAt(1).url).toContain('cursor=seq-2');
    expect(screen.queryByRole('button', { name: adminText('en', 'loadMore') })).toBeNull();
  });

  it('drops accumulated rows and the cursor when filters change', async () => {
    sendMock.mockResolvedValueOnce(page([event()], 'seq-2'));
    sendMock.mockResolvedValueOnce(
      page([event({ id: 'audit-2', seq: 2, purpose: 'Synthetic second page' })]),
    );
    sendMock.mockResolvedValueOnce(page([event({ purpose: 'Synthetic filtered page' })]));
    wrap(<AuditPage />);
    await screen.findByText('Synthetic configuration review');
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'loadMore') }));
    expect(await screen.findByText('Synthetic second page')).toBeTruthy();

    fillFilters();

    expect(await screen.findByText('Synthetic filtered page')).toBeTruthy();
    expect(screen.queryByText('Synthetic second page')).toBeNull();
    expect(descriptorAt(2).url).not.toContain('cursor=');
  });
});

describe('audit role visibility and routes', () => {
  it('shows the audit item only to the privacy auditor and the tenant admin', () => {
    expect(navigationFor('privacy_auditor').map((item) => item.route)).toContain('/admin/audit');
    expect(navigationFor('tenant_admin').map((item) => item.route)).toContain('/admin/audit');
    expect(navigationFor('reception').map((item) => item.route)).not.toContain('/admin/audit');
    expect(navigationFor('clinician').map((item) => item.route)).not.toContain('/admin/audit');
  });

  it('registers the audit path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/admin/audit');
    expect(patterns.indexOf('/admin/audit')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered audit page with live data', async () => {
    sendMock.mockResolvedValueOnce(page());
    const route = must(
      createRoutes(false).find((candidate) => candidate.pattern === '/admin/audit'),
    );
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );
    expect(await screen.findByText('Synthetic configuration review')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/audit-events');
  });

  it('drops the previous tenant rows when the organization changes', async () => {
    sendMock.mockResolvedValue(page());
    const view = wrap(<AuditPage />, alphaOrganizationId);
    await screen.findByText('Synthetic configuration review');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    view.rerender(element(<AuditPage />, betaOrganizationId));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});
