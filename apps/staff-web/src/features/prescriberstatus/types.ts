/**
 * Wire and view types for the prescriber's view of pharmacy events (RX-02,
 * WEB-09). Field names come from the clarification and substitution API
 * projections; every record is read-only so a screen cannot mutate a response
 * that belongs to the active tenant.
 *
 * Status facts are reused from the pharmacy inbox rather than duplicated here.
 */

/** A bounded catalog entry as published in a substitution proposal. */
export interface CatalogEntryView {
  entryId: string;
  version: number;
  genericName: string;
  tradeName: string;
  formulation: string;
  strengthValue: string;
  strengthUnit: string;
}

/** Lifecycle of a substitution proposal. */
export type SubstitutionState = 'pending' | 'approved' | 'declined';

/** Bounded, auditable reason for proposing an alternative. */
export type SubstitutionReason = 'stock' | 'brand' | 'formulation';

/** Signed revision created only when the prescriber approves. */
export interface SignedRevisionView {
  id: string;
  revisionNumber: number;
  replacement: CatalogEntryView;
  approvedBy: string;
  signedAt: string;
}

/** One review-only substitution proposal. */
export interface SubstitutionProposal {
  id: string;
  prescriptionId: string;
  patientId: string;
  prescriberSubject: string;
  proposedBy: string;
  original: CatalogEntryView;
  proposed: CatalogEntryView;
  reason: SubstitutionReason;
  note: string | null;
  state: SubstitutionState;
  version: number;
  proposedAt: string;
}

/** Proposal view placing the original and proposed entries side by side. */
export interface ProposalViewResponse {
  proposal: SubstitutionProposal;
  appliedRevision: SignedRevisionView | null;
}

/** Lifecycle of a clarification thread. */
export type ClarificationState = 'open' | 'answered';

/** The two parties of a clarification thread. */
export type ClarificationParty = 'pharmacy' | 'prescriber';

/** One immutable entry in the append-only clarification trail. */
export interface ClarificationEntryView {
  id: string;
  threadId: string;
  sequence: number;
  authorParty: ClarificationParty;
  authorSubject: string;
  body: string;
  amendedRevisionId: string | null;
  occurredAt: string;
}

/** Current projection of one clarification thread. */
export interface ClarificationThread {
  id: string;
  organizationId: string;
  prescriptionId: string;
  requesterSubject: string;
  prescriberSubject: string;
  state: ClarificationState;
  openedAt: string;
  version: number;
  answerBody: string | null;
  amendedRevisionId: string | null;
  answeredAt: string | null;
  answeredBy: string | null;
}

/** One thread with its trail and derived overdue flag. */
export interface ThreadViewResponse {
  thread: ClarificationThread;
  entries: readonly ClarificationEntryView[];
  overdue: boolean;
}

/** Ephemeral, unsaved prescriber answer input. */
export interface AnswerDraft {
  answerBody: string;
  amendedRevisionId: string;
}

/** Ephemeral, unsaved substitution decision input. */
export interface DecisionDraft {
  attestation: string;
  note: string;
}
