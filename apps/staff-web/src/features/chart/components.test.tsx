import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { createAmendment, currentRevision, markEnteredInError } from './domain';
import type { NoteRevision } from './domain';
import {
  alphaOrganizationId,
  syntheticAlphaChart,
  syntheticBetaChart,
  syntheticEnteredInErrorReason,
  syntheticSigner,
} from './fixtures';
import { chartText } from './messages';
import { ChartTabs } from './ChartTabs';
import { DocumentList } from './DocumentList';
import { EnteredInErrorBanner } from './EnteredInErrorBanner';
import { FollowUpCard } from './FollowUpCard';
import { LongitudinalChart } from './LongitudinalChart';
import { NoteEditor } from './NoteEditor';
import { PatientBanner } from './PatientBanner';
import { RecentResults } from './RecentResults';
import { RevisionHistory } from './RevisionHistory';
import { must } from '../../../tests/must';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

const signed = (): NoteRevision => {
  const revision = syntheticAlphaChart.revisions[1];
  if (!revision) {
    throw new Error('missing signed fixture');
  }
  return revision;
};

const draft = (): NoteRevision => {
  const revision = currentRevision(syntheticBetaChart.revisions);
  if (!revision) {
    throw new Error('missing draft fixture');
  }
  return revision;
};

const noop = vi.fn();

function editorProps(revision: NoteRevision) {
  return {
    revision,
    history: syntheticAlphaChart.revisions.filter((item) => item.id !== revision.id),
    locale: 'en' as const,
    onContentChange: noop,
    onSign: noop,
    onAmend: noop,
    onMarkEnteredInError: noop,
  };
}

describe('LongitudinalChart', () => {
  it('renders coded problems, allergies, medications and measured vitals with units', () => {
    wrap(<LongitudinalChart chart={syntheticAlphaChart} locale="en" />);
    expect(screen.getByRole('region', { name: chartText('en', 'longitudinalChart') })).toBeTruthy();
    for (const label of ['problems', 'allergies', 'medications', 'vitals'] as const) {
      expect(screen.getByRole('table', { name: chartText('en', label) })).toBeTruthy();
    }
    expect(screen.getAllByText(chartText('en', 'clinicalAuthor')).length).toBe(3);
    expect(screen.getAllByText(chartText('en', 'enteredBy')).length).toBe(3);
    expect(screen.getByText(chartText('en', 'verificationNone'))).toBeTruthy();
    expect(screen.getByText(chartText('en', 'verificationUnknown'))).toBeTruthy();
    expect(screen.getAllByText(chartText('en', 'verificationVerified')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/mmHg|°C|beats\/min/).length).toBeGreaterThan(0);
    const name = screen.getByText('Synthetic Layla');
    expect(name.tagName).toBe('BDI');
    expect(name.getAttribute('dir')).toBe('auto');
  });

  it('shows explicit empty states for a chart with no coded entries', () => {
    wrap(
      <LongitudinalChart
        chart={{
          ...syntheticAlphaChart,
          problems: [],
          allergies: [],
          medications: [],
          vitals: [],
        }}
        locale="en"
      />,
    );
    expect(screen.getAllByText(chartText('en', 'noEntries'))).toHaveLength(4);
  });

  it('uses Arabic labels, local names and logical layout under RTL', () => {
    wrap(<LongitudinalChart chart={syntheticAlphaChart} locale="ar" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('region', { name: chartText('ar', 'longitudinalChart') })).toBeTruthy();
    const localName = screen.getByText('ليلى تجريبية');
    expect(localName.tagName).toBe('BDI');
    expect(localName.getAttribute('lang')).toBe('ar');
    expect(screen.getByText(chartText('ar', 'verificationNone'))).toBeTruthy();
    expect(screen.getByText('ارتفاع ضغط الدم الأساسي')).toBeTruthy();
  });
});

describe('NoteEditor', () => {
  it('edits a draft with labeled SOAP fields and an attested sign action', () => {
    const onContentChange = vi.fn();
    const onSign = vi.fn();
    wrap(
      <NoteEditor {...editorProps(draft())} onContentChange={onContentChange} onSign={onSign} />,
    );
    const subjective = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'subjective'),
    });
    expect(subjective.readOnly).toBe(false);
    fireEvent.change(subjective, { target: { value: 'Synthetic new concern' } });
    expect(onContentChange).toHaveBeenCalledWith('subjective', 'Synthetic new concern');
    const sign = screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'sign') });
    expect(sign.disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    expect(sign.disabled).toBe(false);
    fireEvent.click(sign);
    expect(onSign).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByText(chartText('en', 'signedAt'))).toBeNull();
  });

  it('renders a signed revision read-only with provenance, history and an amendment action', () => {
    const onAmend = vi.fn();
    const onMarkEnteredInError = vi.fn();
    wrap(
      <NoteEditor
        {...editorProps(signed())}
        onAmend={onAmend}
        onMarkEnteredInError={onMarkEnteredInError}
      />,
    );
    for (const field of ['subjective', 'objective', 'assessment', 'plan'] as const) {
      const control = screen.getByRole<HTMLTextAreaElement>('textbox', {
        name: chartText('en', field),
      });
      expect(control.readOnly).toBe(true);
      fireEvent.change(control, { target: { value: 'must not change' } });
      expect(control.value).not.toBe('must not change');
    }
    expect(screen.queryByRole('button', { name: chartText('en', 'sign') })).toBeNull();
    expect(screen.getByText(chartText('en', 'signedAt'))).toBeTruthy();
    expect(screen.getByText(chartText('en', 'checksum'))).toBeTruthy();
    expect(
      must(
        screen
          .getByRole('region', { name: chartText('en', 'clinicalNote') })
          .querySelector('[data-part="note-header"]'),
      ).textContent,
    ).toContain(chartText('en', 'noteSigned'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'amend') }));
    expect(onAmend).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    expect(onMarkEnteredInError).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('list', { name: chartText('en', 'history') })).toBeTruthy();
  });

  it('shows an entered-in-error alert and withholds the amendment action', () => {
    const flagged = markEnteredInError(signed(), {
      actor: syntheticSigner,
      reason: syntheticEnteredInErrorReason,
      affectedRecipients: ['synthetic.referrer'],
      recordedAt: '2026-03-09T13:00:00Z',
    });
    wrap(<NoteEditor {...editorProps(flagged)} />);
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(syntheticEnteredInErrorReason);
    expect(alert.textContent).toContain(syntheticSigner);
    expect(alert.textContent).toContain('synthetic.referrer');
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
    expect(
      screen.queryByRole('button', { name: chartText('en', 'markEnteredInError') }),
    ).toBeNull();
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(true);
  });

  it('labels an amendment draft and links it to the prior revision', () => {
    const amendment = createAmendment(signed(), 'rev-amend');
    wrap(<NoteEditor {...editorProps(amendment)} />);
    expect(screen.getByText(chartText('en', 'noteAmendment'))).toBeTruthy();
    expect(screen.getByText(signed().id, { exact: false })).toBeTruthy();
    expect(screen.queryByText(chartText('en', 'signedAt'))).toBeNull();
  });

  it('uses Arabic SOAP labels and RTL direction', () => {
    wrap(<NoteEditor {...editorProps(signed())} locale="ar" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('textbox', { name: chartText('ar', 'subjective') })).toBeTruthy();
    expect(screen.getByRole('button', { name: chartText('ar', 'amend') })).toBeTruthy();
  });
});

describe('EnteredInErrorBanner', () => {
  it('records actor, reason, recipients and time in an alert', () => {
    wrap(
      <EnteredInErrorBanner
        locale="en"
        detail={{
          actor: syntheticSigner,
          reason: syntheticEnteredInErrorReason,
          affectedRecipients: ['synthetic.referrer'],
          recordedAt: '2026-03-09T13:00:00Z',
        }}
      />,
    );
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(chartText('en', 'enteredInError'));
    expect(alert.textContent).toContain(chartText('en', 'affectedRecipients'));
    expect(alert.querySelector('time')?.getAttribute('datetime')).toBe('2026-03-09T13:00:00Z');
  });

  it('renders Arabic copy under RTL', () => {
    wrap(
      <EnteredInErrorBanner
        locale="ar"
        detail={{
          actor: syntheticSigner,
          reason: syntheticEnteredInErrorReason,
          affectedRecipients: [],
          recordedAt: '2026-03-09T13:00:00Z',
        }}
      />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('alert').textContent).toContain(chartText('ar', 'enteredInError'));
  });
});

describe('RevisionHistory', () => {
  it('lists prior revisions and flags an entered-in-error event', () => {
    wrap(<RevisionHistory revisions={syntheticAlphaChart.revisions} locale="en" />);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getAllByText(chartText('en', 'enteredInError')).length).toBeGreaterThan(0);
  });

  it('shows an empty state when there is no prior revision', () => {
    wrap(<RevisionHistory revisions={[]} locale="en" />);
    expect(screen.getByText(chartText('en', 'noEntries'))).toBeTruthy();
  });

  it('labels a draft revision in the preserved history', () => {
    wrap(<RevisionHistory revisions={[draft()]} locale="en" />);
    expect(screen.getByText(chartText('en', 'noteDraft'), { exact: false })).toBeTruthy();
  });
});

describe('DocumentList', () => {
  const documents = syntheticAlphaChart.documents;

  it('lists version, language, checksum, rotation and the void reason', () => {
    const onVoid = vi.fn();
    const onRotate = vi.fn();
    wrap(<DocumentList documents={documents} locale="en" onVoid={onVoid} onRotate={onRotate} />);
    expect(screen.getByRole('table', { name: chartText('en', 'documentList') })).toBeTruthy();
    expect(screen.getByText(chartText('en', 'languageAr'))).toBeTruthy();
    expect(
      screen.getByText(
        (content, element) =>
          element?.tagName === 'TD' && content.startsWith(chartText('en', 'documentVoided')),
      ),
    ).toBeTruthy();
    expect(
      screen.getByText(syntheticAlphaChart.documents[3]?.void?.reason ?? '', { exact: false }),
    ).toBeTruthy();
    expect(screen.getAllByText(/°/).length).toBeGreaterThan(0);
    const voidButtons = screen.getAllByRole('button', { name: chartText('en', 'void') });
    expect((voidButtons[3] as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(must(voidButtons[0]));
    expect(onVoid).toHaveBeenCalledWith('doc-001');
    fireEvent.click(must(screen.getAllByRole('button', { name: chartText('en', 'rotate') })[0]));
    expect(onRotate).toHaveBeenCalledWith('doc-001');
  });

  it('shows an empty state for a chart with no documents', () => {
    wrap(<DocumentList documents={[]} locale="en" onVoid={noop} onRotate={noop} />);
    expect(screen.getByText(chartText('en', 'noEntries'))).toBeTruthy();
  });

  it('uses local document titles and Arabic labels under RTL', () => {
    wrap(<DocumentList documents={documents} locale="ar" onVoid={noop} onRotate={noop} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('table', { name: chartText('ar', 'documentList') })).toBeTruthy();
    expect(screen.getAllByText('مستند عربي')[0]?.getAttribute('lang')).toBe('ar');
  });

  it('renders a document without a local title using the Latin title', () => {
    wrap(
      <DocumentList
        documents={[
          {
            organizationId: alphaOrganizationId,
            id: 'doc-x',
            chartLocalId: 'chart-001',
            kind: 'pdf',
            title: 'Synthetic Latin title',
            version: 1,
            language: 'en',
            checksum: 'synthetic:doc-x',
            linkedTo: 'chart:chart-001',
            rotationDegrees: 0,
          },
        ]}
        locale="ar"
        onVoid={noop}
        onRotate={noop}
      />,
      'ar',
    );
    expect(screen.getByText('Synthetic Latin title')).toBeTruthy();
  });
});

describe('PatientBanner', () => {
  const summary = () => must(syntheticAlphaChart.patientSummary);

  it('shows demographics, chips and actions and calls both action callbacks', () => {
    const onNewOrder = vi.fn();
    const onPrescribe = vi.fn();
    wrap(
      <PatientBanner
        summary={summary()}
        locale="en"
        onNewOrder={onNewOrder}
        onPrescribe={onPrescribe}
      />,
    );
    const banner = screen.getByRole('region', { name: chartText('en', 'patientBanner') });
    expect(within(banner).getByText('Synthetic Layla')).toBeTruthy();
    expect(must(banner.querySelector('[data-part="demographics"]')).textContent).toContain(
      'SYN-099804',
    );
    const chips = must(banner.querySelector('[data-part="chips"]'));
    expect(chips.textContent).toContain(chartText('en', 'allergy'));
    expect(chips.textContent).toContain(chartText('en', 'resultsToReview'));
    fireEvent.click(within(banner).getByRole('button', { name: chartText('en', 'newOrder') }));
    fireEvent.click(within(banner).getByRole('button', { name: chartText('en', 'prescribe') }));
    expect(onNewOrder).toHaveBeenCalledTimes(1);
    expect(onPrescribe).toHaveBeenCalledTimes(1);
  });

  it('shows the coded verification state of an allergy rather than a constant verified label', () => {
    const base = summary();
    const allergy = must(base.allergy);
    wrap(
      <PatientBanner
        summary={{ ...base, allergy: { ...allergy, verification: 'unverified' } }}
        locale="en"
        onNewOrder={noop}
        onPrescribe={noop}
      />,
    );
    const chip = must(screen.getByTestId('allergy-chip'));
    expect(chip.textContent).toContain(chartText('en', 'verificationUnverified'));
    expect(chip.textContent).not.toContain(chartText('en', 'verificationVerified'));
  });

  it('omits the allergy chip when the summary has no reaction', () => {
    wrap(
      <PatientBanner
        summary={{ ...summary(), allergy: undefined }}
        locale="en"
        onNewOrder={noop}
        onPrescribe={noop}
      />,
    );
    expect(screen.queryByTestId('allergy-chip')).toBeNull();
  });

  it('renders the banner under RTL in Arabic with an Arabic language chip', () => {
    wrap(
      <PatientBanner
        summary={{ ...summary(), language: 'ar' }}
        locale="ar"
        onNewOrder={noop}
        onPrescribe={noop}
      />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('ليلى تجريبية')).toBeTruthy();
    expect(must(screen.getByTestId('language-chip')).textContent).toContain(
      chartText('ar', 'languageAr'),
    );
  });
});

describe('ChartTabs', () => {
  it('marks only the selected tab as current', () => {
    wrap(<ChartTabs locale="en" />);
    const tabs = screen.getByRole('navigation', { name: chartText('en', 'chartTabs') });
    expect(within(tabs).getAllByRole('button')).toHaveLength(6);
    const overview = within(tabs).getByRole('button', { name: chartText('en', 'overview') });
    expect(overview.getAttribute('aria-current')).toBe('page');
    const results = within(tabs).getByRole('button', { name: chartText('en', 'results') });
    fireEvent.click(results);
    expect(results.getAttribute('aria-current')).toBe('page');
    expect(overview.getAttribute('aria-current')).toBeNull();
  });

  it('renders six Arabic tabs under RTL', () => {
    wrap(<ChartTabs locale="ar" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    const tabs = screen.getByRole('navigation', { name: chartText('ar', 'chartTabs') });
    expect(within(tabs).getAllByRole('button')).toHaveLength(6);
    expect(within(tabs).getByRole('button', { name: chartText('ar', 'overview') }).getAttribute('aria-current')).toBe('page');
  });
});

describe('RecentResults', () => {
  it('shows value, unit, reference range, date and a flag word, not colour alone', () => {
    const results = must(syntheticAlphaChart.recentResults);
    wrap(<RecentResults results={results} locale="en" />);
    const region = screen.getByRole('region', { name: chartText('en', 'recentResults') });
    expect(within(region).getAllByRole('listitem')).toHaveLength(results.length);
    expect(region.textContent).toContain('6.9');
    expect(region.textContent).toContain('%');
    expect(region.textContent).toContain(chartText('en', 'referenceRange'));
    expect(region.textContent).toContain(chartText('en', 'flagHigh'));
    expect(region.textContent).toContain(chartText('en', 'flagNormal'));
    expect(must(region.querySelector('time')).getAttribute('datetime')).toBe('2026-03-14');
  });

  it('shows an explicit empty state instead of a blank card', () => {
    wrap(<RecentResults results={[]} locale="en" />);
    expect(screen.getByText(chartText('en', 'noEntries'))).toBeTruthy();
  });

  it('uses Arabic flag words under RTL', () => {
    wrap(<RecentResults results={must(syntheticAlphaChart.recentResults)} locale="ar" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('region', { name: chartText('ar', 'recentResults') })).toBeTruthy();
    expect(screen.getAllByText(chartText('ar', 'flagHigh')).length).toBeGreaterThan(0);
  });
});

describe('FollowUpCard', () => {
  const suggestion = () => must(syntheticAlphaChart.followUp);

  it('shows the large suggested date and the explained rule', () => {
    wrap(
      <FollowUpCard suggestion={suggestion()} locale="en" onConfirmBook={noop} onOverride={noop} />,
    );
    expect(screen.getByRole('heading', { name: chartText('en', 'followUpSuggestion') })).toBeTruthy();
    expect(screen.getByText(chartText('en', 'followUpRule'))).toBeTruthy();
    expect(screen.getByText(suggestion().rule)).toBeTruthy();
    expect(must(screen.getAllByRole('time')[0]).getAttribute('datetime')).toBe('2026-12-19');
  });

  it('books the follow-up only after an explicit confirmation', () => {
    const onConfirmBook = vi.fn();
    wrap(
      <FollowUpCard
        suggestion={suggestion()}
        locale="en"
        onConfirmBook={onConfirmBook}
        onOverride={noop}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'confirmAndBook') }));
    expect(onConfirmBook).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain(chartText('en', 'bookingConfirm'));
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onConfirmBook).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'confirmAndBook') }));
    const reopened = screen.getByRole('dialog');
    fireEvent.click(within(reopened).getByRole('button', { name: chartText('en', 'confirmBooking') }));
    expect(onConfirmBook).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('requires a non-blank reason before an override and never books on override', () => {
    const onOverride = vi.fn();
    const onConfirmBook = vi.fn();
    wrap(
      <FollowUpCard
        suggestion={suggestion()}
        locale="en"
        onConfirmBook={onConfirmBook}
        onOverride={onOverride}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'overrideWithReason') }));
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole<HTMLButtonElement>('button', {
      name: chartText('en', 'confirmOverride'),
    });
    expect(confirm.disabled).toBe(true);
    expect(dialog.textContent).toContain(chartText('en', 'overrideReasonRequired'));
    const reason = within(dialog).getByRole('textbox', { name: chartText('en', 'overrideReason') });
    fireEvent.change(reason, { target: { value: '   ' } });
    expect(confirm.disabled).toBe(true);
    fireEvent.change(reason, { target: { value: 'Synthetic schedule conflict' } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    expect(onOverride).toHaveBeenCalledWith('Synthetic schedule conflict');
    expect(onConfirmBook).not.toHaveBeenCalled();
  });

  it('cancels an override without recording a reason', () => {
    const onOverride = vi.fn();
    wrap(
      <FollowUpCard
        suggestion={suggestion()}
        locale="en"
        onConfirmBook={noop}
        onOverride={onOverride}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'overrideWithReason') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'cancel') }));
    expect(onOverride).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('renders the follow-up card under RTL in Arabic', () => {
    wrap(
      <FollowUpCard suggestion={suggestion()} locale="ar" onConfirmBook={noop} onOverride={noop} />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { name: chartText('ar', 'followUpSuggestion') })).toBeTruthy();
  });
});

describe('NoteEditor required-field and stale recovery', () => {
  it('shows the template, revision and formatted saved time in its header', () => {
    wrap(<NoteEditor {...editorProps({ ...draft(), savedAt: '2026-03-09T09:41:00Z' })} />);
    const header = must(
      screen.getByRole('region', { name: chartText('en', 'clinicalNote') }).querySelector('[data-part="note-header"]'),
    );
    expect(header.textContent).toContain(chartText('en', 'savedAt'));
    expect(header.textContent).toContain('soap-v2');
    const saved = must(header.querySelector('time'));
    expect(saved.getAttribute('datetime')).toBe('2026-03-09T09:41:00Z');
    expect(saved.textContent ?? '').not.toBe('2026-03-09T09:41:00Z');
  });

  it('omits saved time when the revision has no savedAt', () => {
    wrap(<NoteEditor {...editorProps({ ...draft(), savedAt: undefined })} />);
    const header = must(
      screen.getByRole('region', { name: chartText('en', 'clinicalNote') }).querySelector('[data-part="note-header"]'),
    );
    expect(header.querySelector('time')).toBeNull();
    expect(header.textContent).not.toContain(chartText('en', 'savedAt'));
  });

  const fullPlan = (): NoteRevision => ({
    ...draft(),
    content: { ...draft().content, plan: 'Synthetic plan to sign.' },
  });
  const emptyPlan = (): NoteRevision => ({
    ...fullPlan(),
    content: { ...fullPlan().content, plan: '' },
  });

  it('binds an inline Plan error, marks the field invalid and keeps typed text', () => {
    const onContentChange = vi.fn();
    wrap(
      <NoteEditor
        {...editorProps(emptyPlan())}
        planRequired
        onContentChange={onContentChange}
      />,
    );
    const plan = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'plan'),
    });
    expect(plan.getAttribute('aria-invalid')).toBe('true');
    const errorId = plan.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    expect(screen.getByText(chartText('en', 'planError')).id).toBe(errorId);
    expect(screen.getByText(chartText('en', 'required'), { exact: false })).toBeTruthy();
    fireEvent.change(plan, { target: { value: 'typed plan' } });
    expect(onContentChange).toHaveBeenCalledWith('plan', 'typed plan');
    expect(plan.value).toBe('');
  });

  it('disables Sign for every side of !attested || requiredMissing', () => {
    wrap(<NoteEditor {...editorProps(fullPlan())} planRequired />);
    const sign = screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'sign') });
    const attestation = screen.getByRole('checkbox', { name: chartText('en', 'attestation') });
    expect(sign.disabled).toBe(true);
    expect(screen.getByText(chartText('en', 'signHelp'))).toBeTruthy();
    fireEvent.click(attestation);
    expect(sign.disabled).toBe(false);
    expect(screen.queryByText(chartText('en', 'signDisabledReason'))).toBeNull();
  });

  it('shows the disabled reason while attested with an empty Plan', () => {
    wrap(<NoteEditor {...editorProps(emptyPlan())} planRequired />);
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    expect(
      screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'sign') }).disabled,
    ).toBe(true);
    expect(screen.getByText(chartText('en', 'signDisabledReason'))).toBeTruthy();
  });

  it('omits the Plan gate for a wire-backed revision whose content is one string', () => {
    wrap(<NoteEditor {...editorProps(emptyPlan())} />);
    expect(screen.queryByText(chartText('en', 'planError'))).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    expect(
      screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'sign') }).disabled,
    ).toBe(false);
  });

  it('renders the conflict banner only when asked and fires every recovery callback', () => {
    const onCompare = vi.fn();
    const onReload = vi.fn();
    const onCopy = vi.fn();
    const props = {
      ...editorProps(fullPlan()),
      onCompare,
      onReload,
      onCopy,
    };
    const { rerender } = wrap(<NoteEditor {...props} />);
    expect(screen.queryByText(stateViewText('en', 'conflictDetail'))).toBeNull();
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <NoteEditor {...props} conflict />
        </I18nProvider>
      </ThemeProvider>,
    );
    const banner = screen.getByRole('alert');
    expect(banner.textContent).toContain(stateViewText('en', 'conflictDetail'));
    fireEvent.click(within(banner).getByRole('button', { name: stateViewText('en', 'compare') }));
    fireEvent.click(within(banner).getByRole('button', { name: stateViewText('en', 'reload') }));
    fireEvent.click(within(banner).getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(onCompare).toHaveBeenCalledTimes(1);
    expect(onReload).toHaveBeenCalledTimes(1);
    expect(onCopy).toHaveBeenCalledTimes(1);
  });

  it('renders the conflict recovery actions under RTL in Arabic', () => {
    wrap(<NoteEditor {...editorProps(fullPlan())} locale="ar" conflict />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { name: stateViewText('ar', 'conflict') })).toBeTruthy();
    expect(
      screen.getByRole('button', { name: stateViewText('ar', 'copyMyChanges') }),
    ).toBeTruthy();
  });
});
