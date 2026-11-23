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
import type { StaffHistoryResponse, StaffPageResponse } from './api';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { StaffPage } from './StaffPage';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function staffPage(
  items: StaffPageResponse['items'] = [],
  nextCursor: string | null = null,
): StaffPageResponse {
  return { items, nextCursor };
}

function member(
  overrides: Partial<StaffPageResponse['items'][number]> = {},
): StaffPageResponse['items'][number] {
  return {
    subject: 'synthetic.reception',
    displayName: 'Synthetic Reception',
    status: 'active',
    roles: ['reception'],
    ...overrides,
  };
}

function historyView(
  memberships: StaffHistoryResponse['memberships'] = [
    {
      id: 'membership-1',
      status: 'active',
      validFrom: '2026-01-05T08:00:00Z',
      validTo: null,
      createdAt: '2026-01-05T08:00:00Z',
    },
  ],
  roleGrants: StaffHistoryResponse['roleGrants'] = [
    {
      id: 'grant-1',
      roleKey: 'reception',
      grantedBy: 'Synthetic alpha admin A',
      validFrom: '2026-01-06T08:00:00Z',
      validTo: null,
    },
  ],
): StaffHistoryResponse {
  return { subject: 'synthetic.reception', memberships, roleGrants };
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

function fillInviteForm() {
  fireEvent.change(screen.getByLabelText('Staff subject'), {
    target: { value: 'synthetic.auditor' },
  });
  fireEvent.change(screen.getByLabelText('Display name'), {
    target: { value: 'Synthetic Auditor' },
  });
  fireEvent.change(screen.getByLabelText('Bounded role'), { target: { value: 'privacy_auditor' } });
}

function openConfirmation() {
  fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
  return screen.getByRole('dialog', { name: 'Deactivate Synthetic Reception?' });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('StaffPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<StaffPageResponse>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<StaffPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(staffPage([member()]));
    expect(await screen.findByText('Synthetic Reception')).toBeTruthy();
  });

  it('shows the empty state for an empty first page', async () => {
    sendMock.mockResolvedValueOnce(staffPage());

    wrap(<StaffPage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));

      wrap(<StaffPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));

    wrap(<StaffPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));

    wrap(<StaffPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('renders the table and Arabic labels under RTL', async () => {
    sendMock.mockResolvedValueOnce(staffPage([member()]));

    wrap(<StaffPage />, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'إدارة الموظفين' })).toBeTruthy();
  });
});

describe('StaffPage invitation', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(staffPage([member()]));
  });

  it('invites through the API and reloads the list after success', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    fillInviteForm();
    sendMock.mockResolvedValueOnce(
      member({
        subject: 'synthetic.auditor',
        displayName: 'Synthetic Auditor',
        status: 'active',
        roles: ['privacy_auditor'],
      }),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Invite staff' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/staff');
    expect(command.body).toEqual({
      subject: 'synthetic.auditor',
      displayName: 'Synthetic Auditor',
      role: 'privacy_auditor',
    });
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptorAt(2).method).toBe('GET');
    expect(await screen.findByText('Invited Synthetic Auditor')).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Staff subject').value).toBe('');
  });

  it('shows a linked error summary and sends no command for an invalid draft', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    fireEvent.click(screen.getByRole('button', { name: 'Invite staff' }));

    expect(sendMock).toHaveBeenCalledTimes(1);
    const summary = screen.getByRole('alert', { name: 'Fix the following fields' });
    expect(summary.querySelector('a[href="#staff-subject"]')).toBeTruthy();
    expect(summary.querySelector('a[href="#staff-displayName"]')).toBeTruthy();
    expect(summary.querySelector('a[href="#staff-role"]')).toBeTruthy();
    expect(screen.getByLabelText('Staff subject').getAttribute('aria-invalid')).toBe('true');
  });

  it('links a server role error to the role field', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    fillInviteForm();
    sendMock.mockRejectedValueOnce(transportFailure('invalid', 'INVALID_ROLE'));

    fireEvent.click(screen.getByRole('button', { name: 'Invite staff' }));

    expect(await screen.findByRole('alert', { name: 'Fix the following fields' })).toBeTruthy();
    expect(screen.getByLabelText('Bounded role').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByLabelText('Staff subject').getAttribute('aria-invalid')).toBeNull();
    expect(screen.queryByText(stateViewText('en', 'error'))).toBeNull();
  });

  it('falls back to the error state for an unmapped server validation code', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    fillInviteForm();
    sendMock.mockRejectedValueOnce(transportFailure('invalid', 'UNKNOWN_CODE'));

    fireEvent.click(screen.getByRole('button', { name: 'Invite staff' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-invalid')).toBeTruthy();
    expect(screen.queryByRole('alert', { name: 'Fix the following fields' })).toBeNull();
  });

  it('shows the conflict state for a duplicate subject without losing the form', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    fillInviteForm();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: 'Invite staff' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Staff subject').value).toBe(
      'synthetic.auditor',
    );
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('normalizes an unexpected invitation rejection into a typed error state', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    fillInviteForm();
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));

    fireEvent.click(screen.getByRole('button', { name: 'Invite staff' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('activates the primary action with a native form submit (keyboard equivalent)', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    fillInviteForm();

    const submit = screen.getByRole<HTMLButtonElement>('button', { name: 'Invite staff' });
    expect(submit.type).toBe('submit');
    fireEvent.submit(must(submit.closest<HTMLElement>('form')));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
  });
});

describe('StaffPage deactivation', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(staffPage([member()]));
  });

  it('cancels without sending a command and moves focus to the safe choice', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    const dialog = openConfirmation();
    expect(within(dialog).getByText(/This appends a revocation/)).toBeTruthy();
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' });
    expect(document.activeElement).toBe(cancel);

    fireEvent.click(cancel);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('requires a reason before deactivating', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    const dialog = openConfirmation();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(await screen.findByRole('alert', { name: 'Fix the following fields' })).toBeTruthy();
    expect(screen.getByLabelText('Reason for deactivation').getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('deactivates with a reason and reloads the list after confirmation', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    const dialog = openConfirmation();
    fireEvent.change(within(dialog).getByLabelText('Reason for deactivation'), {
      target: { value: 'Synthetic offboarding' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/staff/synthetic.reception/deactivate');
    expect(command.body).toBeUndefined();
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptorAt(2).method).toBe('GET');
    expect(
      await screen.findByText(
        'Deactivated Synthetic Reception. Reason entered: Synthetic offboarding (not stored by the API)',
      ),
    ).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows the conflict state for an already-deactivated member and keeps the dialog', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    const dialog = openConfirmation();
    fireEvent.change(within(dialog).getByLabelText('Reason for deactivation'), {
      target: { value: 'Synthetic offboarding' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('shows the forbidden state when deactivation is denied', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    const dialog = openConfirmation();
    fireEvent.change(within(dialog).getByLabelText('Reason for deactivation'), {
      target: { value: 'Synthetic offboarding' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('normalizes an unexpected deactivation rejection into a typed error state', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');

    const dialog = openConfirmation();
    fireEvent.change(within(dialog).getByLabelText('Reason for deactivation'), {
      target: { value: 'Synthetic offboarding' },
    });
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('never offers deactivation for an already deactivated member', async () => {
    sendMock.mockResolvedValue(staffPage([member({ status: 'deactivated', roles: [] })]));

    wrap(<StaffPage />);
    expect(await screen.findByText('Synthetic Reception')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Deactivate' })).toBeNull();
    expect(screen.getByRole('button', { name: 'History' })).toBeTruthy();
  });
});

describe('StaffPage history', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(staffPage([member()]));
  });

  it('loads and renders the immutable history for the selected subject', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    sendMock.mockResolvedValueOnce(historyView());

    fireEvent.click(screen.getByRole('button', { name: 'History' }));

    expect(
      await screen.findByRole('table', { name: 'Staff membership and role-grant history' }),
    ).toBeTruthy();
    expect(
      within(
        screen.getByRole('table', { name: 'Staff membership and role-grant history' }),
      ).getByText('reception'),
    ).toBeTruthy();
    const command = descriptorAt(1);
    expect(command.method).toBe('GET');
    expect(command.url).toBe('/api/v1/staff/synthetic.reception/history');
  });

  it('shows the empty state when the subject has no history', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    sendMock.mockResolvedValueOnce(historyView([], []));

    fireEvent.click(screen.getByRole('button', { name: 'History' }));

    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it('shows the forbidden state when history access is denied', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));

    fireEvent.click(screen.getByRole('button', { name: 'History' }));

    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('labels the API fallback reason in the active locale under RTL', async () => {
    wrap(<StaffPage />, alphaOrganizationId, 'ar');
    await screen.findByText('Synthetic Reception');
    sendMock.mockResolvedValueOnce(
      historyView(
        [
          {
            id: 'membership-2',
            status: 'revoked',
            validFrom: '2026-03-01T08:00:00Z',
            validTo: null,
            createdAt: '2026-03-01T08:00:00Z',
          },
        ],
        [],
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: 'السجل' }));

    const reason = await screen.findByText('لم تسجّل الخدمة سببًا');
    expect(reason.getAttribute('lang')).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('closes the history panel', async () => {
    wrap(<StaffPage />);
    await screen.findByText('Synthetic Reception');
    sendMock.mockResolvedValueOnce(historyView());

    fireEvent.click(screen.getByRole('button', { name: 'History' }));
    expect(
      await screen.findByRole('table', { name: 'Staff membership and role-grant history' }),
    ).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Close history' }));
    expect(
      screen.queryByRole('table', { name: 'Staff membership and role-grant history' }),
    ).toBeNull();
  });
});

describe('StaffPage paging and tenant switching', () => {
  it('appends the next page while a cursor exists', async () => {
    sendMock.mockResolvedValueOnce(staffPage([member()], 'cursor-2'));
    sendMock.mockResolvedValueOnce(
      staffPage([member({ subject: 'synthetic.auditor', displayName: 'Synthetic Auditor' })]),
    );

    wrap(<StaffPage />);
    expect(await screen.findByText('Synthetic Reception')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('Synthetic Auditor')).toBeTruthy();
    expect(screen.getByText('Synthetic Reception')).toBeTruthy();
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockResolvedValueOnce(staffPage([member()]));
    sendMock.mockResolvedValue(staffPage());

    const view = wrap(<StaffPage />, alphaOrganizationId);
    expect(await screen.findByText('Synthetic Reception')).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    view.rerender(element(<StaffPage />, betaOrganizationId));
    expect(screen.queryByText('Synthetic Reception')).toBeNull();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});

describe('admin staff routes', () => {
  it('registers the staff path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/admin/staff');
    expect(patterns.indexOf('/admin/staff')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered staff page with live data', async () => {
    sendMock.mockResolvedValue(staffPage([member()]));
    const route = must(
      createRoutes(false).find((candidate) => candidate.pattern === '/admin/staff'),
    );
    const Page = route.component;

    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );

    expect(await screen.findByText('Synthetic Reception')).toBeTruthy();
  });
});
