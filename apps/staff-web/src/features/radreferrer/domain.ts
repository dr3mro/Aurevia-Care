/**
 * Pure domain model for the referring clinician's imaging report view (RAD-01).
 *
 * The signed interpretation is made of five narrated sections that must always
 * be read in clinical order. An addendum is a new revision with its own receipt;
 * it stays open until the verified referrer acknowledges that exact version.
 * This module states those rules without fetching or rendering anything.
 */

/** The five narrated sections of a signed interpretation, in clinical order. */
export const REPORT_SECTION_KEYS = [
  'technique',
  'comparison',
  'findings',
  'impression',
  'followUp',
] as const;

/** One section of a signed interpretation. */
export type ReportSectionKey = (typeof REPORT_SECTION_KEYS)[number];

/** A displayed section with its stable key and stored narrative. */
export interface ReportSection {
  key: ReportSectionKey;
  body: string;
}

/** One tenant-scoped signed interpretation delivered to a referrer. */
export interface SignedReport {
  /** Active tenant; always the first component of the display scope. */
  organizationId: string;
  /** Tenant-local order identifier the report belongs to. */
  orderId: string;
  /** Opaque matched patient identifier shown on the report. */
  patientId: string;
  /** Append-only revision number of this interpretation. */
  version: number;
  /** Authenticated interpreter whose signature binds to this report. */
  signer: string;
  /** ISO-8601 signing instant in UTC. */
  signedAt: string;
  technique: string;
  comparison: string;
  findings: string;
  impression: string;
  followUp: string;
}

/** Acknowledgment state of one delivered addendum revision. */
export type AcknowledgmentState = 'open' | 'acknowledged';

/** One addendum's receipt, scoped to a tenant, order and exact revision. */
export interface AddendumAcknowledgment {
  organizationId: string;
  orderId: string;
  version: number;
  state: AcknowledgmentState;
  /** ISO-8601 instant at which the referrer was notified. */
  notifiedAt: string;
  /** ISO-8601 acknowledgment instant, present only once acknowledged. */
  acknowledgedAt: string | null;
  /** Verified referrer who acknowledged, present only once acknowledged. */
  acknowledgedBy: string | null;
}

/**
 * Project the five narrated fields into display sections in clinical order.
 * Ordering comes from the vocabulary, never from the object's key order.
 *
 * @param report signed interpretation to project
 * @returns the sections in technique, comparison, findings, impression, follow-up order
 */
export function reportSections(report: SignedReport): ReportSection[] {
  return REPORT_SECTION_KEYS.map((key) => ({ key, body: report[key] }));
}

/**
 * Whether an addendum still awaits the referrer's acknowledgment.
 *
 * @param addendum receipt to inspect
 * @returns true only while the receipt is open
 */
export function canAcknowledge(addendum: AddendumAcknowledgment): boolean {
  return addendum.state === 'open';
}

/**
 * Scope receipts to the active tenant and order, then keep only open ones.
 * Identical revision numbers in another tenant or order are never displayed.
 *
 * @param addenda full synthetic receipt pool
 * @param organizationId active organization
 * @param orderId the report's order
 * @returns open receipts for this report in the order supplied
 */
export function openAddenda(
  addenda: readonly AddendumAcknowledgment[],
  organizationId: string,
  orderId: string,
): AddendumAcknowledgment[] {
  return addenda.filter(
    (addendum) =>
      addendum.organizationId === organizationId &&
      addendum.orderId === orderId &&
      canAcknowledge(addendum),
  );
}
