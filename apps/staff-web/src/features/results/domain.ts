import type { Locale } from '../../i18n/messages';

/** Status carried by an XCH-04 reconciliation entry. */
export type ReconciliationStatus = 'open' | 'resolved';

/** Why an inbound report could not be imported. */
export type ReconciliationReason = 'unmatched' | 'conflict';

/** Disposition of one immutable result revision. */
export type ResultKind = 'final' | 'corrected' | 'critical';

/** Clinical review state of one immutable result revision. */
export type ReviewState = 'unreviewed' | 'acknowledged';

/** Clinical state of one XCH-05 critical result. */
export type CriticalState = 'unverified' | 'awaiting_ack' | 'acknowledged' | 'closed';

/** The five issuer-scoped identifiers matched before import (XCH-04). */
export interface ResultIdentifiers {
  patientId: string;
  sourceId: string;
  accessionId: string;
  destinationId: string;
  orderId: string;
}

/** One report waiting in the reconciliation queue. */
export interface ReconciliationEntry {
  id: string;
  identifiers: ResultIdentifiers;
  reason: ReconciliationReason;
  mismatches: string[];
  detail: string;
  status: ReconciliationStatus;
  createdAt: string;
}

/** One immutable imported result revision. */
export interface ResultReport {
  id: string;
  version: number;
  orderId: string;
  identifiers: ResultIdentifiers;
  kind: ResultKind;
  supersedesVersion: number | null;
  previousId: string | null;
  owner: string;
  coverageQueue: string;
  effectiveAt: string;
  receivedAt: string;
  checksum: string;
}

/** Human review task bound to exactly one immutable revision. */
export interface Review {
  reportId: string;
  version: number;
  state: ReviewState;
  acknowledgedAt: string | null;
}

/** One immutable correction notification. */
export interface RecipientNotification {
  id: string;
  reportId: string;
  recipient: string;
  supersededVersion: number;
  createdAt: string;
}

/** One revision together with its lineage, review and correction notices. */
export interface ResultDetail {
  current: ResultReport;
  review: Review;
  lineage: ResultReport[];
  notifications: RecipientNotification[];
}

/** Current projection of one XCH-05 critical result. */
export interface CriticalResult {
  id: string;
  version: number;
  patientId: string;
  sourceSystem: string;
  sourceId: string;
  orderReference: string | null;
  accession: string | null;
  resultSummary: string;
  observedValue: string;
  unit: string;
  readBackRequired: boolean;
  state: CriticalState;
  verifiedBySubject: string | null;
  verifiedAt: string | null;
  ownerSubject: string | null;
  dueAt: string | null;
  primaryChannel: string | null;
  backupChannel: string | null;
  backupRecipient: string | null;
  createdAt: string;
}

/** One immutable fact in a critical-result trail. */
export interface CriticalEvent {
  id: string;
  criticalResultId: string;
  criticalResultVersion: number;
  eventType: string;
  actorId: string | null;
  recipient: string | null;
  channel: string | null;
  outcome: string;
  detail: string;
  occurredAt: string;
}

/** One critical result and its complete event trail. */
export interface CriticalView {
  result: CriticalResult;
  events: CriticalEvent[];
}

/** Operational report of overdue alerts, roster gaps and failed notifications. */
export interface CriticalReport {
  overdue: CriticalResult[];
  rosterGaps: CriticalEvent[];
  failedNotifications: CriticalEvent[];
}

/** How often the critical queue recomputes its countdowns. */
export const COUNTDOWN_INTERVAL_MS = 30_000;

const SECOND_MS = 1_000;
const MINUTE_MS = 60 * SECOND_MS;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * Order critical results by their absolute due instant, keeping undated rows at
 * the end. The input array is never mutated, so a refresh cannot reorder rows a
 * clinician is reading.
 */
export function sortByDueAt(items: readonly CriticalResult[]): CriticalResult[] {
  return [...items].sort((left, right) => {
    if (left.dueAt === null) {
      return right.dueAt === null ? 0 : 1;
    }
    if (right.dueAt === null) {
      return -1;
    }
    return left.dueAt.localeCompare(right.dueAt);
  });
}

/** Whether the deadline is strictly before the supplied instant. */
export function isOverdue(dueAt: string | null, now: number): boolean {
  return dueAt !== null && Date.parse(dueAt) < now;
}

/**
 * Describe the distance to a deadline with the locale's relative-time words.
 *
 * Latin digits are requested explicitly so an Arabic interface never mixes
 * Arabic-Indic numerals into a countdown beside an Latin-script identifier. The
 * absolute due time is rendered separately by the table; this text is only the
 * distance. No live region announces a ticking countdown.
 */
export function countdownText(dueAt: string | null, now: number, locale: Locale): string {
  if (dueAt === null) {
    return locale === 'ar' ? 'لا يوجد موعد استحقاق' : 'No due time';
  }
  const remainingMs = Date.parse(dueAt) - now;
  const overdue = remainingMs < 0;
  const magnitude = Math.abs(remainingMs);
  let amount: number;
  let unit: Intl.RelativeTimeFormatUnit;
  if (magnitude >= DAY_MS) {
    amount = Math.round(magnitude / DAY_MS);
    unit = 'day';
  } else if (magnitude >= HOUR_MS) {
    amount = Math.round(magnitude / HOUR_MS);
    unit = 'hour';
  } else if (magnitude >= MINUTE_MS) {
    amount = Math.round(magnitude / MINUTE_MS);
    unit = 'minute';
  } else {
    amount = Math.round(magnitude / SECOND_MS);
    unit = 'second';
  }
  // `numberingSystem` is supported by the runtime but missing from the TS 5.7
  // lib declaration; requesting Latin digits keeps identifiers and countdowns
  // in one numeral system (RTL contract section 3).
  const options = {
    numeric: 'always',
    numberingSystem: 'latn',
  } as Intl.RelativeTimeFormatOptions;
  const formatter = new Intl.RelativeTimeFormat(locale, options);
  return formatter.format(overdue ? -amount : amount, unit);
}

/**
 * Resolve the approved channel and recipient for one escalation attempt.
 * An overdue or offline alert must use the backup; an in-hours alert uses the
 * primary channel and the named owner (XCH-05).
 */
export function escalationTarget(
  value: CriticalResult,
  now: number,
): { channel: string; recipient: string } {
  const overdue = isOverdue(value.dueAt, now);
  return {
    channel: (overdue ? value.backupChannel : value.primaryChannel) ?? '',
    recipient: (overdue ? value.backupRecipient : value.ownerSubject) ?? '',
  };
}

/** Whether a submitted reason or identifier contains non-whitespace text. */
export function isNonBlank(value: string): boolean {
  return value.trim().length > 0;
}

/** Interpret the two-option outcome selector without a cast at the call site. */
export function outcomeFrom(value: string): 'delivered' | 'failed' {
  return value === 'failed' ? 'failed' : 'delivered';
}
