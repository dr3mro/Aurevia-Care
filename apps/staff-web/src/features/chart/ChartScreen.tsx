import { useState } from 'react';
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

/** Mutable screen state that belongs entirely to one active tenant. */
interface TenantChartState {
  organizationId: string;
  revisions: NoteRevision[];
  documents: DocumentAssociation[];
  announcement: string;
  error: string;
}

/** Build fresh tenant-local state; local ids are shared, so state never crosses tenants. */
function tenantState(charts: readonly Chart[], organizationId: string): TenantChartState {
  const chart = chartsForTenant(charts, organizationId)[0];
  return {
    organizationId,
    revisions: [...(chart?.revisions ?? [])],
    documents: [...(chart?.documents ?? [])],
    announcement: '',
    error: '',
  };
}

/**
 * Compose the longitudinal chart, note editor and document list for one tenant.
 *
 * All mutable state is keyed to the active organization and reset during render,
 * so a switch can never paint the previous tenant's revisions or documents next
 * to the new tenant's chart (IAM-01/ARC-02): React discards a render that calls
 * `setState` on the same component and re-renders before any child is committed.
 */
export function ChartScreen({ charts, organizationId }: ChartScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const chart = chartsForTenant(charts, organizationId)[0];
  const [state, setState] = useState<TenantChartState>(() => tenantState(charts, organizationId));

  if (state.organizationId !== organizationId) {
    setState(tenantState(charts, organizationId));
  }

  const { revisions, documents, announcement, error } = state;

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
    setState((currentState) => ({
      ...currentState,
      revisions: replaceRevision(
        currentState.revisions,
        organizationId,
        current.id,
        (item) => updateDraft(item, field, value),
      ),
      announcement: chartText(locale, 'updateNotice'),
      error: '',
    }));
  }

  function handleSign(current: NoteRevision) {
    try {
      const signed = signDraft(current, {
        signer: syntheticSigner,
        attested: true,
        signedAt: '2026-03-09T12:00:00Z',
        effectiveOn: '2026-03-09',
        patientLocalId: activeChart.localId,
        encounterLocalId: syntheticEncounterLocalId,
        expectedVersion: current.version,
      });
      setState((currentState) => ({
        ...currentState,
        revisions: replaceRevision(currentState.revisions, organizationId, signed.id, () => signed),
        announcement: chartText(locale, 'signedNotice'),
        error: '',
      }));
    } catch {
      // The domain guards are authoritative and separately tested; this UI
      // boundary converts any rejected sign into an accessible announcement so
      // the draft stays intact and the screen remains operable (CLN-05).
      setState((currentState) => ({
        ...currentState,
        announcement: '',
        error: chartText(locale, 'signError'),
      }));
    }
  }

  function handleAmend(current: NoteRevision) {
    const amendment = createAmendment(current, `${current.id}-a${current.version + 1}`);
    // Supersede by the composite tenant key, as every other mutation does: a
    // local id alone is not unique across organizations (ARC-01/IAM-01).
    setState((currentState) => ({
      ...currentState,
      revisions: [
        ...replaceRevision(currentState.revisions, organizationId, current.id, (item) =>
          markSuperseded(item, amendment.id),
        ),
        amendment,
      ],
      announcement: chartText(locale, 'amendNotice'),
      error: '',
    }));
  }

  function handleMarkEnteredInError(current: NoteRevision) {
    setState((currentState) => ({
      ...currentState,
      revisions: replaceRevision(currentState.revisions, organizationId, current.id, (item) =>
        markEnteredInError(item, {
          actor: syntheticSigner,
          reason: syntheticEnteredInErrorReason,
          affectedRecipients: ['synthetic.referrer'],
          recordedAt: '2026-03-09T13:00:00Z',
        }),
      ),
      announcement: chartText(locale, 'enterErrorNotice'),
      error: '',
    }));
  }

  function handleVoid(id: string) {
    setState((currentState) => ({
      ...currentState,
      documents: replaceDocument(currentState.documents, organizationId, id, (document) =>
        voidDocument(document, {
          actor: syntheticSigner,
          reason: syntheticVoidReason,
          voidedAt: '2026-03-09T14:00:00Z',
        }),
      ),
      announcement: chartText(locale, 'voidNotice'),
      error: '',
    }));
  }

  function handleRotate(id: string) {
    setState((currentState) => ({
      ...currentState,
      documents: replaceDocument(currentState.documents, organizationId, id, (document) =>
        rotateDocument(document, 90),
      ),
      announcement: chartText(locale, 'rotateNotice'),
      error: '',
    }));
  }

  return (
    <section
      aria-label={chartText(locale, 'chart')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{chartText(locale, 'chart')}</h2>
      <p>{chartText(locale, 'syntheticNotice')}</p>
      {error ? (
        <p role="alert" data-testid="chart-error">
          {error}
        </p>
      ) : null}
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
