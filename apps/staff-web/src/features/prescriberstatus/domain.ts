import type { StatusEventResponse } from '../pharmacyinbox/types';
import { statusRowLabel } from './labels';
import type { PrescriberMessageKey } from './labels';
import type {
  CatalogEntryView,
  ClarificationState,
  ProposalViewResponse,
  SignedRevisionView,
} from './types';

/** One presentational row of the pharmacy status timeline. */
export interface TimelineRow {
  id: string;
  type: string;
  labelKey: PrescriberMessageKey;
  detail: string | null;
  actor: string;
  occurredAt: string;
  dispensedBy: string | null;
  dispensedAt: string | null;
  handedOver: string | null;
}

/**
 * Project the append-only status facts into one row per fact. Dispensing
 * evidence stays on the dispensing fact and is never folded into acceptance.
 */
export function timelineRows(events: readonly StatusEventResponse[]): TimelineRow[] {
  return events.map((event) => ({
    id: event.id,
    type: event.type,
    labelKey: statusRowLabel(event.type),
    detail: event.detail,
    actor: event.actorId,
    occurredAt: event.occurredAt,
    dispensedBy: event.dispensedBy,
    dispensedAt: event.dispensedAt,
    handedOver: event.handedOver,
  }));
}

/** A reason or answer survives submission only if it contains non-blank text. */
export function hasText(value: string): boolean {
  return value.trim().length > 0;
}

/** An answer needs free text, an amended revision, or both. */
export function hasAnswer(answerBody: string, amendedRevisionId: string): boolean {
  return hasText(answerBody) || hasText(amendedRevisionId);
}

/** A clarification thread is read-only once the prescriber has answered. */
export function isAnswered(state: ClarificationState): boolean {
  return state === 'answered';
}

/** A signed revision exists only after an approved substitution. */
export function revisionLink(view: ProposalViewResponse): SignedRevisionView | null {
  return view.appliedRevision;
}

/** The originally prescribed entry stays active until an approval replaces it. */
export function activeEntry(view: ProposalViewResponse): CatalogEntryView {
  return revisionLink(view)?.replacement ?? view.proposal.original;
}

/** Present one catalog entry as a single isolated clinical label. */
export function catalogLabel(entry: CatalogEntryView): string {
  return `${entry.tradeName} (${entry.genericName}), ${entry.strengthValue} ${entry.strengthUnit}, ${entry.formulation}`;
}
