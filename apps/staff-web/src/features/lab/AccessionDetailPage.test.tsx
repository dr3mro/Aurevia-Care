import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { RouterProvider } from '../../app/router';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { AccessionDetailPage } from './AccessionDetailPage';
import { accession, accessionEvent, accessionView, alphaOrganizationId } from './fixtures';
import { labText } from './messages';
import type { AccessionAction, AccessionState, AccessionView, LabAccession } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(ui: ReactElement, organizationId: string, locale: Locale): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(
  view: AccessionView,
  locale: Locale = 'en',
  options: { router?: boolean } = {},
): void {
  const withRouter = options.router !== false;
  const ui = element(<AccessionDetailPage />, alphaOrganizationId, locale);
  render(
    withRouter ? (
      <RouterProvider patterns={['/lab/accessions/:id']} initialPath={`/lab/accessions/${view.accession.id}`}>
        {ui}
      </RouterProvider>
    ) : (
      ui
    ),
  );
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue(accessionView());
});

describe('AccessionDetailPage states and valid actions', () => {
  const expectations: Array<[AccessionState, AccessionAction[]]> = [
    ['accessioned', ['collect']],
    ['collected', ['receive', 'reject']],
    ['received', ['reject']],
    ['rejected', ['recollect']],
    ['recollected', []],
  ];
  const allActions: AccessionAction[] = ['collect', 'receive', 'reject', 'recollect'];

  it.each(expectations)('offers only %j actions in state %s', async (state, allowed) => {
    sendMock.mockResolvedValue(accessionView({ accession: accession({ state }) }));

    wrap(accessionView({ accession: accession({ state }) }));

    expect(await screen.findByTestId('accession-state')).toBeTruthy();
    expect(screen.getByTestId('accession-state').textContent).toBe(labText('en', `state${state[0]!.toUpperCase()}${state.slice(1)}` as 'stateAccessioned'));
    for (const action of allActions) {
      const button = screen.queryByRole('button', { name: labText('en', action) });
      if (allowed.includes(action)) {
        expect(button).toBeTruthy();
      } else {
        expect(button).toBeNull();
      }
    }
  });

  it('shows a loading state before the accession resolves', () => {
    wrap(accessionView());
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
  });

  it('shows an error state with the correlation id', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('server', 'corr-detail'));

    wrap(accessionView());
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-detail')).toBeTruthy();
  });

  it('reads the id from the route and falls back to an empty id outside a router', async () => {
    const view = accessionView();
    wrap(view, 'en', { router: false });

    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(0));
    expect(descriptorAt(0).url).toBe('/api/v1/lab/accessions/');
  });

  it('renders the Arabic labels and rtl direction', async () => {
    const view = accessionView({ accession: accession({ state: 'collected' }) });
    sendMock.mockResolvedValue(view);
    wrap(view, 'ar');

    expect(await screen.findByTestId('accession-state')).toBeTruthy();
    expect(screen.getByTestId('accession-state').textContent).toBe(labText('ar', 'stateCollected'));
    expect(screen.getByRole('group', { name: labText('ar', 'actions') })).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
  });
});

describe('AccessionDetailPage history and fields', () => {
  it('shows the explicit empty-history message', async () => {
    sendMock.mockResolvedValue(accessionView({ events: [] }));

    wrap(accessionView({ events: [] }));
    expect(await screen.findByText(labText('en', 'noEvents'))).toBeTruthy();
  });

  it('shows transition evidence and a retained rejection reason', async () => {
    const events = [
      accessionEvent(),
      accessionEvent({
        id: 'cccccccc-0000-4000-8000-000000000002',
        action: 'rejected',
        fromState: 'collected',
        toState: 'rejected',
        reason: 'SYN hemolysis',
        accessionVersion: 2,
      }),
    ];
    const view = accessionView({ events });
    sendMock.mockResolvedValue(view);

    wrap(view);
    expect(await screen.findByText(/SYN hemolysis/)).toBeTruthy();
    expect(screen.getByText(/collected → rejected/)).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('shows populated specimen and receipt values', async () => {
    const view = accessionView({
      accession: accession({
        specimenType: 'SYN-WHOLE-BLOOD',
        siteCode: 'SYN-MAIN',
        collector: 'SYN-COLLECTOR',
        collectedAt: '2026-03-09T06:00:00Z',
        receivedAt: '2026-03-09T07:00:00Z',
        quality: 'SYN-ACCEPTABLE',
      }),
    });
    sendMock.mockResolvedValue(view);

    wrap(view);
    expect(await screen.findByText('SYN-WHOLE-BLOOD')).toBeTruthy();
    expect(screen.getByText('SYN-COLLECTOR')).toBeTruthy();
    expect(screen.getByText('SYN-ACCEPTABLE')).toBeTruthy();
  });
});

describe('AccessionDetailPage commands', () => {
  async function openAction(action: AccessionAction) {
    fireEvent.click(screen.getByRole('button', { name: labText('en', action) }));
    return screen.getByRole('form', { name: labText('en', action) });
  }

  it('collects a specimen with the identity taken from the record', async () => {
    const view = accessionView({ accession: accession({ state: 'accessioned' }) });
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('collect');

    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'collect') }));

    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    const post = descriptorAt(1);
    expect(post.method).toBe('POST');
    expect(post.url).toBe('/api/v1/lab/accessions/aaaaaaaa-0000-4000-8000-000000000001/collect');
    expect(post.headers['If-Match']).toBe('1');
    expect((post.body as Record<string, unknown>)['specimenType']).toBe('SYN-WHOLE-BLOOD');
  });

  it('receives a specimen with the receipt quality', async () => {
    const view = accessionView({
      accession: accession({ state: 'collected', version: 2, specimenType: 'SYN-WHOLE-BLOOD' }),
    });
    sendMock.mockResolvedValue(view);
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('receive');

    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'receive') }));

    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    const post = descriptorAt(1);
    expect(post.url).toBe('/api/v1/lab/accessions/aaaaaaaa-0000-4000-8000-000000000001/receive');
    expect(post.headers['If-Match']).toBe('2');
    expect((post.body as Record<string, unknown>)['quality']).toBe('SYN-ACCEPTABLE');
  });

  it('requires a reason before rejecting', async () => {
    const view = accessionView({ accession: accession({ state: 'collected' }) });
    sendMock.mockResolvedValue(view);
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('reject');

    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'reject') }));
    expect(await screen.findByText(labText('en', 'reasonRequired'))).toBeTruthy();
    expect(sendMock.mock.calls.length).toBe(1);

    fireEvent.change(screen.getByLabelText(labText('en', 'reason')), {
      target: { value: 'SYN hemolysis' },
    });
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'reject') }));

    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    const post = descriptorAt(1);
    expect(post.url).toBe('/api/v1/lab/accessions/aaaaaaaa-0000-4000-8000-000000000001/reject');
    expect((post.body as Record<string, unknown>)['reason']).toBe('SYN hemolysis');
    expect(screen.queryByText(labText('en', 'reasonRequired'))).toBeNull();
  });

  it('links the new specimen after a recollect', async () => {
    const view = accessionView({ accession: accession({ state: 'rejected', version: 3 }) });
    sendMock.mockResolvedValue(view);
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('recollect');

    const newSpecimen: LabAccession = accession({
      id: 'aaaaaaaa-0000-4000-8000-000000000099',
      accessionNo: 'SYN-ACC-0099',
      accessionIssuer: 'SYNLAB',
      replacesAccessionId: view.accession.id,
    });
    sendMock.mockResolvedValueOnce(newSpecimen);
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'recollect') }));

    const link = await screen.findByRole('link', { name: 'SYN-ACC-0099 (SYNLAB)' });
    expect(link.getAttribute('href')).toBe('/lab/accessions/aaaaaaaa-0000-4000-8000-000000000099');
    const post = descriptorAt(1);
    expect(post.url).toBe('/api/v1/lab/accessions/aaaaaaaa-0000-4000-8000-000000000001/recollect');
    expect(post.headers['If-Match']).toBe('3');
  });

  it('clears the recollect banner when navigating to the linked specimen', async () => {
    const view = accessionView({ accession: accession({ state: 'rejected', version: 3 }) });
    sendMock.mockResolvedValue(view);
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('recollect');

    const newSpecimen: LabAccession = accession({
      id: 'aaaaaaaa-0000-4000-8000-000000000099',
      accessionNo: 'SYN-ACC-0099',
      accessionIssuer: 'SYNLAB',
      replacesAccessionId: view.accession.id,
    });
    sendMock.mockResolvedValueOnce(newSpecimen);
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'recollect') }));

    const link = await screen.findByRole('link', { name: 'SYN-ACC-0099 (SYNLAB)' });
    fireEvent.click(link);

    // The app reuses the page component across :id changes; the banner tied to
    // the previous record must not follow the reader to the new specimen.
    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'SYN-ACC-0099 (SYNLAB)' })).toBeNull(),
    );
    const lastCall = sendMock.mock.calls[sendMock.mock.calls.length - 1]![0] as RequestDescriptor;
    expect(lastCall.url).toBe(
      '/api/v1/lab/accessions/aaaaaaaa-0000-4000-8000-000000000099',
    );
  });

  it('cancels an open action form', async () => {
    const view = accessionView({ accession: accession({ state: 'collected' }) });
    sendMock.mockResolvedValue(view);
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('reject');

    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'cancel') }));
    expect(screen.queryByRole('form', { name: labText('en', 'reject') })).toBeNull();
  });

  it('shows a conflict state when a command is rejected', async () => {
    const view = accessionView({ accession: accession({ state: 'accessioned' }) });
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('collect');

    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'collect') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('normalises a non-transport command rejection to the server state', async () => {
    const view = accessionView({ accession: accession({ state: 'accessioned' }) });
    wrap(view);
    await screen.findByTestId('accession-state');
    const form = await openAction('collect');

    sendMock.mockRejectedValueOnce(new Error('unexpected'));
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'collect') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
  });
});
