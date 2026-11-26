import { Suspense } from 'react';
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
import { createRoutes } from '../../app/routes';
import { CollectionDeskPage } from './CollectionDeskPage';
import type { AccessionPageResponse, AccessionViewResponse } from './api';
import { alphaOrganizationId, betaOrganizationId, labelAlpha, presentedAlpha } from './fixtures';
import { labText } from './messages';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function apiView(
  overrides: Partial<AccessionViewResponse['accession']> = {},
): AccessionViewResponse {
  return {
    accession: {
      id: 'accession-100',
      version: 1,
      accessionNo: 'ACC-100',
      accessionIssuer: 'SYN-ISSUER',
      patientId: 'patient-100',
      sourceOrderRef: 'ORDER-100',
      state: 'accessioned',
      specimenType: null,
      siteCode: null,
      collector: null,
      collectedAt: null,
      receivedAt: null,
      quality: null,
      rejectionReason: null,
      rejectionNotification: null,
      replacesAccessionId: null,
      ...overrides,
    },
    events: [],
  };
}

function element(ui: ReactElement, organizationId: string): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement = <CollectionDeskPage />, organizationId = alphaOrganizationId) {
  return render(element(ui, organizationId));
}

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = 'corr-1',
) {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
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

async function selectOrder() {
  fireEvent.change(screen.getByLabelText(labText('en', 'search')), {
    target: { value: 'ACC-100' },
  });
  const list = screen.getByRole('list', { name: labText('en', 'findOrder') });
  fireEvent.click(required(within(list).getAllByRole('button')[0]));
  await screen.findByLabelText(labText('en', 'presentedPatientId'));
}

function verifyIdentity() {
  fireEvent.change(screen.getByLabelText(labText('en', 'presentedPatientId')), {
    target: { value: presentedAlpha.patientId },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'presentedAccessionNo')), {
    target: { value: presentedAlpha.accessionNo },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'presentedAccessionIssuer')), {
    target: { value: presentedAlpha.accessionIssuer },
  });
  fireEvent.click(screen.getByLabelText(labText('en', 'confirmNames')));
  fireEvent.click(screen.getByLabelText(labText('en', 'confirmAccession')));
}

function fillSpecimen() {
  fireEvent.change(screen.getByLabelText(labText('en', 'specimenType')), {
    target: { value: 'SERUM' },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'siteCode')), { target: { value: 'MAIN' } });
  fireEvent.change(screen.getByLabelText(labText('en', 'collector')), {
    target: { value: 'synthetic.collector' },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'collectedAt')), {
    target: { value: '2026-03-09T09:00:00Z' },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'quality')), {
    target: { value: 'ACCEPTABLE' },
  });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('CollectionDeskPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<AccessionPageResponse>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve({ items: [apiView()], nextCursor: null });
    expect(
      await screen.findByRole('heading', { name: labText('en', 'collectionDesk') }),
    ).toBeTruthy();
  });

  it('shows the empty state for an empty first page', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    wrap();
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind, `corr-${kind}`));

      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a forbidden list response', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('CollectionDeskPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue({ items: [apiView()], nextCursor: null });
  });

  it('records a collection through the API and reloads the list', async () => {
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();
    fillSpecimen();

    sendMock.mockResolvedValueOnce({});
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'recordCollection') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/lab/specimens/accession-100/collect');
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(command.body).toMatchObject({ specimenType: 'SERUM', expectedVersion: 1 });
    expect(command.headers['If-Match']).toBe('1');
    expect(descriptorAt(2).method).toBe('GET');
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.getByTestId('collection-status').textContent).toContain(
      labText('en', 'collectionRecorded'),
    );
  });

  it('shows the conflict state and keeps the screen for a rejected collection', async () => {
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();
    fillSpecimen();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'recordCollection') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByLabelText(labText('en', 'specimenType'))).toBeTruthy();
    expect(screen.getByTestId('collection-status').textContent).not.toContain(
      labText('en', 'collectionRecorded'),
    );
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('normalises an unexpected collection failure into a typed error state', async () => {
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();
    fillSpecimen();
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'recordCollection') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('loads label data for printing and shows the label sheet', async () => {
    sendMock.mockResolvedValueOnce({ items: [apiView()], nextCursor: null });
    sendMock.mockResolvedValueOnce(labelAlpha);
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'printLabels') }));

    expect(await screen.findByTestId('label-sheet')).toBeTruthy();
    expect(screen.getByTestId('label-sheet').textContent).toContain('MRN-100');
    expect(descriptorAt(1).url).toBe('/api/v1/lab/specimens/accession-100/label');
  });

  it('shows a conflict state when label data cannot be loaded', async () => {
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'printLabels') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.queryByTestId('label-sheet')).toBeNull();
  });

  it('rejects a specimen with a reason and reloads the list', async () => {
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();
    fireEvent.change(screen.getByLabelText(labText('en', 'rejectionReason')), {
      target: { value: 'haemolysed' },
    });

    sendMock.mockResolvedValueOnce({});
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'rejectSpecimen') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/lab/specimens/accession-100/reject');
    expect(command.headers['If-Match']).toBe('1');
    expect(command.body).toMatchObject({ reason: 'haemolysed', expectedVersion: 1 });
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.getByTestId('collection-status').textContent).toContain(
      labText('en', 'specimenRejected'),
    );
  });

  it('shows the conflict state for a rejected rejection', async () => {
    wrap();
    await screen.findByRole('heading', { name: labText('en', 'collectionDesk') });
    await selectOrder();
    verifyIdentity();
    fireEvent.change(screen.getByLabelText(labText('en', 'rejectionReason')), {
      target: { value: 'wrong_tube' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'rejectSpecimen') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByTestId('collection-status').textContent).not.toContain(
      labText('en', 'specimenRejected'),
    );
    expect(sendMock).toHaveBeenCalledTimes(2);
  });
});

describe('CollectionDeskPage paging, tenant switching and routing', () => {
  it('loads the next page while a cursor exists and keeps earlier rows', async () => {
    sendMock.mockResolvedValueOnce({
      items: [apiView({ id: 'accession-100' })],
      nextCursor: 'cursor-2',
    });
    sendMock.mockResolvedValueOnce({
      items: [apiView({ id: 'accession-200', accessionNo: 'ACC-200', patientId: 'patient-200' })],
      nextCursor: null,
    });

    wrap();
    expect(
      await screen.findByRole('heading', { name: labText('en', 'collectionDesk') }),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'loadMore') }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: labText('en', 'loadMore') })).toBeNull(),
    );
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');

    // Both pages remain searchable; the earlier accession was not dropped.
    fireEvent.change(screen.getByLabelText(labText('en', 'search')), { target: { value: 'ACC' } });
    const list = screen.getByRole('list', { name: labText('en', 'findOrder') });
    const labels = within(list)
      .getAllByRole('button')
      .map((button) => button.textContent);
    expect(labels.some((text) => text.includes('ACC-100'))).toBe(true);
    expect(labels.some((text) => text.includes('ACC-200'))).toBe(true);
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockResolvedValueOnce({ items: [apiView()], nextCursor: null });
    sendMock.mockResolvedValue({ items: [], nextCursor: null });

    const { rerender } = wrap(<CollectionDeskPage />, alphaOrganizationId);
    expect(
      await screen.findByRole('heading', { name: labText('en', 'collectionDesk') }),
    ).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    rerender(element(<CollectionDeskPage />, betaOrganizationId));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(screen.getByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('does not paint the previous tenant rows while the new tenant is still loading', async () => {
    const pending = deferred<AccessionPageResponse>();
    sendMock.mockResolvedValueOnce({ items: [apiView()], nextCursor: null });
    sendMock.mockReturnValueOnce(pending.promise);

    const { rerender } = wrap(<CollectionDeskPage />, alphaOrganizationId);
    expect(
      await screen.findByRole('heading', { name: labText('en', 'collectionDesk') }),
    ).toBeTruthy();
    fireEvent.change(screen.getByLabelText(labText('en', 'search')), { target: { value: 'ACC' } });
    expect(screen.getByRole('list', { name: labText('en', 'findOrder') })).toBeTruthy();

    rerender(element(<CollectionDeskPage />, betaOrganizationId));
    // The new tenant has not responded yet: no alpha row may survive this frame.
    expect(screen.queryByRole('list', { name: labText('en', 'findOrder') })).toBeNull();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve({ items: [], nextCursor: null });
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it('registers the accession route before the catch-all and lazily loads the page', async () => {
    sendMock.mockResolvedValue({ items: [apiView()], nextCursor: null });
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/lab/accessions');
    expect(patterns.indexOf('/lab/accessions')).toBeLessThan(patterns.indexOf('*'));

    const route = required(
      createRoutes(false).find((candidate) => candidate.pattern === '/lab/accessions'),
    );
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );
    expect(
      await screen.findByRole('heading', { name: labText('en', 'collectionDesk') }),
    ).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
