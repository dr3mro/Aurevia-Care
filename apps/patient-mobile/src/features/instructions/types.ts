/**
 * Patient-side view of MSG-04 typed instructions.
 *
 * All fields are server-owned projections; the app never authors or mutates
 * clinical content locally. Every instruction is bound to one verified
 * patient-unit connection and carries the revision it was published under.
 */

/** Site-relevant typed instruction kinds (wire keys from the API). */
export type InstructionKind =
  | 'collection'
  | 'imaging_preparation'
  | 'therapy_home'
  | 'visit_follow_up'
  | 'pharmacy_pickup'
  | 'general';

/** Lifecycle of an instruction revision. Sent revisions are immutable. */
export type InstructionState = 'draft' | 'sent' | 'superseded' | 'withdrawn';

/** Care relationship an instruction may be tied to. */
export type ContextType = 'appointment' | 'order' | 'referral' | 'prescription' | 'encounter';

/** One immutable revision projected to the verified patient. */
export interface PatientInstruction {
  /** Stable thread identifier shared by all revisions. */
  readonly threadId: string;
  /** Monotonic revision number within the thread. */
  readonly revision: number;
  /** Typed instruction kind. */
  readonly kind: InstructionKind;
  /** Localized title. */
  readonly title: string;
  /** Localized instruction text. */
  readonly text: string;
  /** BCP-47 language tag of the title and text. */
  readonly language: string;
  /** Sending care unit identifier. */
  readonly unitId: string;
  /** Human-readable care unit name. */
  readonly unitName: string;
  /** Human-readable issuing organization name. */
  readonly organizationName: string;
  /** Server issue instant as ISO-8601 text. */
  readonly issuedAt: string;
  /** Optional requested action instant as ISO-8601 text. */
  readonly actionDueAt: string | null;
  /** Linked care relationship type, when present. */
  readonly contextType: ContextType | null;
  /** Bound secure attachment identifiers. */
  readonly attachmentIds: readonly string[];
  /** Current revision state. */
  readonly state: InstructionState;
  /** Later revision that superseded this one, when present. */
  readonly supersededBy: number | null;
}

/** Text direction for a localized instruction. */
export type TextDirection = 'ltr' | 'rtl';

/** Localized display projection for one instruction. */
export interface DisplayInstruction {
  /** Title to render. */
  readonly title: string;
  /** Body to render. */
  readonly text: string;
  /** Direction to apply to the container. */
  readonly direction: TextDirection;
}
