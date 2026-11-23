import { useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import type { Breakpoint } from '../../theme/breakpoints';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { NoteRevision, SoapContent } from './domain';
import {
  chartsForTenant,
  createAmendment,
  currentRevision,
  isEditable,
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
import { ChartTabs } from './ChartTabs';
import { DocumentList } from './DocumentList';
import { FollowUpCard } from './FollowUpCard';
import { NoteEditor } from './NoteEditor';
import { OverviewCards } from './OverviewCards';
import { PatientBanner } from './PatientBanner';
import { RecentResults } from './RecentResults';

/** Keyboard-focusable scroll regions join the natural tab order. */
const FOCUSABLE_TAB_INDEX = 0;

/** Props for the tenant-scoped synthetic chart screen. */
export interface ChartScreenProps {
  charts: readonly Chart[];
  organizationId: string;
}

/**
 * Presentation-only layout of the two composed chart regions. A phone stacks
 * the chart summary above the note editor; wider bands place them side by side.
 * The class names come from the responsive stylesheet, so one DOM mirrors under
 * right-to-left without a direction override (WEB-07, UX-01).
 */
const CHART_LAYOUT: Record<Breakpoint, { className: string; columns: string }> = {
  compact: { className: 'stack', columns: 'single-column' },
  medium: { className: 'grid-2', columns: 'two-column' },
  expanded: { className: 'grid-2', columns: 'two-column' },
};

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

/** All draft, signature and document commands over the tenant chart state. */
function useChartActions(
  setState: Dispatch<SetStateAction<TenantChartState>>,
  organizationId: string,
  locale: Locale,
  patientLocalId: string,
) {
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

  /** Change one SOAP field of the named draft and announce the update. */
  function handleContentChange(current: NoteRevision, field: keyof SoapContent, value: string) {
    setState((currentState) => ({
      ...currentState,
      revisions: replaceRevision(currentState.revisions, organizationId, current.id, (item) =>
        updateDraft(item, field, value),
      ),
      announcement: chartText(locale, 'updateNotice'),
      error: '',
    }));
  }

  /** Sign the named draft; a domain rejection becomes an accessible alert. */
  function handleSign(current: NoteRevision) {
    try {
      const signed = signDraft(current, {
        signer: syntheticSigner,
        attested: true,
        signedAt: '2026-03-09T12:00:00Z',
        effectiveOn: '2026-03-09',
        patientLocalId,
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

  return {
    handleAmend,
    handleMarkEnteredInError,
    handleVoid,
    handleRotate,
    handleContentChange,
    handleSign,
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
  const breakpoint = useBreakpoint();
  const chart = chartsForTenant(charts, organizationId)[0];
  const [state, setState] = useState<TenantChartState>(() => tenantState(charts, organizationId));

  if (state.organizationId !== organizationId) {
    setState(tenantState(charts, organizationId));
  }

  const {
    handleAmend,
    handleMarkEnteredInError,
    handleVoid,
    handleRotate,
    handleContentChange,
    handleSign,
  } = useChartActions(setState, organizationId, locale, chart?.localId ?? '');

  const { revisions, documents, announcement, error } = state;

  if (!chart) {
    return (
      <section
        className="page"
        data-breakpoint={breakpoint}
        data-layout="single-column"
        aria-label={chartText(locale, 'chart')}
      >
        <h2>{chartText(locale, 'chart')}</h2>
        <p>{chartText(locale, 'noChart')}</p>
      </section>
    );
  }

  const revision = currentRevision(revisions);
  const layout = CHART_LAYOUT[breakpoint];

  /** Banner commands and follow-up outcomes are announced, never fabricated. */
  function announce(messageKey: 'previewNotice' | 'bookedNotice' | 'overrideNotice'): void {
    setState((currentState) => ({
      ...currentState,
      announcement: chartText(locale, messageKey),
      error: '',
    }));
  }

  // The approximate 320–440 px left rail uses rem so it scales with the
  // reader's font preference; below the medium band the cards stack.
  const chartColumns =
    breakpoint === 'compact'
      ? 'minmax(0, 1fr)'
      : 'minmax(var(--chart-aside-inline-size, 20rem), 27.5rem) minmax(0, 1fr)';

  return (
    <div className="chart-screen" data-breakpoint={breakpoint}>
      {chart.patientSummary === undefined ? null : (
        <PatientBanner
          summary={chart.patientSummary}
          locale={locale}
          onNewOrder={() => announce('previewNotice')}
          onPrescribe={() => announce('previewNotice')}
        />
      )}
      <ChartTabs locale={locale} />
      <section className="page" data-breakpoint={breakpoint} aria-label={chartText(locale, 'chart')}>
        <h2>{chartText(locale, 'chart')}</h2>
        <p>{chartText(locale, 'syntheticNotice')}</p>
        {error === '' ? null : (
          <p role="alert" data-testid="chart-error">
            {error}
          </p>
        )}
        <p role="status" data-testid="chart-status" aria-label={chartText(locale, 'status')}>
          {announcement}
        </p>
        <div
          className={layout.className}
          data-part="chart-content"
          data-layout={layout.columns}
          style={{ gridTemplateColumns: chartColumns }}
        >
          {/*
           * The wide clinical tables stay real tables with every column; a
           * labelled, keyboard-scrollable region contains the overflow so a
           * narrow viewport never scrolls the whole page sideways (WCAG 1.4.10).
           */}
          <div
            className="table-region"
            data-part="longitudinal"
            role="region"
            aria-label={chartText(locale, 'longitudinalChart')}
            tabIndex={FOCUSABLE_TAB_INDEX}
            style={{ minInlineSize: '0px', overflowWrap: 'anywhere' }}
          >
            <OverviewCards chart={chart} locale={locale} />
          </div>
          <div data-part="note" style={{ minInlineSize: '0px', overflowWrap: 'anywhere' }}>
            {revision ? (
              <>
                <NoteEditor
                  key={revision.id}
                  revision={revision}
                  history={revisions.filter((item) => item.id !== revision.id)}
                  locale={locale}
                  planRequired
                  onContentChange={(field, value) => handleContentChange(revision, field, value)}
                  onSign={() => handleSign(revision)}
                  onAmend={() => handleAmend(revision)}
                  onMarkEnteredInError={() => handleMarkEnteredInError(revision)}
                />
                {isEditable(revision) && chart.followUp !== undefined ? (
                  <FollowUpCard
                    suggestion={chart.followUp}
                    locale={locale}
                    onConfirmBook={() => announce('bookedNotice')}
                    onOverride={() => announce('overrideNotice')}
                  />
                ) : null}
              </>
            ) : (
              <p>{chartText(locale, 'noRevisions')}</p>
            )}
          </div>
        </div>
        {chart.recentResults === undefined ? null : (
          <RecentResults results={chart.recentResults} locale={locale} />
        )}
        <div
          className="table-region"
          data-part="documents"
          role="region"
          aria-label={chartText(locale, 'documentList')}
          tabIndex={FOCUSABLE_TAB_INDEX}
          style={{ minInlineSize: '0px', overflowWrap: 'anywhere' }}
        >
          <DocumentList
            documents={documents}
            locale={locale}
            onVoid={handleVoid}
            onRotate={handleRotate}
          />
        </div>
      </section>
    </div>
  );
}
