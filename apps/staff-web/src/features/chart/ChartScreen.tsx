import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { NoteRevision, SoapContent } from './domain';
import {
  chartsForTenant,
  createAmendment,
  currentRevision,
  markEnteredInError,
  markSuperseded,
  replaceDocument,
  replaceRevision,
  rotateDocument,
  signDraft,
  updateDraft,
  voidDocument,
} from './domain';
import type { Chart, DocumentAssociation } from './domain';
import {
  syntheticEncounterLocalId,
  syntheticEnteredInErrorReason,
  syntheticSigner,
  syntheticVoidReason,
} from './fixtures';
import { chartText } from './messages';
import { DocumentList } from './DocumentList';
import { LongitudinalChart } from './LongitudinalChart';
import { NoteEditor } from './NoteEditor';

/** Props for the tenant-scoped synthetic chart screen. */
export interface ChartScreenProps {
  charts: readonly Chart[];
  organizationId: string;
}

/** Compose the longitudinal chart, note editor and document list for one tenant. */
export function ChartScreen({ charts, organizationId }: ChartScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const chart = chartsForTenant(charts, organizationId)[0];
  const [revisions, setRevisions] = useState<NoteRevision[]>(() => [...(chart?.revisions ?? [])]);
  const [documents, setDocuments] = useState<DocumentAssociation[]>(() => [
    ...(chart?.documents ?? []),
  ]);
  const [announcement, setAnnouncement] = useState('');

  // Switching the active organization starts fresh tenant-local state; local
  // ids from the previous tenant are never carried forward.
  useEffect(() => {
    setRevisions([...(chart?.revisions ?? [])]);
    setDocuments([...(chart?.documents ?? [])]);
    setAnnouncement('');
  }, [chart, organizationId]);

  if (!chart) {
    return (
      <section aria-label={chartText(locale, 'chart')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
        <h2>{chartText(locale, 'chart')}</h2>
        <p>{chartText(locale, 'noChart')}</p>
      </section>
    );
  }

  const activeChart: Chart = chart;
  const revision = currentRevision(revisions);

  function handleContentChange(current: NoteRevision, field: keyof SoapContent, value: string) {
    setRevisions((items) =>
      replaceRevision(items, organizationId, current.id, (item) => updateDraft(item, field, value)),
    );
    setAnnouncement(chartText(locale, 'updateNotice'));
  }

  function handleSign(current: NoteRevision) {
    const signed = signDraft(current, {
      signer: syntheticSigner,
      attested: true,
      signedAt: '2026-03-09T12:00:00Z',
      effectiveOn: '2026-03-09',
      patientLocalId: activeChart.localId,
      encounterLocalId: syntheticEncounterLocalId,
      expectedVersion: current.version,
    });
    setRevisions((items) => replaceRevision(items, organizationId, signed.id, () => signed));
    setAnnouncement(chartText(locale, 'signedNotice'));
  }

  function handleAmend(current: NoteRevision) {
    const amendment = createAmendment(current, `${current.id}-a${current.version + 1}`);
    // Supersede by the composite tenant key, as every other mutation does: a
    // local id alone is not unique across organizations (ARC-01/IAM-01).
    setRevisions((items) => [
      ...replaceRevision(items, organizationId, current.id, (item) =>
        markSuperseded(item, amendment.id),
      ),
      amendment,
    ]);
    setAnnouncement(chartText(locale, 'amendNotice'));
  }

  function handleMarkEnteredInError(current: NoteRevision) {
    setRevisions((items) =>
      replaceRevision(items, organizationId, current.id, (item) =>
        markEnteredInError(item, {
          actor: syntheticSigner,
          reason: syntheticEnteredInErrorReason,
          affectedRecipients: ['synthetic.referrer'],
          recordedAt: '2026-03-09T13:00:00Z',
        }),
      ),
    );
    setAnnouncement(chartText(locale, 'enterErrorNotice'));
  }

  function handleVoid(id: string) {
    setDocuments((items) =>
      replaceDocument(items, organizationId, id, (document) =>
        voidDocument(document, {
          actor: syntheticSigner,
          reason: syntheticVoidReason,
          voidedAt: '2026-03-09T14:00:00Z',
        }),
      ),
    );
    setAnnouncement(chartText(locale, 'voidNotice'));
  }

  function handleRotate(id: string) {
    setDocuments((items) =>
      replaceDocument(items, organizationId, id, (document) => rotateDocument(document, 90)),
    );
    setAnnouncement(chartText(locale, 'rotateNotice'));
  }

  return (
    <section
      aria-label={chartText(locale, 'chart')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{chartText(locale, 'chart')}</h2>
      <p>{chartText(locale, 'syntheticNotice')}</p>
      <p role="status" data-testid="chart-status" aria-label={chartText(locale, 'status')}>
        {announcement}
      </p>
      <LongitudinalChart chart={chart} locale={locale} />
      {revision ? (
        <NoteEditor
          key={revision.id}
          revision={revision}
          history={revisions.filter((item) => item.id !== revision.id)}
          locale={locale}
          onContentChange={(field, value) => handleContentChange(revision, field, value)}
          onSign={() => handleSign(revision)}
          onAmend={() => handleAmend(revision)}
          onMarkEnteredInError={() => handleMarkEnteredInError(revision)}
        />
      ) : (
        <p>{chartText(locale, 'noRevisions')}</p>
      )}
      <DocumentList
        documents={documents}
        locale={locale}
        onVoid={handleVoid}
        onRotate={handleRotate}
      />
    </section>
  );
}
