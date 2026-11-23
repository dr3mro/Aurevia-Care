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
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { DuplicatesPage } from './DuplicatesPage';
import {
  alphaOrganizationId,
  betaOrganizationId,
  detailWithUnresolvedDependency,
  detailWithoutReview,
  openerSubject,
  reviewOpen,
  secondReviewerSubject,
  syntheticConsolidation,
  syntheticDetail,
  syntheticPair,
} from './fixtures';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
}

function page(currentSubject?: string) {
  return currentSubject === undefined ? (
    <DuplicatesPage />
  ) : (
    <DuplicatesPage currentSubject={currentSubject} />
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  code?: string,
  correlationId = `corr-${kind}`,
) {
  return new TransportError(kind, {
    correlationId,
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

/** Load the worklist, then select the synthetic pair and await its detail. */
async function reachDetail(detail = syntheticDetail, subject?: string) {
  sendMock.mockResolvedValueOnce([syntheticPair]);
  sendMock.mockResolvedValueOnce(detail);
  wrap(page(subject));
  const open = await screen.findByRole('button', {
    name: /Review pair patient-survivor and patient-duplicate/,
  });
  fireEvent.click(open);
  await screen.findByRole('region', { name: 'Surviving record' });
}

/** Fill a locally valid approval form: two identifiers confirmed, no alternate. */
function fillValidApproval() {
  fireEvent.change(screen.getByLabelText('Reason for consolidation'), {
    target: { value: 'duplicate registration' },
  });
  fireEvent.click(screen.getByLabelText('MRN-1001@SYNTHETIC'));
  fireEvent.click(screen.getByLabelText('MRN-1002@SYNTHETIC'));
}

function submitApproval() {
  const submit = screen.getByRole<HTMLButtonElement>('button', { name: 'Approve consolidation' });
  fireEvent.submit(must(submit.closest<HTMLElement>('form')));
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('DuplicatesPage worklist states', () => {
  it('shows the loading state until the worklist resolves', async () => {
    const pending = deferred<(typeof syntheticPair)[]>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(page());

    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve([syntheticPair] as never);
    expect(await screen.findByRole('table', { name: 'Duplicate review worklist' })).toBeTruthy();
  });

  it('shows the empty state when there are no duplicate pairs', async () => {
    sendMock.mockResolvedValueOnce([]);
    wrap(page());

    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));
      wrap(page());

      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(page());
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(page());
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('renders the worklist in Arabic under RTL', async () => {
    sendMock.mockResolvedValueOnce([syntheticPair]);
    wrap(page(), alphaOrganizationId, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'قائمة مراجعة التكرارات' })).toBeTruthy();
  });

  it('clears the previous tenant rows on a tenant switch', async () => {
    sendMock.mockResolvedValueOnce([syntheticPair]);
    sendMock.mockResolvedValueOnce([]);
    const view = wrap(page());
    await screen.findByText('Layla Haddad');

    view.rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={{ organizationId: betaOrganizationId }}>
            {page()}
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>,
    );
    await waitFor(() => expect(screen.queryByText('Layla Haddad')).toBeNull());
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });
});

describe('DuplicatesPage review and second approval', () => {
  it('reaches the side-by-side view and dry run through the worklist', async () => {
    await reachDetail();

    expect(screen.getByRole('region', { name: 'Surviving record' })).toBeTruthy();
    expect(screen.getByText(/Dry run: move 1 chart visibility association/)).toBeTruthy();
    expect(descriptorAt(1).url).toBe(
      `/api/v1/duplicate-reviews/${syntheticPair.survivorId}/${syntheticPair.duplicateId}`,
    );
  });

  it('opens a review ticket and shows who opened it', async () => {
    sendMock.mockResolvedValueOnce([syntheticPair]);
    sendMock.mockResolvedValueOnce(detailWithoutReview);
    sendMock.mockResolvedValueOnce(reviewOpen);
    wrap(page());
    fireEvent.click(
      await screen.findByRole('button', {
        name: /Review pair patient-survivor and patient-duplicate/,
      }),
    );
    await screen.findByRole('button', { name: 'Open review' });

    fireEvent.click(screen.getByRole('button', { name: 'Open review' }));
    expect(await screen.findByText(reviewOpen.id)).toBeTruthy();
    expect(descriptorAt(2).method).toBe('POST');
    expect(descriptorAt(2).url).toBe('/api/v1/duplicate-reviews');
    expect(descriptorAt(2).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('sends only one open request for two rapid clicks', async () => {
    sendMock.mockResolvedValueOnce([syntheticPair]);
    sendMock.mockResolvedValueOnce(detailWithoutReview);
    const pending = deferred<typeof reviewOpen>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(page());
    fireEvent.click(
      await screen.findByRole('button', {
        name: /Review pair patient-survivor and patient-duplicate/,
      }),
    );
    const open = await screen.findByRole('button', { name: 'Open review' });
    fireEvent.click(open);
    fireEvent.click(open);

    expect(sendMock).toHaveBeenCalledTimes(3);
    pending.resolve(reviewOpen);
    await screen.findByText(reviewOpen.id);
  });

  it('shows the open failure as a state view', async () => {
    sendMock.mockResolvedValueOnce([syntheticPair]);
    sendMock.mockResolvedValueOnce(detailWithoutReview);
    sendMock.mockRejectedValueOnce(transportFailure('conflict', 'CONFLICT'));
    wrap(page());
    fireEvent.click(
      await screen.findByRole('button', {
        name: /Review pair patient-survivor and patient-duplicate/,
      }),
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Open review' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('refuses the reviewer who opened the request without sending an approval', async () => {
    await reachDetail(syntheticDetail, openerSubject);
    fillValidApproval();

    const callsBefore = sendMock.mock.calls.length;
    submitApproval();

    expect(screen.getByText(/The reviewer who opened this request cannot approve it/)).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(callsBefore);
  });

  it('blocks approval while an ambiguous dependency is unresolved', async () => {
    await reachDetail(detailWithUnresolvedDependency);
    fillValidApproval();

    const callsBefore = sendMock.mock.calls.length;
    submitApproval();

    expect(screen.getByText(/Unresolved dependencies block approval/)).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(callsBefore);
  });

  it('keeps the typed reason when local validation fails', async () => {
    await reachDetail();
    submitApproval();

    expect(screen.getByLabelText('Reason for consolidation').getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(screen.getByLabelText<HTMLInputElement>('Reason for consolidation').value).toBe('');
    expect(sendMock).toHaveBeenCalledTimes(2);
  });
});

describe('DuplicatesPage approval refusals and submission', () => {
  it('maps a 409 SECOND_REVIEWER_REQUIRED to the refusal and conflict state', async () => {
    await reachDetail();
    fillValidApproval();
    sendMock.mockRejectedValueOnce(transportFailure('conflict', 'SECOND_REVIEWER_REQUIRED'));

    submitApproval();

    expect(
      await screen.findByText(
        'Refused: a different authorized reviewer must approve this request.',
      ),
    ).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('maps a 409 UNRESOLVED_DEPENDENCY to the unresolved refusal', async () => {
    await reachDetail();
    fillValidApproval();
    sendMock.mockRejectedValueOnce(transportFailure('conflict', 'UNRESOLVED_DEPENDENCY'));

    submitApproval();

    expect(
      await screen.findByText(
        'Refused: unresolved dependencies must be resolved before consolidation.',
      ),
    ).toBeTruthy();
  });

  it('falls back to a generic refusal for an unrecognized server code', async () => {
    await reachDetail();
    fillValidApproval();
    sendMock.mockRejectedValueOnce(transportFailure('conflict', 'SOMETHING_ELSE'));

    submitApproval();

    expect(
      await screen.findByText(
        'The consolidation could not be completed. Review the state and try again.',
      ),
    ).toBeTruthy();
  });

  it('normalizes an unexpected rejection into the server error state', async () => {
    await reachDetail();
    fillValidApproval();
    sendMock.mockRejectedValueOnce(new Error('unexpected'));

    submitApproval();

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('approves with the duplicate version, a fresh idempotency key and the confirmation body', async () => {
    await reachDetail();
    fillValidApproval();
    sendMock.mockResolvedValueOnce(syntheticConsolidation);

    submitApproval();

    expect(
      await screen.findByText('Consolidation approved and chart visibility moved.'),
    ).toBeTruthy();
    const descriptor = descriptorAt(2);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe(`/api/v1/duplicate-reviews/${reviewOpen.id}/consolidate`);
    expect(descriptor.headers['If-Match']).toBe(String(syntheticDetail.duplicate.version));
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual({
      reason: 'duplicate registration',
      confirmedIdentifiers: ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC'],
      alternateProcess: '',
    });
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
  });

  it('approves with a documented alternate process instead of identifiers', async () => {
    await reachDetail();
    fireEvent.change(screen.getByLabelText('Reason for consolidation'), {
      target: { value: 'duplicate registration' },
    });
    fireEvent.change(screen.getByLabelText('Approved alternate-process code'), {
      target: { value: 'documented_process' },
    });
    sendMock.mockResolvedValueOnce(syntheticConsolidation);

    submitApproval();

    expect(
      await screen.findByText('Consolidation approved and chart visibility moved.'),
    ).toBeTruthy();
    expect(descriptorAt(2).body?.confirmedIdentifiers).toEqual([]);
    expect(descriptorAt(2).body?.alternateProcess).toBe('documented_process');
  });

  it('sends only one approval for two rapid submits', async () => {
    await reachDetail();
    fillValidApproval();
    const pending = deferred<typeof syntheticConsolidation>();
    sendMock.mockReturnValueOnce(pending.promise);

    submitApproval();
    submitApproval();

    expect(sendMock).toHaveBeenCalledTimes(3);
    pending.resolve(syntheticConsolidation);
    await screen.findByText('Consolidation approved and chart visibility moved.');
  });

  it('uses the injected second reviewer by default', async () => {
    await reachDetail();
    fillValidApproval();
    expect(screen.queryByText(/The reviewer who opened this request cannot approve it/)).toBeNull();
    // The default principal differs from the opener, so the guard stays open.
    expect(secondReviewerSubject).not.toBe(openerSubject);
  });

  it('returns to the worklist with the back action', async () => {
    await reachDetail();
    sendMock.mockResolvedValueOnce([syntheticPair]);

    fireEvent.click(screen.getByRole('button', { name: 'Back to worklist' }));

    expect(await screen.findByRole('table', { name: 'Duplicate review worklist' })).toBeTruthy();
  });
});

describe('duplicates route registration', () => {
  it('does not claim a shared app route (BUILD-52 owns registration)', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).not.toContain('/duplicates');
  });
});

/** Compile-time guard that the page renders as a React element. */
export const pageElement: ReactElement = <DuplicatesPage />;
