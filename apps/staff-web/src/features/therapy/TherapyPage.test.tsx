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
import { TherapyPage } from './TherapyPage';
import type { EpisodeResponse, ReferralResponse } from './api';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { therapyText } from './messages';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function referral(overrides: Partial<ReferralResponse> = {}): ReferralResponse {
  return {
    id: 'ref-001',
    version: 2,
    patientId: 'patient-001',
    referrer: 'Synthetic Referrer A',
    reason: 'Synthetic knee rehabilitation',
    receivedAt: '2026-03-02T08:00:00Z',
    state: 'accepted',
    ...overrides,
  };
}

function episode(overrides: Partial<EpisodeResponse> = {}): EpisodeResponse {
  return {
    id: 'episode-001',
    referralId: 'ref-001',
    patientId: 'patient-001',
    timeZone: 'America/New_York',
    state: 'active',
    evaluation: 'Synthetic evaluation',
    problems: ['Synthetic problem'],
    baseline: [{ id: 'baseline-1', name: 'Pain', value: 7, unit: '/10', measuredOn: '2026-03-02' }],
    goals: [{ id: 'goal-1', description: 'Synthetic goal', targetOn: '2026-04-15', state: 'active' }],
    plannedFrequency: 'Twice weekly',
    plannedDuration: 'Six weeks',
    interventions: ['Synthetic exercise'],
    outcomeScales: [
      {
        id: 'scale-1',
        name: 'Synthetic scale',
        code: 'NPRS',
        scaleVersion: 2,
        unit: '/10',
        measurements: [{ id: 'm-1', recordedAt: '2026-03-05T09:00:00Z', value: 7, unit: '/10' }],
      },
    ],
    sessionNotes: [
      {
        id: 'note-signed',
        episodeId: 'episode-001',
        patientLocalId: 'patient-001',
        author: 'Synthetic Therapist',
        authoredAt: '2026-03-05T10:00:00Z',
        body: 'Synthetic signed note',
        state: 'signed',
        version: 2,
        signedBy: 'Synthetic Therapist',
        signedAt: '2026-03-05T10:30:00Z',
        addendumOf: undefined,
      },
      {
        id: 'note-draft',
        episodeId: 'episode-001',
        patientLocalId: 'patient-001',
        author: 'Synthetic Therapist',
        authoredAt: '2026-03-12T10:00:00Z',
        body: 'Synthetic draft note',
        state: 'draft',
        version: 1,
        signedBy: undefined,
        signedAt: undefined,
        addendumOf: undefined,
      },
    ],
    progressNotes: [
      {
        id: 'progress-signed',
        episodeId: 'episode-001',
        patientLocalId: 'patient-001',
        author: 'Synthetic Therapist',
        authoredAt: '2026-03-14T10:00:00Z',
        body: 'Synthetic progress',
        state: 'signed',
        version: 3,
        signedBy: 'Synthetic Therapist',
        signedAt: '2026-03-14T10:30:00Z',
        addendumOf: undefined,
      },
    ],
    dischargeSummary: null,
    version: 4,
    ...overrides,
  };
}

function element(ui: ReactElement, organizationId = alphaOrganizationId): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId) {
  return render(element(ui, organizationId));
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function findDescriptor(fragment: string): RequestDescriptor | undefined {
  return sendMock.mock.calls
    .map((call) => call[0] as RequestDescriptor)
    .find((descriptor) => descriptor.url.includes(fragment));
}

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0]) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
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

/** Route each mock response by URL: two independent loaders share `send`. */
function mockByUrl(
  referralPage: { items: ReferralResponse[]; nextCursor: string | null } = {
    items: [referral()],
    nextCursor: null,
  },
  episodeValue: EpisodeResponse = episode(),
) {
  sendMock.mockImplementation((descriptor: RequestDescriptor) =>
    descriptor.url.includes('/episode')
      ? Promise.resolve(episodeValue)
      : Promise.resolve(referralPage),
  );
}

beforeEach(() => {
  sendMock.mockReset();
  mockByUrl();
});

describe('TherapyPage states', () => {
  it('shows the loading state until the first referral page resolves', async () => {
    const pending = deferred<{ items: ReferralResponse[]; nextCursor: string | null }>();
    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      descriptor.url.includes('/episode') ? Promise.resolve(episode()) : pending.promise,
    );

    wrap(<TherapyPage initialReferralId="ref-001" />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve({ items: [referral()], nextCursor: null });
    expect(await screen.findAllByText('patient-001')).toBeTruthy();
  });

  it('shows the empty state for an empty first page', async () => {
    mockByUrl({ items: [], nextCursor: null });
    wrap(<TherapyPage initialReferralId="ref-001" />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockImplementation((descriptor: RequestDescriptor) =>
        descriptor.url.includes('/episode')
          ? Promise.resolve(episode())
          : Promise.reject(transportFailure(kind)),
      );
      wrap(<TherapyPage initialReferralId="ref-001" />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden and session-expired states', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      descriptor.url.includes('/episode')
        ? Promise.resolve(episode())
        : Promise.reject(transportFailure('forbidden')),
    );
    const { unmount } = wrap(<TherapyPage initialReferralId="ref-001" />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    unmount();

    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      descriptor.url.includes('/episode')
        ? Promise.resolve(episode())
        : Promise.reject(transportFailure('session-expired')),
    );
    wrap(<TherapyPage initialReferralId="ref-001" />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows the episode error state while keeping the referral worklist', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      descriptor.url.includes('/episode')
        ? Promise.reject(transportFailure('forbidden'))
        : Promise.resolve({ items: [referral()], nextCursor: null }),
    );
    wrap(<TherapyPage initialReferralId="ref-001" />);
    expect(await screen.findByTestId('therapy-episode-error')).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
  });
});

describe('TherapyPage commands', () => {
  beforeEach(() => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/episode')) {
        return Promise.resolve(episode());
      }
      return Promise.resolve({ items: [referral()], nextCursor: null });
    });
  });

  it('accepts a pending referral and reloads the worklist', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/accept')) {
        return Promise.resolve(referral({ state: 'accepted', version: 2 }));
      }
      return descriptor.url.includes('/episode')
        ? Promise.resolve(episode())
        : Promise.resolve({ items: [referral({ state: 'pending', version: 1 })], nextCursor: null });
    });
    wrap(<TherapyPage initialReferralId="ref-002" />);
    await screen.findAllByText('patient-001');

    fireEvent.click(screen.getByRole('button', { name: therapyText('en', 'accept') }));

    await waitFor(() => expect(findDescriptor('/accept')).toBeTruthy());
    const command = findDescriptor('/accept')!;
    expect(command.method).toBe('POST');
    expect(command.headers['If-Match']).toBe('1');
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('signs a draft session note through the API and reloads the episode', async () => {
    wrap(<TherapyPage initialReferralId="ref-001" />);
    const article = await screen.findByText('Synthetic draft note');
    const card = article.closest('article')!;
    fireEvent.click(card.querySelector('input[type="checkbox"]')!);
    fireEvent.change(card.querySelector('textarea')!, { target: { value: 'Synthetic signed body' } });
    fireEvent.click(card.querySelector('button')!);

    await waitFor(() => expect(findDescriptor('/sign')).toBeTruthy());
    const command = findDescriptor('/sign')!;
    expect(command.url).toBe('/api/v1/therapy/episodes/episode-001/notes/note-draft/sign');
    expect(command.headers['If-Match']).toBe('1');
    expect(command.body).toMatchObject({ body: 'Synthetic signed body', attested: true });
  });

  it('signs a draft progress note through the API', async () => {
    const draftProgress = {
      id: 'progress-draft',
      episodeId: 'episode-001',
      patientLocalId: 'patient-001',
      author: 'Synthetic Therapist',
      authoredAt: '2026-03-14T10:00:00Z',
      body: 'Synthetic draft progress',
      state: 'draft' as const,
      version: 5,
      signedBy: undefined,
      signedAt: undefined,
      addendumOf: undefined,
    };
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/episode')) {
        return Promise.resolve(episode({ progressNotes: [draftProgress] }));
      }
      return Promise.resolve({ items: [referral()], nextCursor: null });
    });
    wrap(<TherapyPage initialReferralId="ref-001" />);
    const articles = await screen.findAllByRole('button', { name: therapyText('en', 'signNote') });
    const card = articles[1]!.closest('article')!;
    fireEvent.click(card.querySelector('input[type="checkbox"]')!);
    fireEvent.change(card.querySelector('textarea')!, { target: { value: 'Synthetic progress signed' } });
    fireEvent.click(within(card).getByRole('button', { name: therapyText('en', 'signNote') }));

    await waitFor(() => expect(findDescriptor('progress-draft/sign')).toBeTruthy());
    expect(findDescriptor('progress-draft/sign')!.headers['If-Match']).toBe('5');
  });

  it('records an addendum through the API', async () => {
    wrap(<TherapyPage initialReferralId="ref-001" />);
    const signed = await screen.findByTestId('read-only-body-note-signed');
    const card = signed.closest('article')!;
    fireEvent.click(card.querySelector('button')!);
    fireEvent.change(card.querySelector('textarea')!, { target: { value: 'Synthetic addendum' } });
    const buttons = card.querySelectorAll('button');
    fireEvent.click(buttons[buttons.length - 1]!);

    await waitFor(() => expect(findDescriptor('/addendum')).toBeTruthy());
    expect(findDescriptor('/addendum')!.body).toEqual({ body: 'Synthetic addendum' });
  });

  it('records an addendum to a progress note through the API', async () => {
    wrap(<TherapyPage initialReferralId="ref-001" />);
    const signed = await screen.findByTestId('read-only-body-progress-signed');
    const card = signed.closest('article')!;
    fireEvent.click(card.querySelector('button')!);
    fireEvent.change(card.querySelector('textarea')!, {
      target: { value: 'Synthetic progress addendum' },
    });
    const buttons = card.querySelectorAll('button');
    fireEvent.click(buttons[buttons.length - 1]!);

    await waitFor(() => expect(findDescriptor('progress-signed/addendum')).toBeTruthy());
    expect(findDescriptor('progress-signed/addendum')!.body).toEqual({
      body: 'Synthetic progress addendum',
    });
  });

  it('signs the discharge through the API and reloads both resources', async () => {
    wrap(<TherapyPage initialReferralId="ref-001" />);
    await screen.findAllByText('patient-001');
    fireEvent.change(screen.getByLabelText(therapyText('en', 'dischargeSummary')), {
      target: { value: 'Synthetic discharge body' },
    });
    fireEvent.change(screen.getByLabelText(therapyText('en', 'followUp')), {
      target: { value: 'Synthetic review' },
    });
    fireEvent.click(document.getElementById('discharge-attestation')!);
    fireEvent.click(screen.getByRole('button', { name: therapyText('en', 'signDischarge') }));

    await waitFor(() => expect(findDescriptor('/discharge')).toBeTruthy());
    const command = findDescriptor('/discharge')!;
    expect(command.headers['If-Match']).toBe('4');
    expect(command.body).toMatchObject({
      body: 'Synthetic discharge body',
      followUp: 'Synthetic review',
    });
    expect(sendMock.mock.calls.filter((call) => (call[0] as RequestDescriptor).method === 'GET').length).toBeGreaterThan(2);
  });

  it('shows the conflict state for a rejected command without losing the screen', async () => {
    const pendingPage = { items: [referral({ state: 'pending' as const, version: 1 })], nextCursor: null };
    mockByUrl(pendingPage);
    wrap(<TherapyPage initialReferralId="ref-001" />);
    await screen.findAllByText('patient-001');
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/accept')) {
        return Promise.reject(transportFailure('conflict'));
      }
      return descriptor.url.includes('/episode')
        ? Promise.resolve(episode())
        : Promise.resolve(pendingPage);
    });
    fireEvent.click(screen.getByRole('button', { name: therapyText('en', 'accept') }));

    expect(await screen.findByTestId('therapy-command-error')).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(
      screen.getByRole('table', { name: therapyText('en', 'referrals') }),
    ).toBeTruthy();
  });

  it('normalises an unexpected command rejection into a typed error state', async () => {
    const pendingPage = { items: [referral({ state: 'pending' as const, version: 1 })], nextCursor: null };
    mockByUrl(pendingPage);
    wrap(<TherapyPage initialReferralId="ref-001" />);
    await screen.findAllByText('patient-001');
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/accept')) {
        return Promise.reject(new Error('unexpected'));
      }
      return descriptor.url.includes('/episode')
        ? Promise.resolve(episode())
        : Promise.resolve(pendingPage);
    });
    fireEvent.click(screen.getByRole('button', { name: therapyText('en', 'accept') }));

    expect(await screen.findByTestId('therapy-command-error')).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });
});

describe('TherapyPage navigation', () => {
  it('auto-selects the first referral and loads a second page on demand', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/episode')) {
        return Promise.resolve(episode());
      }
      if (descriptor.url.includes('cursor=')) {
        return Promise.resolve({
          items: [referral({ id: 'ref-002', patientId: 'patient-002', state: 'pending', version: 1 })],
          nextCursor: null,
        });
      }
      return Promise.resolve({ items: [referral()], nextCursor: 'cursor-2' });
    });

    wrap(<TherapyPage />);
    expect(await screen.findAllByText('patient-001')).toBeTruthy();
    await waitFor(() => expect(findDescriptor('/episode')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: therapyText('en', 'loadMore') }));
    expect((await screen.findAllByText('patient-002')).length).toBeGreaterThan(0);
    expect(sendMock.mock.calls.some((call) => (call[0] as RequestDescriptor).url.includes('cursor=cursor-2'))).toBe(true);
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url.includes('/episode')) {
        return Promise.resolve(episode());
      }
      return descriptor.headers['X-Active-Organization'] === betaOrganizationId
        ? Promise.resolve({ items: [], nextCursor: null })
        : Promise.resolve({ items: [referral()], nextCursor: null });
    });

    const { rerender } = wrap(<TherapyPage initialReferralId="ref-001" />);
    expect((await screen.findAllByText('patient-001')).length).toBeGreaterThan(0);
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    rerender(element(<TherapyPage initialReferralId="ref-001" />, betaOrganizationId));
    expect(screen.queryByText('patient-001')).toBeNull();
  });
});

