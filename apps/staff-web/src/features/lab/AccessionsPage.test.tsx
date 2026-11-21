import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { AccessionsPage } from './AccessionsPage';
import {
  accession,
  accessionPage,
  accessionView,
  alphaOrganizationId,
  betaOrganizationId,
} from './fixtures';
import { labText } from './messages';
import type { AccessionPage } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(
  ui: ReactElement,
  organizationId = alphaOrganizationId,
  locale: Locale = 'en',
): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(<AccessionsPage />, organizationId, locale));
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue(accessionPage());
});

describe('AccessionsPage states', () => {
  it('shows loading until the first page resolves, then the accession rows', async () => {
    const pending = deferred<AccessionPage>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(accessionPage());
    expect(await screen.findByRole('link', { name: 'SYN-ACC-0001' })).toBeTruthy();
    expect(screen.getByTestId('accession-state').textContent).toBe(labText('en', 'stateAccessioned'));
  });

  it('shows the empty state when the first page has no accessions', async () => {
    sendMock.mockResolvedValue(accessionPage({ items: [] }));

    wrap();
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows the error state with the correlation id', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('server', 'corr-list'));

    wrap();
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-list')).toBeTruthy();
  });

  it('shows the forbidden state for a forbidden list response', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));

    wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });
});

describe('AccessionsPage filtering and paging', () => {
  it('filters by state and sends the selected state on the next request', async () => {
    wrap();
    expect(await screen.findByRole('link', { name: 'SYN-ACC-0001' })).toBeTruthy();

    fireEvent.change(screen.getByLabelText(labText('en', 'stateFilter')), {
      target: { value: 'rejected' },
    });

    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    expect(descriptorAt(1).url).toContain('state=rejected');
    expect((screen.getByLabelText(labText('en', 'stateFilter')) as HTMLSelectElement).value).toBe('rejected');
  });

  it('appends the next page when Load more is activated', async () => {
    sendMock.mockResolvedValueOnce(
      accessionPage({
        items: [accessionView()],
        nextCursor: 'cursor-2',
      }),
    );

    wrap();
    const loadMore = await screen.findByRole('button', { name: labText('en', 'loadMore') });
    fireEvent.click(loadMore);

    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(1));
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');
    await waitFor(() =>
      expect(screen.getAllByRole('link', { name: 'SYN-ACC-0001' }).length).toBe(2),
    );
  });

  it('clears the previous tenant rows when the organization changes', async () => {
    const view = render(element(<AccessionsPage />, alphaOrganizationId));
    expect(await screen.findByRole('link', { name: 'SYN-ACC-0001' })).toBeTruthy();

    view.rerender(element(<AccessionsPage />, betaOrganizationId));
    await waitFor(() => expect(screen.queryByRole('link', { name: 'SYN-ACC-0001' })).toBeNull());
    const lastCall = sendMock.mock.calls[sendMock.mock.calls.length - 1]![0] as RequestDescriptor;
    expect(lastCall.headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('renders in Arabic with the document direction set to rtl', async () => {
    wrap(alphaOrganizationId, 'ar');
    expect(await screen.findByRole('link', { name: 'SYN-ACC-0001' })).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('table')).toBeTruthy();
  });

  it('renders both a populated and an empty specimen cell', async () => {
    const populated = accessionView({
      accession: accession({
        id: 'aaaaaaaa-0000-4000-8000-000000000002',
        accessionNo: 'SYN-ACC-0002',
        specimenType: 'SYN-PLASMA',
        siteCode: 'SYN-LAB',
      }),
    });
    sendMock.mockResolvedValueOnce(accessionPage({ items: [accessionView(), populated] }));

    wrap();
    expect(await screen.findByRole('link', { name: 'SYN-ACC-0002' })).toBeTruthy();
    const cells = screen.getAllByRole('cell');
    expect(cells.some((cell) => cell.textContent === 'SYN-PLASMA')).toBe(true);
    expect(cells.some((cell) => cell.textContent === '')).toBe(true);
  });
});

describe('AccessionsPage create command', () => {
  async function openForm() {
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'newAccession') }));
    return screen.getByRole('form', { name: labText('en', 'newAccession') });
  }

  it('blocks an empty accession number and an empty patient id', async () => {
    wrap();
    await screen.findByRole('link', { name: 'SYN-ACC-0001' });
    const form = await openForm();

    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'createAccession') }));
    expect(screen.getAllByText(labText('en', 'required')).length).toBe(2);

    fireEvent.change(screen.getByLabelText(labText('en', 'accessionNo')), {
      target: { value: 'SYN-ACC-0009' },
    });
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'createAccession') }));
    expect(screen.getAllByText(labText('en', 'required')).length).toBe(1);
    expect(sendMock.mock.calls.length).toBe(1);
  });

  it('creates an accession and shows the issuer-scoped id', async () => {
    wrap();
    await screen.findByRole('link', { name: 'SYN-ACC-0001' });
    const form = await openForm();

    fireEvent.change(screen.getByLabelText(labText('en', 'accessionNo')), {
      target: { value: 'SYN-ACC-0009' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'patientId')), {
      target: { value: 'bbbbbbbb-0000-4000-8000-000000000009' },
    });
    sendMock.mockResolvedValueOnce(
      accession({ accessionNo: 'SYN-ACC-0009', accessionIssuer: 'SYNLAB' }),
    );
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'createAccession') }));

    expect(await screen.findByText('SYN-ACC-0009 (SYNLAB)')).toBeTruthy();
    const post = sendMock.mock.calls[1]![0] as RequestDescriptor;
    expect(post.method).toBe('POST');
    expect(post.url).toBe('/api/v1/lab/accessions');
  });

  it('keeps the form cancellable', async () => {
    wrap();
    await screen.findByRole('link', { name: 'SYN-ACC-0001' });
    const form = await openForm();

    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'cancel') }));
    expect(screen.queryByRole('form', { name: labText('en', 'newAccession') })).toBeNull();
    expect(screen.getByRole('button', { name: labText('en', 'newAccession') })).toBeTruthy();
  });

  it('shows a conflict state when the command is rejected', async () => {
    wrap();
    await screen.findByRole('link', { name: 'SYN-ACC-0001' });
    const form = await openForm();

    fireEvent.change(screen.getByLabelText(labText('en', 'accessionNo')), {
      target: { value: 'SYN-ACC-0009' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'patientId')), {
      target: { value: 'bbbbbbbb-0000-4000-8000-000000000009' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'createAccession') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('normalises a non-transport command rejection to the server state', async () => {
    wrap();
    await screen.findByRole('link', { name: 'SYN-ACC-0001' });
    const form = await openForm();

    fireEvent.change(screen.getByLabelText(labText('en', 'accessionNo')), {
      target: { value: 'SYN-ACC-0009' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'patientId')), {
      target: { value: 'bbbbbbbb-0000-4000-8000-000000000009' },
    });
    sendMock.mockRejectedValueOnce(new Error('unexpected'));
    fireEvent.click(within(form).getByRole('button', { name: labText('en', 'createAccession') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
  });
});
