import type {
  AckResult,
  DeliveryFact,
  DeliveryFacts,
  InstructionView,
  OverdueStatus,
} from './types';

/** The empty fact projection for an instruction with no recorded facts. */
export const EMPTY_FACTS: DeliveryFacts = {
  publication: null,
  dispatch: null,
  firstView: null,
  acknowledgment: null,
};

/**
 * Record the first in-app view of one revision exactly once (MSG-05).
 *
 * The view is independent of dispatch and acknowledgment: viewing an instruction
 * never records receipt. A repeat view returns the original state object, so an
 * earlier timestamp can never be overwritten.
 *
 * @param facts current fact projection
 * @param instructionId addressed instruction
 * @param revision viewed revision
 * @param viewer authenticated patient subject
 * @param occurredAt server view instant
 * @returns state containing the first-view fact
 */
export function recordFirstViewOnce(
  facts: DeliveryFacts,
  instructionId: string,
  revision: number,
  viewer: string,
  occurredAt: string,
): DeliveryFacts {
  if (facts.firstView !== null) {
    return facts;
  }
  return {
    ...facts,
    firstView: { kind: 'first_view', instructionId, revision, viewer, occurredAt },
  };
}

/**
 * Acknowledge receipt of one exact instruction revision (MSG-05).
 *
 * The acknowledgment is receipt-only. It is rejected for withdrawn instructions,
 * when no acknowledgment was requested, for a superseded revision, for a revision
 * other than the requested one, and when a receipt was already recorded.
 *
 * @param view instruction projection with revision context
 * @param facts current fact projection
 * @param revision exact revision being acknowledged
 * @param actor authenticated patient subject
 * @param acknowledgedAt server acknowledgment instant
 * @returns the receipt fact or a stable machine-readable denial
 */
export function acknowledgeRevisionBound(
  view: InstructionView,
  facts: DeliveryFacts,
  revision: number,
  actor: string,
  acknowledgedAt: string,
): AckResult {
  if (view.withdrawn) {
    return { ok: false, reason: 'WITHDRAWN' };
  }
  if (view.request === null) {
    return { ok: false, reason: 'NO_REQUEST' };
  }
  if (revision !== view.latestRevision) {
    return { ok: false, reason: 'SUPERSEDED' };
  }
  if (revision !== view.request.revision) {
    return { ok: false, reason: 'REVISION_MISMATCH' };
  }
  if (facts.acknowledgment !== null) {
    return { ok: false, reason: 'ALREADY_ACKNOWLEDGED' };
  }
  return {
    ok: true,
    fact: {
      kind: 'acknowledgment',
      instructionId: view.id,
      revision,
      actor,
      purpose: 'receipt',
      acknowledgedAt,
    },
  };
}

/**
 * Derive the patient-facing acknowledgment state from facts and time.
 *
 * @param view instruction projection
 * @param facts current fact projection
 * @param now current UTC instant as an ISO-8601 string
 * @returns the derived state; due at or after the deadline is overdue
 */
export function acknowledgmentStatus(
  view: InstructionView,
  facts: DeliveryFacts,
  now: string,
): OverdueStatus {
  if (view.withdrawn) {
    return 'withdrawn';
  }
  if (view.request === null) {
    return 'not_requested';
  }
  if (facts.acknowledgment !== null) {
    return 'acknowledged';
  }
  if (now >= view.request.dueAt) {
    return 'overdue';
  }
  return 'pending';
}

/**
 * Select instructions whose requested receipt acknowledgment is overdue.
 *
 * @param views visible instruction projections
 * @param factsByInstruction facts keyed by instruction identifier
 * @param now current UTC instant as an ISO-8601 string
 * @returns overdue instructions in input order
 */
export function overdueInstructions(
  views: readonly InstructionView[],
  factsByInstruction: Readonly<Record<string, DeliveryFacts>>,
  now: string,
): readonly InstructionView[] {
  return views.filter((view) =>
    acknowledgmentStatus(view, factsByInstruction[view.id] ?? EMPTY_FACTS, now) === 'overdue');
}

/**
 * Return the unit that owns the instruction for overdue follow-up (MSG-05).
 *
 * The owning unit comes from the instruction revision itself, never from the
 * patient's current connection or the care-unit directory.
 *
 * @param view instruction projection
 * @returns owning unit identifier
 */
export function owningUnitForOverdue(view: InstructionView): string {
  return view.unitId;
}

/**
 * Apply one server fact to a projection, writing exactly one fact slot.
 *
 * @param facts current projection
 * @param fact fact to apply
 * @returns a new projection with that fact recorded
 */
export function reduceFact(facts: DeliveryFacts, fact: DeliveryFact): DeliveryFacts {
  switch (fact.kind) {
    case 'publication':
      return { ...facts, publication: fact };
    case 'dispatch':
      return { ...facts, dispatch: fact };
    case 'first_view':
      return { ...facts, firstView: fact };
    case 'acknowledgment':
      return { ...facts, acknowledgment: fact };
    default:
      return facts;
  }
}
