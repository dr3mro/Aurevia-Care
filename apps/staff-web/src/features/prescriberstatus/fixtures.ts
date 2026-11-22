/**
 * Synthetic fixtures for the prescriber pharmacy view. No real person,
 * prescription or identifier is represented here; every value is scoped to a
 * synthetic tenant and prefixed for safe review.
 */

import { alphaOrganizationId } from '../pharmacyinbox/fixtures';
import type { StatusEventResponse, StatusViewResponse } from '../pharmacyinbox/types';
import type {
  CatalogEntryView,
  ClarificationEntryView,
  ClarificationThread,
  ProposalViewResponse,
  SignedRevisionView,
  SubstitutionProposal,
  ThreadViewResponse,
} from './types';

/** Synthetic tenant that owns all fixtures in this feature. */
export const prescriberOrganizationId = alphaOrganizationId;

/** The catalog entry originally prescribed. */
export const syntheticOriginalEntry: CatalogEntryView = {
  entryId: 'catalog-original',
  version: 2,
  genericName: 'Synthetic Amoxicillin',
  tradeName: 'Syntheticmox',
  formulation: 'tablet',
  strengthValue: '500',
  strengthUnit: 'mg',
};

/** The tagged catalog alternative proposed by the pharmacy. */
export const syntheticProposedEntry: CatalogEntryView = {
  entryId: 'catalog-proposed',
  version: 1,
  genericName: 'Synthetic Amoxicillin',
  tradeName: 'Syntheticmox Alternative',
  formulation: 'capsule',
  strengthValue: '500',
  strengthUnit: 'mg',
};

/** Replacement entry carried by the signed revision after approval. */
export const syntheticReplacementEntry: CatalogEntryView = {
  ...syntheticProposedEntry,
  version: 2,
};

/** Signed revision produced only by an approved substitution. */
export const syntheticSignedRevision: SignedRevisionView = {
  id: 'revision-synthetic-001',
  revisionNumber: 4,
  replacement: syntheticReplacementEntry,
  approvedBy: 'synthetic.prescriber',
  signedAt: '2026-11-21T11:30:00Z',
};

/** A pending, review-only substitution proposal. */
export const syntheticProposal: SubstitutionProposal = {
  id: 'proposal-synthetic-001',
  prescriptionId: 'rx-synthetic-001',
  patientId: 'SYN-PATIENT-001',
  prescriberSubject: 'synthetic.prescriber',
  proposedBy: 'synthetic.pharmacist',
  original: syntheticOriginalEntry,
  proposed: syntheticProposedEntry,
  reason: 'stock',
  note: 'Synthetic stock note',
  state: 'pending',
  version: 1,
  proposedAt: '2026-11-21T10:45:00Z',
};

/** Pending view: nothing has changed and no revision exists. */
export const syntheticPendingProposalView: ProposalViewResponse = {
  proposal: syntheticProposal,
  appliedRevision: null,
};

/** Approved view: the signed revision now carries the replacement. */
export const syntheticApprovedProposalView: ProposalViewResponse = {
  proposal: { ...syntheticProposal, state: 'approved' },
  appliedRevision: syntheticSignedRevision,
};

/** Declined view: the original stands and no revision was created. */
export const syntheticDeclinedProposalView: ProposalViewResponse = {
  proposal: { ...syntheticProposal, state: 'declined', note: null },
  appliedRevision: null,
};

/** One submitted fact without detail or dispensing evidence. */
export const syntheticSubmittedEvent: StatusEventResponse = {
  id: 'event-synthetic-submitted',
  organizationId: prescriberOrganizationId,
  prescriptionId: 'rx-synthetic-001',
  version: 1,
  type: 'submitted',
  actorId: 'synthetic.prescriber',
  occurredAt: '2026-11-21T09:00:00Z',
  detail: null,
  dispensedBy: null,
  dispensedAt: null,
  handedOver: null,
};

/** One partner fact with a detail run but no dispensing evidence. */
export const syntheticDeliveryEvent: StatusEventResponse = {
  ...syntheticSubmittedEvent,
  id: 'event-synthetic-delivery',
  version: 2,
  type: 'delivery_confirmed',
  actorId: 'synthetic.partner',
  occurredAt: '2026-11-21T09:30:00Z',
  detail: 'Synthetic partner confirmed receipt',
};

/** One clarification fact with a question detail. */
export const syntheticClarificationEvent: StatusEventResponse = {
  ...syntheticSubmittedEvent,
  id: 'event-synthetic-clarification',
  version: 3,
  type: 'clarification',
  occurredAt: '2026-11-21T09:40:00Z',
  detail: 'Which strength should be dispensed?',
};

/** One acceptance fact, deliberately without dispensing evidence. */
export const syntheticAcceptedEvent: StatusEventResponse = {
  ...syntheticSubmittedEvent,
  id: 'event-synthetic-accept',
  version: 4,
  type: 'accepted',
  actorId: 'synthetic.pharmacist',
  occurredAt: '2026-11-21T10:00:00Z',
};

/** One dispensing fact with all three evidence fields. */
export const syntheticDispensedEvent: StatusEventResponse = {
  ...syntheticSubmittedEvent,
  id: 'event-synthetic-dispensed',
  version: 5,
  type: 'dispensed',
  actorId: 'synthetic.pharmacist',
  occurredAt: '2026-11-21T10:30:00Z',
  dispensedBy: 'synthetic.pharmacist',
  dispensedAt: '2026-11-21T10:30:00Z',
  handedOver: '30 synthetic tablets',
};

/** One rejection fact with a reason detail. */
export const syntheticRejectedEvent: StatusEventResponse = {
  ...syntheticDeliveryEvent,
  id: 'event-synthetic-reject',
  type: 'rejected',
  detail: 'Synthetic stock unavailable',
};

/** An unrecognised but possible future fact, exercising the generic label. */
export const syntheticOtherEvent: StatusEventResponse = {
  ...syntheticSubmittedEvent,
  id: 'event-synthetic-other',
  type: 'renewal',
};

/** Status with five distinct facts, including acceptance and dispensing. */
export const syntheticStatus: StatusViewResponse = {
  organizationId: prescriberOrganizationId,
  prescriptionId: 'rx-synthetic-001',
  version: 5,
  accepted: true,
  dispensed: true,
  events: [
    syntheticSubmittedEvent,
    syntheticDeliveryEvent,
    syntheticClarificationEvent,
    syntheticAcceptedEvent,
    syntheticDispensedEvent,
  ],
};

/** Status whose only response fact is a rejection. */
export const syntheticRejectedStatus: StatusViewResponse = {
  ...syntheticStatus,
  accepted: false,
  dispensed: false,
  events: [syntheticRejectedEvent],
};

/** Status carrying an event type outside the known set. */
export const syntheticOtherStatus: StatusViewResponse = {
  ...syntheticStatus,
  events: [syntheticOtherEvent],
};

/** Status with no recorded facts. */
export const syntheticEmptyStatus: StatusViewResponse = {
  ...syntheticStatus,
  accepted: false,
  dispensed: false,
  events: [],
};

/** Pharmacy's opening question, with no amended revision. */
export const syntheticQuestionEntry: ClarificationEntryView = {
  id: 'clarification-entry-question',
  threadId: 'thread-synthetic-001',
  sequence: 1,
  authorParty: 'pharmacy',
  authorSubject: 'synthetic.pharmacist',
  body: 'Which strength should be dispensed?',
  amendedRevisionId: null,
  occurredAt: '2026-11-21T09:40:00Z',
};

/** Open, not-yet-overdue thread addressed to the prescriber. */
export const syntheticOpenThread: ClarificationThread = {
  id: 'thread-synthetic-001',
  organizationId: prescriberOrganizationId,
  prescriptionId: 'rx-synthetic-001',
  requesterSubject: 'synthetic.pharmacist',
  prescriberSubject: 'synthetic.prescriber',
  state: 'open',
  openedAt: '2026-11-21T09:40:00Z',
  version: 1,
  answerBody: null,
  amendedRevisionId: null,
  answeredAt: null,
  answeredBy: null,
};

/** Open thread view with one question entry. */
export const syntheticOpenThreadView: ThreadViewResponse = {
  thread: syntheticOpenThread,
  entries: [syntheticQuestionEntry],
  overdue: false,
};

/** Open thread view flagged overdue. */
export const syntheticOverdueThreadView: ThreadViewResponse = {
  ...syntheticOpenThreadView,
  overdue: true,
};

/** Answer entry carrying text and an amended prescription revision. */
export const syntheticAnswerEntry: ClarificationEntryView = {
  id: 'clarification-entry-answer',
  threadId: 'thread-synthetic-001',
  sequence: 2,
  authorParty: 'prescriber',
  authorSubject: 'synthetic.prescriber',
  body: 'Dispense the 500 mg tablet.',
  amendedRevisionId: 'revision-synthetic-002',
  occurredAt: '2026-11-21T11:00:00Z',
};

/** Answered thread view with both the question and the answer entries. */
export const syntheticAnsweredThreadView: ThreadViewResponse = {
  thread: {
    ...syntheticOpenThread,
    state: 'answered',
    version: 2,
    answerBody: 'Dispense the 500 mg tablet.',
    amendedRevisionId: 'revision-synthetic-002',
    answeredAt: '2026-11-21T11:00:00Z',
    answeredBy: 'synthetic.prescriber',
  },
  entries: [syntheticQuestionEntry, syntheticAnswerEntry],
  overdue: false,
};

/** An answer that carries only an amended revision and no free text. */
export const syntheticRevisionOnlyAnswerEntry: ClarificationEntryView = {
  ...syntheticAnswerEntry,
  id: 'clarification-entry-revision-only',
  body: '',
};

/** Answered thread whose answer entry has an empty body run. */
export const syntheticRevisionOnlyThreadView: ThreadViewResponse = {
  ...syntheticAnsweredThreadView,
  thread: { ...syntheticAnsweredThreadView.thread, answerBody: null },
  entries: [syntheticQuestionEntry, syntheticRevisionOnlyAnswerEntry],
};

/** Answered thread without an amended revision link. */
export const syntheticTextOnlyThreadView: ThreadViewResponse = {
  ...syntheticAnsweredThreadView,
  thread: { ...syntheticAnsweredThreadView.thread, amendedRevisionId: null },
  entries: [syntheticQuestionEntry, { ...syntheticAnswerEntry, amendedRevisionId: null }],
};
