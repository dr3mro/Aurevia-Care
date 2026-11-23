import { useRef, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import {
  approveConsolidation,
  asDuplicatesTransportError,
  getReviewDetail,
  listDuplicateReviews,
  openReview,
} from './api';
import type {
  ApprovalFormErrors,
  ConsolidationWire,
  DuplicatePairWire,
  ReviewDetailWire,
  ReviewWire,
} from './domain';
import {
  approvalBlocker,
  approvalBody,
  approvalFormValid,
  toggleReference,
  validateApprovalForm,
} from './domain';
import { secondReviewerSubject } from './fixtures';
import type { DuplicatesMessageKey } from './messages';
import { blockerMessageKey, duplicatesText, refusalKeyForCode } from './messages';
import { ApprovalPanel, ReviewScreen, WorklistScreen } from './DuplicatesScreen';

/** Props for the page; `currentSubject` is injected because no session principal exists yet. */
export interface DuplicatesPageProps {
  currentSubject?: string;
}

/** Tenant-keyed worklist container. */
function DuplicatesContent({
  organizationId,
  currentSubject,
}: {
  organizationId: string;
  currentSubject: string;
}) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  // jsdom cannot evaluate media queries, so expose the resolved band as a data
  // attribute. It is presentation metadata only: the same DOM and actions are
  // rendered at every width (WEB-07, UX-01).
  const breakpoint = useBreakpoint();
  const [selected, setSelected] = useState<DuplicatePairWire | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  // `.stack` is a padding-free logical column: the `ApiPage` sections below
  // already own `.page` (safe-area padding and reading width), so nesting a
  // second `.page` here would double the gutters on a narrow screen.
  return (
    <div className="stack" data-breakpoint={breakpoint} style={{ gap: tokens.spacing.lg }}>
      <header className="stack" style={{ gap: tokens.spacing.sm }}>
        <h2>{duplicatesText(locale, 'title')}</h2>
        <p>{duplicatesText(locale, 'syntheticNotice')}</p>
      </header>
      {selected === null ? (
        <ApiPage
          resource={{
            label: duplicatesText(locale, 'title'),
            load: () => listDuplicateReviews(organizationId),
            deps: [organizationId, reloadToken],
          }}
          isEmpty={(rows) => rows.length === 0}
        >
          {(rows) => <WorklistScreen pairs={rows} locale={locale} onSelect={setSelected} />}
        </ApiPage>
      ) : (
        <div className="stack" style={{ gap: tokens.spacing.md }}>
          <div>
            <Button variant="secondary" onClick={() => setSelected(null)}>
              {duplicatesText(locale, 'back')}
            </Button>
          </div>
          <ReviewPanel
            key={`${selected.survivorId}:${selected.duplicateId}`}
            pair={selected}
            organizationId={organizationId}
            currentSubject={currentSubject}
            onCommitted={() => setReloadToken((value) => value + 1)}
          />
        </div>
      )}
    </div>
  );
}

/** Load the non-mutating detail for one selected pair. */
function ReviewPanel({
  pair,
  organizationId,
  currentSubject,
  onCommitted,
}: {
  pair: DuplicatePairWire;
  organizationId: string;
  currentSubject: string;
  onCommitted: () => void;
}) {
  const { locale } = useI18n();
  return (
    <ApiPage
      resource={{
        label: duplicatesText(locale, 'reviewTitle'),
        load: () => getReviewDetail(pair.survivorId, pair.duplicateId, organizationId),
        deps: [pair.survivorId, pair.duplicateId, organizationId],
      }}
    >
      {(detail) => (
        <ReviewWorkflow
          key={`${detail.duplicate.id}:${detail.duplicate.version}`}
          detail={detail}
          organizationId={organizationId}
          currentSubject={currentSubject}
          onCommitted={onCommitted}
        />
      )}
    </ApiPage>
  );
}

/** Owns the open-review and second-reviewer approval commands for one detail. */
function ReviewWorkflow({
  detail,
  organizationId,
  currentSubject,
  onCommitted,
}: {
  detail: ReviewDetailWire;
  organizationId: string;
  currentSubject: string;
  onCommitted: () => void;
}) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [review, setReview] = useState<ReviewWire | null>(detail.review);
  const [openError, setOpenError] = useState<TransportError | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState<readonly string[]>([]);
  const [alternateProcess, setAlternateProcess] = useState('');
  const [errors, setErrors] = useState<ApprovalFormErrors>({});
  const [refusal, setRefusal] = useState<DuplicatesMessageKey | undefined>(undefined);
  const [approvalError, setApprovalError] = useState<TransportError | undefined>(undefined);
  const [approved, setApproved] = useState<ConsolidationWire | undefined>(undefined);
  const opening = useRef(false);
  const approving = useRef(false);

  const blocker = approvalBlocker(review, detail.dependencies, currentSubject);
  // A server refusal wins over the advisory blocker; when neither is present
  // the panel shows no notice. The two are never rendered as duplicate alerts.
  const notice = refusal ?? (blocker === undefined ? undefined : blockerMessageKey(blocker));

  async function handleOpen() {
    if (opening.current) {
      return;
    }
    opening.current = true;
    try {
      setOpenError(undefined);
      const ticket = await openReview(
        { survivorId: detail.survivor.id, duplicateId: detail.duplicate.id },
        organizationId,
      );
      setReview(ticket);
    } catch (failure: unknown) {
      setOpenError(asDuplicatesTransportError(failure));
    } finally {
      opening.current = false;
    }
  }

  function handleToggleReference(reference: string) {
    setConfirmed((current) => toggleReference(current, reference));
  }

  async function handleApprove(ticket: ReviewWire) {
    if (approving.current) {
      return;
    }
    approving.current = true;
    try {
      setApprovalError(undefined);
      setRefusal(undefined);
      if (blocker !== undefined) {
        setRefusal(blockerMessageKey(blocker));
        return;
      }
      const validationErrors = validateApprovalForm(reason, confirmed, alternateProcess);
      setErrors(validationErrors);
      if (!approvalFormValid(validationErrors)) {
        return;
      }
      try {
        const result = await approveConsolidation(
          ticket.id,
          detail.duplicate.version,
          approvalBody(reason, confirmed, alternateProcess),
          organizationId,
        );
        setApproved(result);
        onCommitted();
      } catch (failure: unknown) {
        const error = asDuplicatesTransportError(failure);
        setApprovalError(error);
        setRefusal(refusalKeyForCode(error.code));
      }
    } finally {
      approving.current = false;
    }
  }

  return (
    <div className="stack" data-breakpoint={breakpoint} style={{ gap: tokens.spacing.lg }}>
      <ReviewScreen detail={detail} locale={locale} />
      <ApprovalPanel
        locale={locale}
        detail={detail}
        review={review}
        reason={reason}
        confirmed={confirmed}
        alternateProcess={alternateProcess}
        errors={errors}
        notice={notice}
        openError={openError}
        approvalError={approvalError}
        approved={approved}
        onOpenReview={() => {
          void handleOpen();
        }}
        onReasonChange={setReason}
        onToggleReference={handleToggleReference}
        onAlternateChange={setAlternateProcess}
        onApprove={(ticket) => {
          void handleApprove(ticket);
        }}
      />
    </div>
  );
}

/**
 * API-backed duplicate review page. The tenant key prevents a switch from
 * leaking one organization's duplicates into another; `currentSubject` is the
 * injected principal used for the advisory same-reviewer guard (the API is
 * still authoritative and refuses a self-approval with 409).
 */
export function DuplicatesPage({ currentSubject = secondReviewerSubject }: DuplicatesPageProps) {
  const organizationId = useActiveOrganization();
  return (
    <DuplicatesContent
      key={organizationId}
      organizationId={organizationId}
      currentSubject={currentSubject}
    />
  );
}
