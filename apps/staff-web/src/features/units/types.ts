/**
 * Types for the care-unit service profile editor (MSG-06, ADM-01).
 *
 * Every value is synthetic and tenant-scoped. The wire forms live in `api.ts`;
 * this module holds only the view/domain shapes used by the screen.
 */

/**
 * The published service details a unit must show before a patient message is
 * sent (MSG-06). An empty owner or backup string means "not staffed"; no
 * real person or identifier is ever stored here.
 */
export interface ServiceDetails {
  /** Human-readable opening hours in the facility time zone. */
  serviceHours: string;
  /** Urgent response target, for example "15 minutes". */
  responseTargetUrgent: string;
  /** Routine response target, for example "1 business day". */
  responseTargetRoutine: string;
  /** Supported topics as a comma-separated, human-readable list. */
  topics: string;
  /** Site-approved urgent and downtime contact guidance. */
  urgentContactInstructions: string;
  /** Named queue owner; empty when no owner is staffed. */
  queueOwner: string;
  /** Named backup coverage; empty when the unit has no backup. */
  backupCoverage: string;
}

/** Locally editable service details plus the metadata of a proposed change. */
export interface ProfileDraft extends ServiceDetails {
  /** Named approver accountable for the change (ADM-01). */
  approver: string;
  /** Civil `YYYY-MM-DD` on which the change takes effect (ADM-01). */
  effectiveFrom: string;
}

/** The named approval attached to one immutable profile version (ADM-01). */
export interface ProfileApproval {
  approver: string;
  effectiveFrom: string;
  /** UTC instant of approval, or `null` while the version is pending. */
  approvedAt: string | null;
}

/** One tenant-scoped, versioned service profile shown by the editor. */
export interface ServiceProfile {
  organizationId: string;
  unitId: string;
  /** Optimistic version; `0` means no profile has been published yet. */
  version: number;
  enabled: boolean;
  details: ServiceDetails;
  approval: ProfileApproval;
}

/** Why a proposed change or an enable command was refused. */
export type RefusalReason =
  | 'missing-owner'
  | 'missing-approver'
  | 'invalid-effective-date'
  | 'past-effective-date';

/** Outcome of validating a proposed service-profile change. */
export type ProfileValidation = { ok: true } | { ok: false; reason: RefusalReason };
