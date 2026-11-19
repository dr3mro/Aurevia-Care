/**
 * MSG-05 transport-safe projections for the patient app.
 *
 * Publication, notification dispatch, first in-app view and explicit patient
 * acknowledgment are four independent facts. An acknowledgment confirms receipt
 * of one exact instruction revision only; it has no consent, task-completion or
 * critical-result fields, which belong to their own workflows.
 */

/** The fact kind carried by an append-only MSG-05 fact. */
export type FactKind = 'publication' | 'dispatch' | 'first_view' | 'acknowledgment';

/** An instruction revision was published by its owning unit. */
export interface PublicationFact {
  readonly kind: 'publication';
  readonly instructionId: string;
  readonly revision: number;
  readonly organizationId: string;
  readonly occurredAt: string;
}

/** A generic notification was dispatched; it holds no clinical content. */
export interface DispatchFact {
  readonly kind: 'dispatch';
  readonly instructionId: string;
  readonly revision: number;
  readonly genericKey: 'NEW_INSTRUCTION';
  readonly correlationId: string;
  readonly occurredAt: string;
}

/** The patient first viewed this revision in the app. */
export interface FirstViewFact {
  readonly kind: 'first_view';
  readonly instructionId: string;
  readonly revision: number;
  readonly viewer: string;
  readonly occurredAt: string;
}

/** The patient acknowledged receipt of one exact revision. */
export interface AcknowledgmentFact {
  readonly kind: 'acknowledgment';
  readonly instructionId: string;
  readonly revision: number;
  readonly actor: string;
  readonly purpose: 'receipt';
  readonly acknowledgedAt: string;
}

/** Any one MSG-05 fact. */
export type DeliveryFact = PublicationFact | DispatchFact | FirstViewFact | AcknowledgmentFact;

/** The explicit receipt acknowledgment requested for a revision. */
export interface RequestedAcknowledgment {
  readonly revision: number;
  readonly dueAt: string;
}

/** Patient-visible instruction projection, including revision context. */
export interface InstructionView {
  readonly id: string;
  readonly organizationId: string;
  readonly patientId: string;
  readonly unitId: string;
  readonly revision: number;
  readonly latestRevision: number;
  readonly withdrawn: boolean;
  readonly title: string;
  readonly request: RequestedAcknowledgment | null;
}

/** The four independent fact slots for one instruction. */
export interface DeliveryFacts {
  readonly publication: PublicationFact | null;
  readonly dispatch: DispatchFact | null;
  readonly firstView: FirstViewFact | null;
  readonly acknowledgment: AcknowledgmentFact | null;
}

/** Machine-readable reason an acknowledgment was not recorded. */
export type AcknowledgeReason =
  | 'WITHDRAWN'
  | 'NO_REQUEST'
  | 'SUPERSEDED'
  | 'REVISION_MISMATCH'
  | 'ALREADY_ACKNOWLEDGED';

/** Result of an acknowledgment command. */
export type AckResult =
  | { readonly ok: true; readonly fact: AcknowledgmentFact }
  | { readonly ok: false; readonly reason: AcknowledgeReason };

/** Derived patient-facing acknowledgment state. */
export type OverdueStatus =
  | 'withdrawn'
  | 'not_requested'
  | 'acknowledged'
  | 'overdue'
  | 'pending';

/** A generic push notification representation with no clinical detail. */
export interface GenericNotification {
  readonly title: string;
  readonly body: string;
  readonly deepLink: string;
}

/** Synthetic fixture backing the local MSG-05 client. */
export interface MessageFactsFixture {
  readonly organizationId: string;
  readonly viewer: string;
  readonly instructions: readonly InstructionView[];
  readonly facts: Readonly<Record<string, DeliveryFacts>>;
  readonly now: string;
  readonly readToken: () => string | null;
}

/** Typed boundary for authenticated MSG-05 patient commands. */
export interface MessageFactsClient {
  loadInstructionAfterAuth(instructionId: string): Promise<InstructionView>;
  markFirstView(instructionId: string, revision: number): Promise<FirstViewFact>;
  acknowledge(instructionId: string, revision: number): Promise<AckResult>;
  overdueInstructions(now: string): readonly InstructionView[];
  owningUnitFor(instructionId: string): string;
}
