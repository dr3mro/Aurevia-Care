import type { Locale } from '../../i18n/messages';
import type { QueueAction, QueueCollection, QueueId, QueueLoad, QueueRow } from './types';
import { queueIds } from './types';

/** Wire shape of `GET /api/v1/exchange/work-items`. */
export interface WorkItemWire {
  id: string;
  senderOrg: string;
  destinationOrg: string;
  orderRef: string;
  patientRef: string;
  patientMrn: string;
  mrnIssuer: string;
  localPatientId: string;
  patientVerified: boolean;
  priority: string;
  clinicalQuestion: string;
  workQueue: string;
  fingerprint: string;
  state: string;
  createdAt: string;
}

/** Wire shape of `GET /api/v1/delivery/dead-letters`. */
export interface DeadLetterWire {
  eventId: string;
  organizationId: string;
  reason: string;
  attempt: number;
  failedAt: string;
  reviewer: string | null;
  reviewedAt: string | null;
  resolution: string | null;
  reviewReason: string | null;
}

/** Wire shape of `GET /api/v1/messaging/instructions/overdue-follow-ups`. */
export interface FollowUpWire {
  organizationId: string;
  instructionId: string;
  revision: number;
  unitId: string;
  routedAt: string;
  state: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
  reason: string | null;
}

/** Wire shape of `GET /api/v1/duplicate-reviews` (names are not projected). */
export interface DuplicatePairWire {
  survivorId: string;
  survivorName: string;
  duplicateId: string;
  duplicateName: string;
}

/** Wire shape of `GET /api/v1/clinic/worklist`. */
export interface WorklistItemWire {
  id: string;
  appointmentId: string;
  intakeReason: string;
  patientId: string;
  facilityCode: string;
  startsAt: string;
  priority: number;
  assigneeSubject: string | null;
  assignedAt: string | null;
  version: number;
}

/** Actions a queue supports, restricted to endpoints the API provides. */
export function actionsFor(queue: QueueId): readonly QueueAction[] {
  switch (queue) {
    case 'work-items':
      return ['review'];
    case 'dead-letters':
      return ['retry', 'review'];
    case 'overdue-follow-ups':
      return ['review'];
    case 'duplicate-reviews':
      return ['review'];
    case 'clinic-worklist':
      return ['assign', 'escalate'];
  }
}

/**
 * Project one queue's raw response into display rows. The input is trusted to
 * be the server projection; this boundary only selects fields, never displays
 * patient names or free-text clinical payloads.
 */
export function normalizeQueue(queue: QueueId, raw: readonly unknown[]): QueueRow[] {
  switch (queue) {
    case 'work-items':
      return (raw as readonly WorkItemWire[]).map((item) => ({
        id: item.id,
        primary: item.orderRef,
        detail: item.localPatientId,
        state: item.state,
        occurredAt: item.createdAt,
        version: undefined,
        localMrn: item.patientMrn,
        mrnIssuer: item.mrnIssuer,
        survivorId: undefined,
        duplicateId: undefined,
      }));
    case 'dead-letters':
      return (raw as readonly DeadLetterWire[]).map((entry) => ({
        id: entry.eventId,
        primary: entry.eventId,
        detail: entry.reason,
        state: entry.reviewer === null ? 'pending review' : 'reviewed',
        occurredAt: entry.failedAt,
        version: undefined,
        localMrn: undefined,
        mrnIssuer: undefined,
        survivorId: undefined,
        duplicateId: undefined,
      }));
    case 'overdue-follow-ups':
      return (raw as readonly FollowUpWire[]).map((followUp) => ({
        id: followUp.instructionId,
        primary: followUp.instructionId,
        detail: followUp.reason ?? 'No reason recorded',
        state: followUp.state,
        occurredAt: followUp.routedAt,
        version: followUp.revision,
        localMrn: undefined,
        mrnIssuer: undefined,
        survivorId: undefined,
        duplicateId: undefined,
      }));
    case 'duplicate-reviews':
      // The duplicate-review list carries no timestamp, so age is unavailable.
      return (raw as readonly DuplicatePairWire[]).map((pair) => ({
        id: `${pair.survivorId}:${pair.duplicateId}`,
        primary: pair.survivorId,
        detail: pair.duplicateId,
        state: 'pending review',
        occurredAt: undefined,
        version: undefined,
        localMrn: undefined,
        mrnIssuer: undefined,
        survivorId: pair.survivorId,
        duplicateId: pair.duplicateId,
      }));
    case 'clinic-worklist':
      return (raw as readonly WorklistItemWire[]).map((item) => ({
        id: item.id,
        primary: item.patientId,
        detail: item.intakeReason,
        state: item.assigneeSubject === null ? 'unassigned' : 'assigned',
        occurredAt: item.startsAt,
        version: item.version,
        localMrn: undefined,
        mrnIssuer: undefined,
        survivorId: undefined,
        duplicateId: undefined,
      }));
  }
}

/** Earliest server instant in a queue, or `undefined` when none is available. */
export function oldestOccurrence(rows: readonly QueueRow[]): string | undefined {
  const instants = rows
    .map((row) => row.occurredAt)
    .filter((instant): instant is string => instant !== undefined);
  if (instants.length === 0) {
    return undefined;
  }
  return instants.reduce((oldest, instant) =>
    Date.parse(instant) < Date.parse(oldest) ? instant : oldest,
  );
}

/** Non-negative age of a server instant relative to an injected clock. */
export function ageMs(occurredAt: string, nowMs: number): number {
  return Math.max(0, nowMs - Date.parse(occurredAt));
}

/**
 * Render an age with the runtime's locale rules and Latin digits. A relative
 * phrase is paired with the queue's absolute server instant by the screen.
 */
export function formatAge(age: number, locale: Locale): string {
  const relative = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  const minutes = Math.floor(age / 60_000);
  if (minutes < 60) {
    return relative.format(-minutes, 'minute');
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return relative.format(-hours, 'hour');
  }
  return relative.format(-Math.floor(hours / 24), 'day');
}

/** Count of ready rows per queue; a failed queue contributes no rows. */
function countOf(load: QueueLoad): number {
  return load.status === 'ready' ? load.rows.length : 0;
}

/** Row counts for the tab labels and the role-aware home page. */
export function queueCounts(collection: QueueCollection): Record<QueueId, number> {
  const counts = {} as Record<QueueId, number>;
  for (const queue of queueIds) {
    counts[queue] = countOf(collection[queue]);
  }
  return counts;
}
