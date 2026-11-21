import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { therapyText } from './messages';
import { TherapyScreen } from './TherapyScreen';
import type { TherapyScreenProps } from './TherapyScreen';
import {
  alphaOrganizationId,
  betaOrganizationId,
  draftSessionNote,
  signedProgressNote,
  syntheticAlphaEpisode,
  syntheticBetaEpisode,
  syntheticBetaReferrals,
  syntheticReferrals,
} from './fixtures';

function wrap(ui: ReactElement, locale: 'en' | 'ar' = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

type Callbacks = Pick<
  TherapyScreenProps,
  'onSelectReferral' | 'onAcceptReferral' | 'onLoadMore' | 'onSignNote' | 'onAddendum' | 'onDischarge'
>;

/** Render the screen with observable command callbacks and overridable data. */
function Harness({
  onSelectReferral = vi.fn(),
  onAcceptReferral = vi.fn(),
  onLoadMore = vi.fn(),
  onSignNote = vi.fn(),
  onAddendum = vi.fn(),
  onDischarge = vi.fn(),
  referrals = syntheticReferrals,
  episodes = [syntheticAlphaEpisode, syntheticBetaEpisode],
  organizationId = alphaOrganizationId,
  selectedReferralId = 'ref-001',
  nextCursor,
}: Partial<Callbacks> & Partial<TherapyScreenProps> = {}) {
  return (
    <TherapyScreen
      referrals={referrals}
      episodes={episodes}
      organizationId={organizationId}
      selectedReferralId={selectedReferralId}
      nextCursor={nextCursor}
      loadingMore={false}
      onSelectReferral={onSelectReferral}
      onAcceptReferral={onAcceptReferral}
      onLoadMore={onLoadMore}
      onSignNote={onSignNote}
      onAddendum={onAddendum}
      onDischarge={onDischarge}
    />
  );
}

function articleFor(testId: string): HTMLElement {
  const element = screen.getByTestId(testId);
  const article = element.closest('article');
  if (!article) {
    throw new Error(`no article for ${testId}`);
  }
  return article;
}

describe('TherapyScreen worklist and episode', () => {
  it('shows only the active tenant with an accept action for pending referrals', () => {
    const onAcceptReferral = vi.fn();
    wrap(<Harness onAcceptReferral={onAcceptReferral} />);
    expect(screen.getByRole('heading', { level: 2, name: therapyText('en', 'therapy') })).toBeTruthy();
    const worklist = screen.getByRole('table', { name: therapyText('en', 'referrals') });
    expect(within(worklist).getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Synthetic Layla',
      'Synthetic Omar',
      'Synthetic Sara',
    ]);
    expect(screen.queryByText('Synthetic Reem')).toBeNull();
    expect(screen.getAllByRole('button', { name: therapyText('en', 'accept') })).toHaveLength(1);

    const button = screen.getByRole('button', { name: therapyText('en', 'accept') });
    button.focus();
    expect(document.activeElement).toBe(button);
    fireEvent.click(button);
    expect(onAcceptReferral).toHaveBeenCalledWith('ref-002');
    expect(screen.getByTestId('therapy-status').textContent).toContain('Synthetic Layla');
  });

  it('reports a selection and a load-more request to the page', () => {
    const onSelectReferral = vi.fn();
    const onLoadMore = vi.fn();
    wrap(<Harness onSelectReferral={onSelectReferral} onLoadMore={onLoadMore} nextCursor="cursor-2" />);
    fireEvent.click(screen.getAllByRole('button', { name: therapyText('en', 'select') })[1]!);
    expect(onSelectReferral).toHaveBeenCalledWith('ref-002');
    fireEvent.click(screen.getByRole('button', { name: therapyText('en', 'loadMore') }));
    expect(onLoadMore).toHaveBeenCalledTimes(1);
  });

  it('shows the empty worklist and the no-episode notice when there is no data', () => {
    wrap(<Harness referrals={[]} episodes={[]} selectedReferralId="" />);
    expect(screen.getByText(therapyText('en', 'noReferrals'))).toBeTruthy();
    expect(screen.getByText(therapyText('en', 'noEpisode'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: therapyText('en', 'loadMore') })).toBeNull();
  });

  it('renders the episode overview, goals and versioned outcome scales with units', () => {
    wrap(<Harness />);
    expect(screen.getByText('Synthetic evaluation of the left knee after referral.')).toBeTruthy();
    expect(screen.getByText('Walk 50 metres independently')).toBeTruthy();
    expect(screen.getByText(therapyText('en', 'goalMet'))).toBeTruthy();
    expect(screen.getByText('Numeric pain rating (NPRS) · Version 2')).toBeTruthy();
    expect(screen.getByText('7 /10')).toBeTruthy();
    expect(screen.getByText('95 degrees')).toBeTruthy();
  });

  it('renders empty goals and scales without a table row', () => {
    const episode = {
      ...syntheticAlphaEpisode,
      goals: [],
      outcomeScales: [],
    };
    wrap(<Harness episodes={[episode]} />);
    expect(screen.getAllByText(therapyText('en', 'noReferrals')).length).toBeGreaterThan(0);
  });

  it('renders bilingual RTL copy under the Arabic locale', () => {
    wrap(<Harness />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { level: 2, name: therapyText('ar', 'therapy') }),
    ).toBeTruthy();
    expect(document.body.textContent).toContain('/10');
  });
});

describe('signed notes and addenda', () => {
  it('renders a signed note read-only and adds a change only through an addendum', () => {
    const onAddendum = vi.fn();
    wrap(<Harness onAddendum={onAddendum} />);
    const article = articleFor('read-only-body-note-signed');
    expect(within(article).getByText(therapyText('en', 'signedReadOnly'))).toBeTruthy();
    expect(within(article).queryByRole('textbox')).toBeNull();
    expect(within(article).queryByRole('button', { name: therapyText('en', 'signNote') })).toBeNull();

    fireEvent.click(within(article).getByRole('button', { name: therapyText('en', 'addAddendum') }));
    const field = within(article).getByLabelText(therapyText('en', 'addendum'));
    fireEvent.change(field, { target: { value: 'Synthetic addendum text' } });
    const buttons = within(article).getAllByRole('button', { name: therapyText('en', 'addAddendum') });
    fireEvent.click(buttons[buttons.length - 1]!);
    expect(onAddendum).toHaveBeenCalledWith('session', 'note-signed', 'Synthetic addendum text');
    expect(screen.getByTestId('therapy-status').textContent).toContain(therapyText('en', 'commandSent'));
  });

  it('signs a draft note only after attestation and with a non-empty body', () => {
    const onSignNote = vi.fn();
    wrap(<Harness onSignNote={onSignNote} />);
    const article = screen.getByRole('textbox', { name: therapyText('en', 'noteBody') }).closest('article')!;
    const signButton = within(article).getByRole('button', { name: therapyText('en', 'signNote') });
    expect((signButton as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(within(article).getByRole('textbox', { name: therapyText('en', 'noteBody') }), {
      target: { value: '' },
    });
    fireEvent.click(within(article).getByLabelText(therapyText('en', 'attestation')));
    expect((signButton as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(within(article).getByRole('textbox', { name: therapyText('en', 'noteBody') }), {
      target: { value: 'Synthetic signed body' },
    });
    expect((signButton as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(signButton);
    expect(onSignNote).toHaveBeenCalledWith(
      'session',
      'note-draft',
      'Synthetic signed body',
      '2026-03-15T12:00:00Z',
      true,
    );
    expect(within(article).getByText(therapyText('en', 'stateSigned'))).toBeTruthy();
  });

  it('scopes a signed note to its own episode when the tenant has several', () => {
    const onSignNote = vi.fn();
    const otherEpisode = {
      ...syntheticAlphaEpisode,
      id: 'episode-002',
      referralId: 'ref-003',
      sessionNotes: [],
      progressNotes: [],
    };
    wrap(<Harness episodes={[syntheticAlphaEpisode, otherEpisode]} onSignNote={onSignNote} />);
    const article = screen.getByRole('textbox', { name: therapyText('en', 'noteBody') }).closest('article')!;
    fireEvent.click(within(article).getByLabelText(therapyText('en', 'attestation')));
    fireEvent.change(within(article).getByRole('textbox', { name: therapyText('en', 'noteBody') }), {
      target: { value: 'Synthetic scoped signature' },
    });
    fireEvent.click(within(article).getByRole('button', { name: therapyText('en', 'signNote') }));
    expect(onSignNote).toHaveBeenCalledWith(
      'session',
      'note-draft',
      'Synthetic scoped signature',
      '2026-03-15T12:00:00Z',
      true,
    );
  });

  it('scopes an addendum to its own episode when the tenant has several', () => {
    const onAddendum = vi.fn();
    const otherEpisode = {
      ...syntheticAlphaEpisode,
      id: 'episode-002',
      referralId: 'ref-003',
      sessionNotes: [],
      progressNotes: [],
    };
    wrap(<Harness episodes={[syntheticAlphaEpisode, otherEpisode]} onAddendum={onAddendum} />);
    const article = articleFor('read-only-body-note-signed');
    fireEvent.click(within(article).getByRole('button', { name: therapyText('en', 'addAddendum') }));
    fireEvent.change(within(article).getByLabelText(therapyText('en', 'addendum')), {
      target: { value: 'Synthetic scoped addendum' },
    });
    const buttons = within(article).getAllByRole('button', { name: therapyText('en', 'addAddendum') });
    fireEvent.click(buttons[buttons.length - 1]!);
    expect(onAddendum).toHaveBeenCalledWith('session', 'note-signed', 'Synthetic scoped addendum');
  });

  it('signs a draft progress note and adds an addendum to a signed progress note', () => {
    const onSignNote = vi.fn();
    const onAddendum = vi.fn();
    const episode = {
      ...syntheticAlphaEpisode,
      progressNotes: [{ ...draftSessionNote, id: 'progress-draft' }, signedProgressNote],
    };
    wrap(<Harness episodes={[episode]} onSignNote={onSignNote} onAddendum={onAddendum} />);

    const draftArticle = screen
      .getAllByRole('textbox', { name: therapyText('en', 'noteBody') })[1]!
      .closest('article')!;
    fireEvent.click(within(draftArticle).getByLabelText(therapyText('en', 'attestation')));
    fireEvent.change(within(draftArticle).getByRole('textbox', { name: therapyText('en', 'noteBody') }), {
      target: { value: 'Synthetic progress body' },
    });
    fireEvent.click(within(draftArticle).getByRole('button', { name: therapyText('en', 'signNote') }));
    expect(onSignNote).toHaveBeenCalledWith(
      'progress',
      'progress-draft',
      'Synthetic progress body',
      '2026-03-15T12:00:00Z',
      true,
    );

    const signedArticle = articleFor('read-only-body-progress-signed');
    fireEvent.click(within(signedArticle).getByRole('button', { name: therapyText('en', 'addAddendum') }));
    fireEvent.change(within(signedArticle).getByLabelText(therapyText('en', 'addendum')), {
      target: { value: 'Synthetic progress addendum' },
    });
    const buttons = within(signedArticle).getAllByRole('button', { name: therapyText('en', 'addAddendum') });
    fireEvent.click(buttons[buttons.length - 1]!);
    expect(onAddendum).toHaveBeenCalledWith('progress', 'progress-signed', 'Synthetic progress addendum');
  });
});

describe('discharge signing', () => {
  it('signs a discharge, closes the referral and shows the signed summary', () => {
    const onDischarge = vi.fn();
    const otherEpisode = {
      ...syntheticAlphaEpisode,
      id: 'episode-002',
      referralId: 'ref-003',
      sessionNotes: [],
      progressNotes: [],
    };
    wrap(<Harness episodes={[syntheticAlphaEpisode, otherEpisode]} onDischarge={onDischarge} />);
    const button = screen.getByRole('button', { name: therapyText('en', 'signDischarge') });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(therapyText('en', 'dischargeSummary')), {
      target: { value: 'Synthetic discharge body' },
    });
    fireEvent.change(screen.getByLabelText(therapyText('en', 'followUp')), {
      target: { value: 'Synthetic follow-up' },
    });
    fireEvent.click(document.getElementById('discharge-attestation')!);
    expect((button as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(button);

    expect(onDischarge).toHaveBeenCalledWith(
      'Synthetic discharge body',
      'Synthetic follow-up',
      '2026-03-16T12:00:00Z',
    );
    expect(screen.getByTestId('signed-discharge')).toBeTruthy();
    expect(screen.getByText(therapyText('en', 'referrerNotified'))).toBeTruthy();
    const row = screen.getByRole('rowheader', { name: 'Synthetic Layla' }).closest('tr')!;
    expect(within(row).getByText(therapyText('en', 'stateClosed'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: therapyText('en', 'signDischarge') })).toBeNull();
  });

  it('renders an already discharged episode as read-only', () => {
    const episode = {
      ...syntheticAlphaEpisode,
      state: 'discharged' as const,
      dischargeSummary: {
        id: 'discharge-001',
        body: 'Synthetic signed summary',
        followUp: 'Synthetic review',
        signedBy: 'Synthetic Therapist',
        signedAt: '2026-03-16T12:00:00Z',
      },
    };
    wrap(<Harness episodes={[episode]} />);
    expect(screen.getByTestId('signed-discharge')).toBeTruthy();
    expect(screen.queryByRole('button', { name: therapyText('en', 'signDischarge') })).toBeNull();
  });
});

describe('tenant switching and routes', () => {
  it('clears the previous tenant rows when the active organization changes', () => {
    const { rerender } = wrap(<Harness />);
    expect(screen.getByText('Synthetic Layla')).toBeTruthy();

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Harness
            organizationId={betaOrganizationId}
            referrals={syntheticBetaReferrals}
            episodes={[syntheticBetaEpisode]}
            selectedReferralId="ref-001"
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
    expect(screen.getByText('Synthetic Reem')).toBeTruthy();
    expect(screen.getByText('Synthetic hand therapy')).toBeTruthy();
  });
});
