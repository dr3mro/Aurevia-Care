import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { policyText } from './messages';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { PolicyPage } from './PolicyPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** One configuration history entry. */
function entry(overrides: Record<string, unknown> = {}) {
  return {
    key: 'template.soap',
    version: 2,
    value: 'Synthetic SOAP rule',
    status: 'approved',
    effectiveFrom: '2026-03-01T00:00:00Z',
    approvedBy: 'synthetic.governance',
    ...overrides,
  };
}

/** Route a mocked descriptor to the synthetic response its path expects. */
function route(overrides: Record<string, unknown> = {}) {
  sendMock.mockImplementation((descriptor: RequestDescriptor) => {
    const url = descriptor.url;
    if (url.includes('/catalogs/')) {
      return Promise.resolve([
        { family: 'obstetric_history', code: 'LMP', version: 1, value: 'Synthetic LMP' },
      ]);
    }
    if (url.includes('/history')) {
      return Promise.resolve({
        key: url.includes('release') ? 'release.hold' : 'template.soap',
        entries: [
          entry({ ...overrides }),
          entry({ version: 3, status: 'pending', value: 'Synthetic draft', approvedBy: null }),
        ],
      });
    }
    return Promise.resolve({ version: 4 });
  });
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

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0]) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function review() {
  fireEvent.click(screen.getByRole('button', { name: policyText('en', 'review') }));
}

function fillEditor(approver = 'Synthetic named approver') {
  fireEvent.change(screen.getByLabelText(policyText('en', 'proposedValue')), {
    target: { value: 'Synthetic new rule' },
  });
  fireEvent.change(screen.getByLabelText(policyText('en', 'approverName')), {
    target: { value: approver },
  });
  fireEvent.change(screen.getByLabelText(policyText('en', 'effectiveFrom')), {
    target: { value: '2026-07-01' },
  });
}

beforeEach(() => {
  sendMock.mockReset();
  document.documentElement.dir = 'ltr';
});

describe('PolicyPage lookup and states', () => {
  it('makes no request until a key is reviewed', () => {
    wrap(<PolicyPage />);
    expect(screen.getByText(policyText('en', 'pageTitle'))).toBeTruthy();
    expect(screen.getByRole('button', { name: policyText('en', 'areaTemplates') })).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('refuses an empty key and an invalid configuration key', () => {
    wrap(<PolicyPage />);
    fireEvent.change(screen.getByLabelText(policyText('en', 'resourceKey')), { target: { value: '' } });
    review();
    expect(screen.getByText(policyText('en', 'keyRequired'))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(policyText('en', 'resourceKey')), { target: { value: '!' } });
    review();
    expect(screen.getByText(policyText('en', 'invalidPolicyKey'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('refuses an invalid catalog family after switching area', () => {
    wrap(<PolicyPage />);
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'areaDictionaries') }));
    fireEvent.change(screen.getByLabelText(policyText('en', 'dictionaryFamily')), { target: { value: 'Upper' } });
    review();
    expect(screen.getByText(policyText('en', 'invalidDictionaryFamily'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('shows loading then the template history and its approver', async () => {
    let resolve!: (value: unknown) => void;
    sendMock.mockReturnValueOnce(new Promise((res) => { resolve = res; }));
    wrap(<PolicyPage />);
    review();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    resolve({ key: 'template.soap', entries: [entry()] });
    expect(await screen.findByText('Synthetic SOAP rule')).toBeTruthy();
    expect(screen.getByText('synthetic.governance')).toBeTruthy();
  });

  it('shows the correlation id for a transport error', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    wrap(<PolicyPage />);
    review();
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('corr-server')).toBeTruthy();
  });

  it('shows an explicit empty history', async () => {
    sendMock.mockResolvedValueOnce({ key: 'template.soap', entries: [] });
    wrap(<PolicyPage />);
    review();
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'empty') })).toBeTruthy();
  });

  it('renders in Arabic with a right-to-left document', async () => {
    route();
    wrap(<PolicyPage />, alphaOrganizationId, 'ar');
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'));
    expect(screen.getByText(policyText('ar', 'pageTitle'))).toBeTruthy();
  });
});

describe('PolicyPage commands', () => {
  it('proposes a template version and refreshes the history', async () => {
    route();
    wrap(<PolicyPage />);
    review();
    expect(await screen.findByText('Synthetic SOAP rule')).toBeTruthy();
    fillEditor();
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'proposeVersion') }));
    await waitFor(() => expect(screen.getByTestId('policy-status').textContent).toBe(policyText('en', 'changeSaved')));
    const writes = sendMock.mock.calls.filter(([descriptor]) => descriptor.method === 'POST');
    expect(writes.length).toBeGreaterThan(0);
  });

  it('refuses a template change without an approver before writing', async () => {
    route();
    wrap(<PolicyPage />);
    review();
    expect(await screen.findByText('Synthetic SOAP rule')).toBeTruthy();
    fillEditor('');
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'proposeVersion') }));
    expect(screen.getByText(policyText('en', 'approverRequired'))).toBeTruthy();
    expect(sendMock.mock.calls.filter(([descriptor]) => descriptor.method === 'POST')).toHaveLength(0);
  });

  it('shows a transport failure for a refused command', async () => {
    route();
    wrap(<PolicyPage />);
    review();
    expect(await screen.findByText('Synthetic SOAP rule')).toBeTruthy();
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    fillEditor();
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'proposeVersion') }));
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
  });

  it('defines a dictionary field after reviewing the catalog family', async () => {
    route();
    wrap(<PolicyPage />);
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'areaDictionaries') }));
    review();
    expect(await screen.findByText('Synthetic LMP')).toBeTruthy();
    fillEditor();
    fireEvent.change(screen.getByLabelText(policyText('en', 'fieldCode')), { target: { value: 'GRAVIDA' } });
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'defineField') }));
    await waitFor(() => expect(screen.getByTestId('policy-status').textContent).toBe(policyText('en', 'changeSaved')));
  });

  it('sets a held release policy with a reason and review date', async () => {
    route();
    wrap(<PolicyPage />);
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'areaRelease') }));
    review();
    expect(await screen.findByText(policyText('en', 'currentAccess'))).toBeTruthy();
    fillEditor();
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'modeHold') }));
    fireEvent.change(screen.getByLabelText(policyText('en', 'holdReason')), { target: { value: 'Synthetic specialist review' } });
    fireEvent.change(screen.getByLabelText(policyText('en', 'holdExpiry')), { target: { value: '2026-08-01' } });
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'setPolicy') }));
    await waitFor(() => expect(screen.getByTestId('policy-status').textContent).toBe(policyText('en', 'changeSaved')));
  });

  it('opens an approval dialog and appends a named approval', async () => {
    route();
    wrap(<PolicyPage />);
    review();
    fireEvent.click(await screen.findByRole('button', { name: `${policyText('en', 'approve')} 3` }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeTruthy();
    fireEvent.change(screen.getByLabelText(policyText('en', 'approveNote')), { target: { value: 'Synthetic approval note' } });
    fireEvent.click(withinDialog(dialog, policyText('en', 'approve')));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('closes the approval dialog on cancel without writing', async () => {
    route();
    wrap(<PolicyPage />);
    review();
    fireEvent.click(await screen.findByRole('button', { name: `${policyText('en', 'approve')} 3` }));
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sendMock.mock.calls.filter(([descriptor]) => descriptor.url.includes('/approve'))).toHaveLength(0);
  });

  it('opens an approval dialog from the release-policy screen', async () => {
    route();
    wrap(<PolicyPage />);
    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'areaRelease') }));
    review();
    fireEvent.click(await screen.findByRole('button', { name: `${policyText('en', 'approve')} 3` }));
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

/** Find a button by exact label inside one dialog. */
function withinDialog(dialog: HTMLElement, label: string) {
  const button = Array.from(dialog.querySelectorAll('button')).find((item) => item.textContent === label);
  if (button === undefined) {
    throw new Error(`button '${label}' not found in dialog`);
  }
  return button;
}

describe('PolicyPage tenant scope', () => {
  it('clears the reviewed key when the active organization changes', async () => {
    route();
    const view = wrap(<PolicyPage />);
    review();
    expect(await screen.findByText('Synthetic SOAP rule')).toBeTruthy();
    view.rerender(element(<PolicyPage />, betaOrganizationId));
    expect(screen.queryByText('Synthetic SOAP rule')).toBeNull();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });
});
