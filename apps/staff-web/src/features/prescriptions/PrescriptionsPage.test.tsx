import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { PrescriptionsPage } from './PrescriptionsPage';
import type { PrescriptionResponse } from './api';
import type { DrugCandidate } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  createSyntheticPrescription,
  syntheticCatalog,
  syntheticSubmissionId,
} from './fixtures';
import { prescriptionText } from './messages';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(ui: ReactElement, organizationId: string, locale: 'en' | 'ar' = 'en'): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: 'en' | 'ar' = 'en') {
  return render(element(ui, organizationId, locale));
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function postCalls(): RequestDescriptor[] {
  return sendMock.mock.calls
    .map((call) => call[0] as RequestDescriptor)
    .filter((descriptor) => descriptor.method === 'POST');
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = 'corr-1',
): TransportError {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function mockReady(catalog: readonly DrugCandidate[] = syntheticCatalog) {
  sendMock.mockImplementation(async (descriptor) => {
    const sent = descriptor as RequestDescriptor;
    if (sent.method === 'GET') {
      return catalog;
    }
    return createSyntheticPrescription();
  });
}

function mockCommandFailure(failure: unknown) {
  sendMock.mockImplementation(async (descriptor) => {
    const sent = descriptor as RequestDescriptor;
    if (sent.method === 'GET') {
      return syntheticCatalog;
    }
    throw failure;
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Give the allergy check an override reason so the form becomes submittable. */
function permitSubmit(reason = 'synthetic-override') {
  fireEvent.change(screen.getByLabelText(prescriptionText('en', 'overrideReason')), {
    target: { value: reason },
  });
}

async function readyScreen() {
  wrap(<PrescriptionsPage />);
  await screen.findByRole('heading', { name: prescriptionText('en', 'prescriptions') });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('PrescriptionsPage states', () => {
  it('shows the loading state until the catalog resolves', async () => {
    const pending = deferred<readonly DrugCandidate[]>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<PrescriptionsPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(syntheticCatalog);
    expect(await screen.findByRole('heading', { name: prescriptionText('en', 'prescriptions') })).toBeTruthy();
  });

  it('shows the empty state when no approved drug is available', async () => {
    mockReady([]);
    wrap(<PrescriptionsPage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByTestId('drug-option')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind, `corr-${kind}`));
      wrap(<PrescriptionsPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a forbidden catalog response', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<PrescriptionsPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });

  it('retries a failed catalog load from the error state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('offline', 'corr-catalog'));
    sendMock.mockResolvedValueOnce(syntheticCatalog);

    wrap(<PrescriptionsPage />);
    expect(await screen.findByText('corr-catalog')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));

    expect(await screen.findByRole('heading', { name: prescriptionText('en', 'prescriptions') })).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<PrescriptionsPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('renders Arabic RTL copy under the Arabic locale', async () => {
    mockReady();
    wrap(<PrescriptionsPage />, alphaOrganizationId, 'ar');
    expect(await screen.findByRole('heading', { name: prescriptionText('ar', 'prescriptions') })).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
  });
});

describe('PrescriptionsPage catalog and draft', () => {
  beforeEach(() => mockReady());

  it('surfaces an allergy warning and requires an override reason before submitting', async () => {
    await readyScreen();
    const warning = screen.getByTestId('allergy-warning');
    expect(warning.textContent).toContain(prescriptionText('en', 'allergyMatch'));
    const submit = screen.getByRole('button', { name: prescriptionText('en', 'submit') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    permitSubmit();
    expect((screen.getByRole('button', { name: prescriptionText('en', 'submit') }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('searches the approved catalog and filters the alternatives', async () => {
    await readyScreen();
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'searchQuery')), {
      target: { value: 'cefalexin' },
    });
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'search') }));
    await waitFor(() =>
      expect(
        sendMock.mock.calls
          .map((call) => (call[0] as RequestDescriptor).url)
          .some((url) => url.includes('q=cefalexin')),
      ).toBe(true),
    );
    await waitFor(() => expect(screen.getAllByTestId('drug-option')).toHaveLength(1));
    expect(postCalls()).toHaveLength(0);
  });

  it('selecting an alternative updates the draft and recomputes the allergy check', async () => {
    await readyScreen();
    const options = screen.getAllByTestId('drug-option');
    const cefalexin = options[2]!;
    fireEvent.click(within(cefalexin).getByRole('button', { name: prescriptionText('en', 'select') }));
    expect(screen.getByText(/DRUG-CEF/)).toBeTruthy();
    expect(screen.queryByText(prescriptionText('en', 'allergyMatch'))).toBeNull();
    expect(screen.getByText(prescriptionText('en', 'allergyUnknown'))).toBeTruthy();
  });

  it('clears rows and typed work when the active organization switches', async () => {
    mockReady();
    const { rerender } = wrap(<PrescriptionsPage />, alphaOrganizationId);
    await screen.findByRole('heading', { name: prescriptionText('en', 'prescriptions') });
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'patientId')), {
      target: { value: 'synthetic-typed-value' },
    });
    await screen.findByDisplayValue('synthetic-typed-value');
    rerender(element(<PrescriptionsPage />, betaOrganizationId));
    await waitFor(() => expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId));
    expect((screen.getByLabelText(prescriptionText('en', 'patientId')) as HTMLInputElement).value).toBe(
      'synthetic-patient-001',
    );
  });
});

describe('PrescriptionsPage commands', () => {
  beforeEach(() => mockReady());

  it('attests and routes a draft, then shows the status timeline', async () => {
    await readyScreen();
    permitSubmit();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));

    await waitFor(() => expect(postCalls()).toHaveLength(1));
    const command = postCalls()[0]!;
    expect(command.url).toBe('/api/v1/prescriptions');
    expect(command.body).toMatchObject({ drugCode: 'DRUG-AMOX', channel: 'e-prescribing' });
    expect(command.headers['If-Match']).toBeUndefined();
    expect(screen.getByText(prescriptionText('en', 'eventAccepted'))).toBeTruthy();
    expect(screen.getByText(syntheticSubmissionId)).toBeTruthy();
    expect(screen.getByTestId('prescriptions-status').textContent).toBe(
      prescriptionText('en', 'submittedNotice'),
    );
  });

  it('carries the version precondition on a second submission', async () => {
    await readyScreen();
    permitSubmit();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(1));

    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(2));
    const update = postCalls()[1]!;
    expect(update.url).toBe('/api/v1/prescriptions/rx-synthetic-001/submit');
    expect(update.headers['If-Match']).toBe('2');
  });

  it('keeps typed work on a conflict and offers compare, reload and copy', async () => {
    await readyScreen();
    permitSubmit('synthetic-override-keep');
    mockCommandFailure(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect((screen.getByLabelText(prescriptionText('en', 'overrideReason')) as HTMLInputElement).value).toBe(
      'synthetic-override-keep',
    );

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('1');
    expect(dialog.textContent).toContain('DRUG-AMOX → DRUG-AMOX');
    fireEvent.click(within(dialog).getByRole('button', { name: prescriptionText('en', 'close') }));
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByTestId('copied-draft').querySelector('textarea')?.value).toContain(
      'synthetic-override-keep',
    );

    const callsBeforeReload = sendMock.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(callsBeforeReload));
    expect(screen.queryByText(stateViewText('en', 'conflict'))).toBeNull();
    expect((screen.getByLabelText(prescriptionText('en', 'overrideReason')) as HTMLInputElement).value).toBe(
      'synthetic-override-keep',
    );
  });

  it('shows a precondition-required rejection as a conflict with the opened version', async () => {
    await readyScreen();
    permitSubmit();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(1));

    mockCommandFailure(transportFailure('precondition-required'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect((await screen.findByRole('dialog')).textContent).toContain('2');
  });

  it('shows a non-conflict command rejection above the retained draft', async () => {
    await readyScreen();
    permitSubmit();
    mockCommandFailure(transportFailure('forbidden'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByTestId('prescriptions-command-error')).toBeTruthy();
    expect(screen.getByLabelText(prescriptionText('en', 'overrideReason'))).toBeTruthy();
  });

  it('normalises an unexpected command rejection into a typed error state', async () => {
    await readyScreen();
    permitSubmit();
    mockCommandFailure(new Error('unexpected transport failure'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('verifies partner status before retrying an ambiguous transmission', async () => {
    let commandResult: PrescriptionResponse = createSyntheticPrescription({
      transmission: 'ambiguous',
      events: [
        { kind: 'submitted', occurredAt: '2026-03-09T09:00:00Z', submissionId: syntheticSubmissionId },
      ],
    });
    sendMock.mockImplementation(async (descriptor) => {
      const sent = descriptor as RequestDescriptor;
      if (sent.method === 'GET') {
        return syntheticCatalog;
      }
      return commandResult;
    });

    await readyScreen();
    permitSubmit();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByTestId('ambiguous-notice')).toBeTruthy();
    expect(postCalls()).toHaveLength(1);

    commandResult = createSyntheticPrescription();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'reconcile') }));
    await waitFor(() => expect(postCalls()).toHaveLength(2));
    expect(postCalls()[1]!.url).toBe('/api/v1/prescriptions/rx-synthetic-001/reconcile');
    expect(screen.getByText(prescriptionText('en', 'eventDeliveryConfirmed'))).toBeTruthy();
    expect(screen.queryByTestId('ambiguous-notice')).toBeNull();
    expect(screen.getByTestId('prescriptions-status').textContent).toBe(
      prescriptionText('en', 'reconciledNotice'),
    );
  });

  it('shows a command error when reconciliation fails', async () => {
    const commandResult: PrescriptionResponse = createSyntheticPrescription({ transmission: 'ambiguous' });
    sendMock.mockImplementation(async (descriptor) => {
      const sent = descriptor as RequestDescriptor;
      if (sent.method === 'GET') {
        return syntheticCatalog;
      }
      return commandResult;
    });
    await readyScreen();
    permitSubmit();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByTestId('ambiguous-notice')).toBeTruthy();

    mockCommandFailure(transportFailure('server', 'corr-reconcile'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'reconcile') }));
    expect(await screen.findByText('corr-reconcile')).toBeTruthy();

    // Retry must re-run the reconciliation command, not the original submit.
    mockReady();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    await waitFor(() => expect(postCalls()).toHaveLength(3));
    expect(postCalls()[2]!.url).toBe('/api/v1/prescriptions/rx-synthetic-001/reconcile');
    expect(screen.queryByTestId('prescriptions-command-error')).toBeNull();
  });

  it('retries a failed submit command from the error state', async () => {
    await readyScreen();
    permitSubmit();
    mockCommandFailure(transportFailure('server', 'corr-submit'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByText('corr-submit')).toBeTruthy();

    mockReady();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    await waitFor(() => expect(postCalls()).toHaveLength(2));
    expect(postCalls()[1]!.url).toBe('/api/v1/prescriptions');
    expect(screen.queryByTestId('prescriptions-command-error')).toBeNull();
  });

  it('reloads the authoritative record on a stale conflict and keeps typed work', async () => {
    await readyScreen();
    permitSubmit('synthetic-keep-on-reload');
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(1));

    sendMock.mockImplementation(async (descriptor) => {
      const sent = descriptor as RequestDescriptor;
      if (sent.method === 'GET' && sent.url.startsWith('/api/v1/prescriptions/')) {
        return createSyntheticPrescription({ version: 4 });
      }
      if (sent.method === 'GET') {
        return syntheticCatalog;
      }
      throw transportFailure('conflict');
    });
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect((await screen.findByRole('dialog')).textContent).toContain('2');
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: prescriptionText('en', 'close') }),
    );

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() =>
      expect(
        sendMock.mock.calls
          .map((call) => (call[0] as RequestDescriptor).url)
          .some((url) => url === '/api/v1/prescriptions/rx-synthetic-001'),
      ).toBe(true),
    );
    expect(screen.queryByText(stateViewText('en', 'conflict'))).toBeNull();
    expect(screen.getByTestId('prescriptions-status').textContent).toBe(
      prescriptionText('en', 'reloadedNotice'),
    );
    expect(
      (screen.getByLabelText(prescriptionText('en', 'overrideReason')) as HTMLInputElement).value,
    ).toBe('synthetic-keep-on-reload');

    // The refreshed version is the one used as the next write precondition.
    mockReady();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(3));
    expect(postCalls()[2]!.headers['If-Match']).toBe('4');
  });

  it('shows a command error when the authoritative reload fails', async () => {
    await readyScreen();
    permitSubmit();
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(1));

    mockCommandFailure(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    sendMock.mockImplementation(async (descriptor) => {
      const sent = descriptor as RequestDescriptor;
      if (sent.method === 'GET' && sent.url.startsWith('/api/v1/prescriptions/')) {
        throw transportFailure('server', 'corr-reload');
      }
      if (sent.method === 'GET') {
        return syntheticCatalog;
      }
      throw transportFailure('conflict');
    });

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    expect(await screen.findByText('corr-reload')).toBeTruthy();
    expect(screen.getByLabelText(prescriptionText('en', 'overrideReason'))).toBeTruthy();
  });

  it('warns before leaving with unsaved draft text and stays quiet once saved', async () => {
    await readyScreen();
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    permitSubmit();
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    const saved = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(saved);
    expect(saved.defaultPrevented).toBe(false);
  });
});
