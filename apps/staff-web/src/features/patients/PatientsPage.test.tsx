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
import { alphaOrganizationId, arabicPatientResponse, syntheticAlphaPatients } from './fixtures';
import { PatientsPage } from './PatientsPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
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

function fillRegistration() {
  fireEvent.change(screen.getByLabelText('Display name'), {
    target: { value: 'Synthetic New Patient' },
  });
  fireEvent.change(screen.getByLabelText('MRN'), { target: { value: 'MRN-2001' } });
  fireEvent.change(screen.getByLabelText('MRN issuer'), { target: { value: 'SYNTHETIC' } });
  fireEvent.change(screen.getByLabelText('Date of birth (YYYY-MM-DD)'), {
    target: { value: '1992-08-03' },
  });
  fireEvent.change(screen.getByLabelText('Language code'), { target: { value: 'en' } });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('PatientsPage states', () => {
  it('shows the loading state until the first search resolves', async () => {
    const pending = deferred<typeof syntheticAlphaPatients>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'Layla' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve(syntheticAlphaPatients);
    expect(await screen.findByText('Layla Haddad')).toBeTruthy();
  });

  it('shows the empty state for an empty search result', async () => {
    sendMock.mockResolvedValueOnce([]);
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'Nobody' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));
      wrap(<PatientsPage />);
      fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
        target: { value: 'Layla' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Search' }));

      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'Layla' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'Layla' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('PatientsPage search', () => {
  it('rejects a name shorter than two characters before any request is sent', async () => {
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: ' A ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('Enter at least two characters.')).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('renders a result row with name, MRN, issuer and date of birth', async () => {
    sendMock.mockResolvedValueOnce([syntheticAlphaPatients[0]]);
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'Layla' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const table = await screen.findByRole('table', { name: 'Patient search results' });
    expect(within(table).getByText('Layla Haddad')).toBeTruthy();
    expect(within(table).getByText('MRN-1001')).toBeTruthy();
    expect(within(table).getByText('SYNTHETIC')).toBeTruthy();
    expect(within(table).getByText('May 12, 1990')).toBeTruthy();
    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/patients?name=Layla');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(within(table).getByRole('link', { name: 'Layla Haddad' }).getAttribute('href')).toBe(
      '/patients/patient-a1',
    );
  });

  it('renders an Arabic name as an isolated bidirectional run', async () => {
    sendMock.mockResolvedValueOnce([arabicPatientResponse]);
    wrap(<PatientsPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'ليلى' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const name = await screen.findByText('ليلى حداد');
    expect(name.tagName).toBe('BDI');
    expect(name.getAttribute('dir')).toBe('auto');
    expect(name.getAttribute('lang')).toBe('ar');
    expect(await screen.findByText('Not disclosed')).toBeTruthy();
  });

  it('renders the table and Arabic labels under RTL', async () => {
    sendMock.mockResolvedValueOnce([syntheticAlphaPatients[0]]);
    wrap(<PatientsPage />, alphaOrganizationId, 'ar');
    fireEvent.change(screen.getByRole('textbox', { name: 'ابحث بالاسم' }), {
      target: { value: 'ليلى' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'بحث' }));

    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'نتائج البحث عن المرضى' })).toBeTruthy();
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockResolvedValueOnce([syntheticAlphaPatients[0]]);
    const view = wrap(<PatientsPage />, alphaOrganizationId);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by name' }), {
      target: { value: 'Layla' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Layla Haddad')).toBeTruthy();

    view.rerender(element(<PatientsPage />, '22222222-2222-2222-2222-222222222222'));
    expect(screen.queryByText('Layla Haddad')).toBeNull();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });
});

describe('PatientsPage registration', () => {
  it('shows the linked error summary and sends no command for an empty form', () => {
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    const summary = screen.getByRole('alert', { name: 'Fix the following fields' });
    for (const id of [
      'patient-displayName',
      'patient-mrn',
      'patient-issuer',
      'patient-dob',
      'patient-language',
    ]) {
      expect(summary.querySelector(`a[href="#${id}"]`)).toBeTruthy();
    }
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('links a malformed unknown-date reason to its own field', () => {
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.change(screen.getByLabelText('Date of birth (YYYY-MM-DD)'), {
      target: { value: '' },
    });
    fireEvent.change(screen.getByLabelText('Unknown-date reason (code)'), {
      target: { value: 'NOT VALID' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    const summary = screen.getByRole('alert', { name: 'Fix the following fields' });
    expect(summary.querySelector('a[href="#patient-dobUnknownReason"]')).toBeTruthy();
    expect(screen.getByLabelText('Unknown-date reason (code)').getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('posts a registration and announces the created patient', async () => {
    sendMock.mockResolvedValueOnce({
      ...required(syntheticAlphaPatients[0]),
      displayName: 'Synthetic New Patient',
    });
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(1));
    expect(screen.getByText('Registered Synthetic New Patient')).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: 'Register patient' })).toBeNull();
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/patients');
    expect(descriptor.body?.mrn).toBe('MRN-2001');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('registers with a coded unknown-date reason instead of a date of birth', async () => {
    sendMock.mockResolvedValueOnce({
      ...required(syntheticAlphaPatients[0]),
      displayName: 'Synthetic Reason Patient',
    });
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'Synthetic Reason Patient' },
    });
    fireEvent.change(screen.getByLabelText('MRN'), { target: { value: 'MRN-2002' } });
    fireEvent.change(screen.getByLabelText('MRN issuer'), { target: { value: 'SYNTHETIC' } });
    fireEvent.change(screen.getByLabelText('Unknown-date reason (code)'), {
      target: { value: 'not_known' },
    });
    fireEvent.change(screen.getByLabelText('Language code'), { target: { value: 'ar' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(1));
    expect(screen.getByText('Registered Synthetic Reason Patient')).toBeTruthy();
    expect(descriptorAt(0).body?.dob).toBeNull();
    expect(descriptorAt(0).body?.dobUnknownReason).toBe('not_known');
  });
});

describe('PatientsPage registration failures', () => {
  it('sends only one registration for two rapid submits', async () => {
    const pending = deferred<(typeof syntheticAlphaPatients)[number]>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    const submit = screen.getByRole('button', { name: 'Save registration' });
    fireEvent.click(submit);
    fireEvent.click(submit);

    expect(sendMock).toHaveBeenCalledTimes(1);
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    pending.resolve({
      ...required(syntheticAlphaPatients[0]),
      displayName: 'Synthetic New Patient',
    });
    expect(await screen.findByText('Registered Synthetic New Patient')).toBeTruthy();
  });

  it('links a server validation code to its field and keeps the typed values', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('invalid', 'INVALID_MRN'));
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    const summary = await screen.findByRole('alert', { name: 'Fix the following fields' });
    expect(summary.querySelector('a[href="#patient-mrn"]')).toBeTruthy();
    expect(screen.getByLabelText('MRN').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByLabelText<HTMLInputElement>('Display name').value).toBe(
      'Synthetic New Patient',
    );
    expect(screen.queryByText(stateViewText('en', 'error'))).toBeNull();
  });

  it('falls back to the error state for an unmapped server validation code', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('invalid', 'UNKNOWN_CODE'));
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-invalid')).toBeTruthy();
    expect(screen.queryByRole('alert', { name: 'Fix the following fields' })).toBeNull();
  });

  it('shows the conflict state for a 409 without losing the typed form', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Display name').value).toBe(
      'Synthetic New Patient',
    );
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('normalizes an unexpected rejection into a typed error state', async () => {
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.click(screen.getByRole('button', { name: 'Save registration' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('closes the form without sending when cancelled', () => {
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog', { name: 'Register patient' })).toBeNull();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('activates the primary form action with a native submit (keyboard equivalent)', async () => {
    sendMock.mockResolvedValueOnce(syntheticAlphaPatients[0]);
    wrap(<PatientsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    fillRegistration();
    const submit = screen.getByRole<HTMLButtonElement>('button', { name: 'Save registration' });
    expect(submit.type).toBe('submit');
    fireEvent.submit(required(submit.closest('form')));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(1));
  });
});

describe('patients routes', () => {
  it('registers both patient paths before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/patients');
    expect(patterns).toContain('/patients/:id');
    expect(patterns.indexOf('/patients')).toBeLessThan(patterns.indexOf('*'));
    expect(patterns.indexOf('/patients/:id')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered patients page', async () => {
    const route = required(
      createRoutes(false).find((candidate) => candidate.pattern === '/patients'),
    );
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );

    expect(await screen.findByRole('textbox', { name: 'Search by name' })).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
