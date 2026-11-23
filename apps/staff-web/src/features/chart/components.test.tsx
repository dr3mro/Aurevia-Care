import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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
import { DocumentList } from './DocumentList';
import { EnteredInErrorBanner } from './EnteredInErrorBanner';
import { LongitudinalChart } from './LongitudinalChart';
import { NoteEditor } from './NoteEditor';
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
