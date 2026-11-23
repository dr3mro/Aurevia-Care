import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import type { Locale } from '../../i18n/messages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { approveSubstitution, declineSubstitution } from './api';
import {
  prescriberOrganizationId,
  syntheticApprovedProposalView,
  syntheticDeclinedProposalView,
  syntheticPendingProposalView,
} from './fixtures';
import { prescriberText } from './labels';
import { SubstitutionReview } from './SubstitutionReview';
import type { DecisionDraft, ProposalViewResponse } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const timeZone = 'UTC';

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

function wrap(
  view: ProposalViewResponse,
  locale: Locale = 'en',
  error?: TransportError,
): ReactElement {
  const onApprove = (draft: DecisionDraft): void => {
    void approveSubstitution(
      view.proposal.id,
      view.proposal.version,
      draft,
      prescriberOrganizationId,
    );
  };
  const onDecline = (draft: DecisionDraft): void => {
    void declineSubstitution(
      view.proposal.id,
      view.proposal.version,
      draft.note,
      prescriberOrganizationId,
    );
  };
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <SubstitutionReview
          view={view}
          locale={locale}
          timeZone={timeZone}
          {...(error ? { error } : {})}
          onApprove={onApprove}
          onDecline={onDecline}
        />
      </I18nProvider>
    </ThemeProvider>
  );
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('SubstitutionReview side-by-side comparison', () => {
  it('shows the original and proposed entries separately with the reason and pending state', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticPendingProposalView));

    expect(
      screen.getByRole('heading', { name: prescriberText('en', 'substitutionTitle') }),
    ).toBeTruthy();
    expect(screen.getByTestId('original-entry').textContent).toContain('Syntheticmox');
    expect(screen.getByTestId('proposed-entry').textContent).toContain('Syntheticmox Alternative');
    expect(screen.getByText(prescriberText('en', 'reasonStock'))).toBeTruthy();
    expect(screen.getByText(prescriberText('en', 'statePending'))).toBeTruthy();
    expect(screen.getByTestId('active-entry').textContent).toContain(
      'Syntheticmox (Synthetic Amoxicillin)',
    );
    expect(screen.getByTestId('proposal-note').textContent).toContain('Synthetic stock note');
    expect(screen.queryByTestId('revision-link')).toBeNull();
  });

  it('shows no note run when the proposal has no note', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticDeclinedProposalView));

    expect(screen.queryByTestId('proposal-note')).toBeNull();
  });
});

describe('SubstitutionReview approval', () => {
  it('refuses an approval without a prescriber attestation', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticPendingProposalView));

    fireEvent.click(screen.getByRole('button', { name: prescriberText('en', 'approve') }));

    expect(screen.getByText(prescriberText('en', 'attestationRequired'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('approves with an attestation and then links the new signed revision', () => {
    sendMock.mockResolvedValue(syntheticApprovedProposalView);
    const { rerender } = render(wrap(syntheticPendingProposalView));

    const attestation = screen.getByLabelText<HTMLInputElement>(
      prescriberText('en', 'attestation'),
    );
    const note = screen.getByLabelText<HTMLInputElement>(prescriberText('en', 'note'));
    fireEvent.change(attestation, {
      target: { value: 'I reviewed the alternative for this patient.' },
    });
    fireEvent.change(note, { target: { value: 'synthetic approval note' } });
    fireEvent.click(screen.getByRole('button', { name: prescriberText('en', 'approve') }));

    expect(descriptorAt(0).url).toBe(
      '/api/v1/pharmacy/substitutions/proposal-synthetic-001/approve',
    );
    expect(descriptorAt(0).body).toEqual({
      attestation: attestation.value,
      note: 'synthetic approval note',
    });
    expect(descriptorAt(0).headers['If-Match']).toBe('1');
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);

    rerender(wrap(syntheticApprovedProposalView));

    expect(screen.getByTestId('revision-link')).toBeTruthy();
    expect(screen.getByTestId('revision-link').textContent).toContain('revision-synthetic-001');
    expect(screen.getByTestId('active-entry').textContent).toContain('Syntheticmox Alternative');
    expect(screen.getByText(prescriberText('en', 'stateApproved'))).toBeTruthy();
  });
});

describe('SubstitutionReview decline', () => {
  it('declines with a note and keeps the original medication with no revision', () => {
    sendMock.mockResolvedValue(syntheticDeclinedProposalView);
    const { rerender } = render(wrap(syntheticPendingProposalView));

    fireEvent.change(screen.getByLabelText(prescriberText('en', 'note')), {
      target: { value: 'synthetic decline note' },
    });
    fireEvent.click(screen.getByRole('button', { name: prescriberText('en', 'decline') }));

    expect(descriptorAt(0).url).toBe(
      '/api/v1/pharmacy/substitutions/proposal-synthetic-001/decline',
    );
    expect(descriptorAt(0).body).toEqual({ attestation: '', note: 'synthetic decline note' });

    rerender(wrap(syntheticDeclinedProposalView));

    expect(screen.queryByTestId('revision-link')).toBeNull();
    expect(screen.getByTestId('active-entry').textContent).toContain(
      'Syntheticmox (Synthetic Amoxicillin)',
    );
    expect(screen.getByText(prescriberText('en', 'stateDeclined'))).toBeTruthy();
  });

  it('keeps typed work and shows the conflict state on a stale proposal', () => {
    sendMock.mockResolvedValue({});
    const { rerender } = render(wrap(syntheticPendingProposalView));
    fireEvent.change(screen.getByLabelText(prescriberText('en', 'note')), {
      target: { value: 'my unsaved note' },
    });

    rerender(
      wrap(
        syntheticPendingProposalView,
        'en',
        new TransportError('conflict', {
          correlationId: 'corr-synthetic-conflict',
          code: undefined,
          retryAfterSeconds: undefined,
        }),
      ),
    );

    expect(screen.getByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>(prescriberText('en', 'note')).value).toBe(
      'my unsaved note',
    );
  });

  it('renders Arabic labels, isolated runs and a right-to-left document', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticPendingProposalView, 'ar'));

    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { name: prescriberText('ar', 'substitutionTitle') }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: prescriberText('ar', 'approve') })).toBeTruthy();
    expect(screen.getByRole('button', { name: prescriberText('ar', 'decline') })).toBeTruthy();
    expect(screen.getByText(prescriberText('ar', 'original'))).toBeTruthy();
    expect(screen.getByText(prescriberText('ar', 'proposed'))).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
