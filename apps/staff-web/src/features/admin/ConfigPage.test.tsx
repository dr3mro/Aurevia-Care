import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { adminText } from './messages';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { ConfigPage } from './ConfigPage';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const key = 'facility.MAIN.booking';

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
}

function entry(overrides: Record<string, unknown> = {}) {
  return {
    key,
    version: 2,
    value: 'Synthetic booking rule',
    status: 'approved',
    effectiveFrom: '2026-03-01T08:00:00Z',
    approvedBy: 'synthetic.approver',
    ...overrides,
  };
}

function history(entries: Record<string, unknown>[] = [entry()]) {
  return { key, entries };
}

function catalogEntry(code: string) {
  return { family: 'diagnosis', code, version: 1, value: `Synthetic ${code}` };
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

function lookup() {
  fireEvent.change(screen.getByLabelText(adminText('en', 'configurationKeyLookup')), {
    target: { value: key },
  });
  fireEvent.click(screen.getByRole('button', { name: adminText('en', 'configLookup') }));
}

function fillDefineForm() {
  fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
    target: { value: 'Synthetic proposed value' },
  });
  fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
    target: { value: '2026-07-01' },
  });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('ConfigPage states', () => {
  it('prompts for a key before making any request', () => {
    wrap(<ConfigPage />);
    expect(screen.getByText(adminText('en', 'configLookupIntro'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('rejects an invalid key without a request', () => {
    wrap(<ConfigPage />);
    fireEvent.change(screen.getByLabelText(adminText('en', 'configurationKeyLookup')), {
      target: { value: '!' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'configLookup') }));
    expect(screen.getByText(adminText('en', 'invalidConfigKey'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('shows the loading state until history resolves', async () => {
    const pending = deferred<ReturnType<typeof history>>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<ConfigPage />);
    lookup();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(history());
    expect(await screen.findByText('Synthetic booking rule')).toBeTruthy();
  });

  it('shows the no-versions state for a key with no history', async () => {
    sendMock.mockResolvedValueOnce(history([]));
    wrap(<ConfigPage />);
    lookup();
    expect(await screen.findByText(adminText('en', 'noVersionsForKey'))).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));
      wrap(<ConfigPage />);
      lookup();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<ConfigPage />);
    lookup();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<ConfigPage />);
    lookup();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows approval state and an approve trigger on a pending version', async () => {
    sendMock.mockResolvedValueOnce(
      history([
        entry(),
        entry({ version: 3, status: 'pending', approvedBy: null, value: 'Synthetic pending rule' }),
      ]),
    );
    wrap(<ConfigPage />);
    lookup();
    expect(await screen.findByText('Synthetic booking rule')).toBeTruthy();
    expect(screen.getByText(adminText('en', 'effectiveBadge'))).toBeTruthy();
    expect(screen.getByRole('button', { name: adminText('en', 'approve') })).toBeTruthy();
  });

  it('renders the lookup and editor under RTL in Arabic', async () => {
    sendMock.mockResolvedValueOnce(history());
    wrap(<ConfigPage />, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    fireEvent.change(screen.getByLabelText(adminText('ar', 'configurationKeyLookup')), {
      target: { value: key },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('ar', 'configLookup') }));
    expect(
      await screen.findByRole('table', { name: adminText('ar', 'configurationVersions') }),
    ).toBeTruthy();
  });
});

describe('ConfigPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(
      history([entry(), entry({ version: 3, status: 'pending', value: 'Synthetic pending rule' })]),
    );
  });

  it('looks up a key with a tenant header and no tenant field in the path body', async () => {
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    expect(descriptorAt(0).url).toBe('/api/v1/configuration/facility.MAIN.booking/history');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptorAt(0).body).toBeUndefined();
  });

  it('defines a version through the API and reloads history after success', async () => {
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fillDefineForm();
    sendMock.mockResolvedValueOnce(entry({ version: 3, status: 'pending' }));

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/configuration');
    expect(command.body).toEqual({
      key,
      value: 'Synthetic proposed value',
      effectiveFrom: '2026-07-01T00:00:00Z',
    });
    expect(command.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptorAt(2).url).toContain('/history');
    expect(
      await screen.findByText(adminText('en', 'defineVersion').replace('{key}', key)),
    ).toBeTruthy();
  });

  it('shows the conflict state for a define conflict without dropping the draft', async () => {
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fillDefineForm();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue')).value).toBe(
      'Synthetic proposed value',
    );
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('requires confirmation before approving and sends nothing on cancel', async () => {
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    const callsBefore = sendMock.mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'approve') }));
    expect(
      screen.getByRole('dialog', {
        name: adminText('en', 'approveTitle').replace('{key}', key).replace('{version}', '3'),
      }),
    ).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(callsBefore);

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sendMock).toHaveBeenCalledTimes(callsBefore);
  });

  it('approves the confirmed version with a note and reloads history', async () => {
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'approve') }));
    fireEvent.change(screen.getByLabelText(adminText('en', 'approvalNote')), {
      target: { value: 'Synthetic approval note' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'approveConfirm') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/configuration/facility.MAIN.booking/versions/3/approve');
    expect(command.body).toEqual({ note: 'Synthetic approval note' });
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(
      await screen.findByText(
        adminText('en', 'approvedNotice').replace('{key}', key).replace('{version}', '3'),
      ),
    ).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps the dialog open and shows the conflict state when approval is rejected', async () => {
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'approve') }));
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'approveConfirm') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

describe('ConfigPage catalog section', () => {
  it('lists a family and defines a pending entry', async () => {
    sendMock.mockResolvedValueOnce(history());
    sendMock.mockResolvedValueOnce([catalogEntry('SYN')]);
    sendMock.mockResolvedValueOnce(entry({ key: 'diagnosis.SYN', version: 2, status: 'pending' }));
    sendMock.mockResolvedValueOnce([catalogEntry('SYN'), catalogEntry('LAB')]);

    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
    expect(await screen.findByText('Synthetic SYN')).toBeTruthy();
    expect(descriptorAt(1).url).toBe('/api/v1/catalogs/diagnosis');

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogCode')), {
      target: { value: 'LAB' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogValue')), {
      target: { value: 'Synthetic lab entry' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'defineCatalogEntry') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(4));
    expect(descriptorAt(2).method).toBe('POST');
    expect(descriptorAt(2).url).toBe('/api/v1/catalogs/diagnosis/entries');
    expect(descriptorAt(2).body).toEqual({ code: 'LAB', value: 'Synthetic lab entry' });
  });

  it('rejects an invalid family without a catalog request', async () => {
    sendMock.mockResolvedValue(history());
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'Bad-Family' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));

    expect(screen.getByText(adminText('en', 'invalidCatalogFamily'))).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('shows an empty catalog family', async () => {
    sendMock.mockResolvedValueOnce(history());
    sendMock.mockResolvedValueOnce([]);
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it('links each invalid catalog field and sends no command', async () => {
    sendMock.mockResolvedValueOnce(history());
    sendMock.mockResolvedValueOnce([catalogEntry('SYN')]);
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
    await screen.findByText('Synthetic SYN');

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'defineCatalogEntry') }));
    const summary = screen.getByRole('alert', { name: adminText('en', 'errorSummary') });
    expect(summary.querySelector('a[href="#catalog-code"]')).toBeTruthy();
    expect(summary.querySelector('a[href="#catalog-value"]')).toBeTruthy();
    expect(summary.querySelector('a[href="#catalog-effective"]')).toBeNull();

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogCode')), {
      target: { value: 'LAB' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogValue')), {
      target: { value: 'Synthetic lab entry' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogEffectiveFrom')), {
      target: { value: '2026-02-30' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'defineCatalogEntry') }));
    expect(
      screen
        .getByRole('alert', { name: adminText('en', 'errorSummary') })
        .querySelector('a[href="#catalog-effective"]'),
    ).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('sends a supplied catalog effective date as a UTC instant', async () => {
    sendMock.mockResolvedValueOnce(history());
    sendMock.mockResolvedValueOnce([catalogEntry('SYN')]);
    sendMock.mockResolvedValueOnce(entry());
    sendMock.mockResolvedValueOnce([catalogEntry('SYN'), catalogEntry('LAB')]);
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
    await screen.findByText('Synthetic SYN');

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogCode')), {
      target: { value: 'LAB' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogValue')), {
      target: { value: 'Synthetic lab entry' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogEffectiveFrom')), {
      target: { value: '2026-08-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'defineCatalogEntry') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(4));
    expect(descriptorAt(2).body).toEqual({
      code: 'LAB',
      value: 'Synthetic lab entry',
      effectiveFrom: '2026-08-01T00:00:00Z',
    });
  });

  it('shows a typed error state when a catalog command fails', async () => {
    sendMock.mockResolvedValueOnce(history());
    sendMock.mockResolvedValueOnce([catalogEntry('SYN')]);
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
    await screen.findByText('Synthetic SYN');

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogCode')), {
      target: { value: 'LAB' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogValue')), {
      target: { value: 'Synthetic lab entry' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'defineCatalogEntry') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-server')).toBeTruthy();
  });

  it('renders a sparse catalog entry with explicit fallbacks', async () => {
    sendMock.mockResolvedValueOnce(history());
    sendMock.mockResolvedValueOnce([{}]);
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText('Synthetic booking rule');
    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
    expect(
      await screen.findByRole('table', { name: adminText('en', 'catalogEntries') }),
    ).toBeTruthy();
    expect(
      within(screen.getByRole('table', { name: adminText('en', 'catalogEntries') })).getAllByRole(
        'cell',
      ),
    ).toHaveLength(2);
  });
});

describe('admin config routes and tenant scoping', () => {
  it('registers the config path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/admin/config');
    expect(patterns.indexOf('/admin/config')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered config page with live data', async () => {
    sendMock.mockResolvedValueOnce(history());
    const route = must(
      createRoutes(false).find((candidate) => candidate.pattern === '/admin/config'),
    );
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );
    fireEvent.change(await screen.findByLabelText(adminText('en', 'configurationKeyLookup')), {
      target: { value: key },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'configLookup') }));
    expect(await screen.findByText('Synthetic booking rule')).toBeTruthy();
    expect(within(screen.getByRole('table')).getByText('synthetic.approver')).toBeTruthy();
  });

  it('drops the previous tenant key and rows when the organization changes', async () => {
    sendMock.mockResolvedValue(history());
    const view = wrap(<ConfigPage />, alphaOrganizationId);
    lookup();
    await screen.findByText('Synthetic booking rule');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    view.rerender(element(<ConfigPage />, betaOrganizationId));
    expect(screen.queryByText('Synthetic booking rule')).toBeNull();
    expect(screen.getByText(adminText('en', 'configLookupIntro'))).toBeTruthy();
  });
});
