/**
 * Wire shapes and pure rules for the partner interface and outage board
 * (XCH-02, XCH-03, PRD-02).
 *
 * The board keeps the three workflow axes separate: a transmission being
 * delivered never closes the clinical review, and a recipient response is its
 * own axis. Every value here is synthetic; no partner is connected.
 */

/** Partner kinds whose interface health and backlog the board reports. */
export type PartnerKind = 'lab' | 'imaging' | 'pharmacy';

/** Observed interface health, from the partner delivery signal (OPS-05). */
export type InterfaceHealth = 'healthy' | 'degraded' | 'down';

/** Site-approved manual fallback when a partner interface is unavailable. */
export type FallbackKind = 'print' | 'call' | 'manual-upload';

/** Transmission axis (XCH-02): independent of workflow and clinical review. */
export type TransmissionState = 'pending' | 'sent' | 'delivered' | 'failed';

/** Workflow axis (XCH-02), including its rejected and cancelled branches. */
export type WorkflowState =
  'requested' | 'accepted' | 'processing' | 'completed' | 'rejected' | 'cancelled';

/** Clinical review axis (XCH-02); only an authorized human can advance it. */
export type ReviewState = 'unreviewed' | 'acknowledged' | 'action-pending' | 'resolved';

/** Recipient response (XCH-03): accept, reject or request clarification. */
export type RecipientResponse = 'accepted' | 'rejected' | 'clarification-requested';

/** One order or prescription waiting on a partner interface. */
export interface WaitingItemWire {
  /** Opaque synthetic identifier, never a patient identifier. */
  id: string;
  /** Partner-issued reference shown as an isolated left-to-right run. */
  reference: string;
  itemType: 'order' | 'prescription';
  /** Whole minutes since the item was placed; a display value, not a clock. */
  ageMinutes: number;
  workflow: WorkflowState;
  transmission: TransmissionState;
  review: ReviewState;
  /** Null until the recipient responds. */
  recipientResponse: RecipientResponse | null;
}

/** One partner's interface health and waiting backlog. */
export interface PartnerBoardWire {
  kind: PartnerKind;
  /** Display name in the partner's own script; isolated when rendered. */
  displayName: string;
  health: InterfaceHealth;
  /** UTC instant of the last health observation. */
  lastCheckedAt: string;
  /** IANA zone used to render `lastCheckedAt`. */
  timeZone: string;
  waiting: readonly WaitingItemWire[];
}

/** Minutes after which a pending transmission is reported as stuck. */
export const STALE_WAIT_MINUTES = 30;

/**
 * Decide whether an item needs attention on the outage board.
 *
 * A failed transmission, a stale pending transmission, a rejection or a
 * clarification request all block progress. A delivered transmission does not
 * close an unreviewed or action-pending clinical task (XCH-02).
 */
export function isStuck(item: WaitingItemWire): boolean {
  if (item.transmission === 'failed') {
    return true;
  }
  if (item.transmission === 'pending' && item.ageMinutes >= STALE_WAIT_MINUTES) {
    return true;
  }
  if (item.recipientResponse !== null && item.recipientResponse !== 'accepted') {
    return true;
  }
  return item.review === 'unreviewed' || item.review === 'action-pending';
}

/** The approved manual fallback for each partner kind (PRD-02). */
const FALLBACK_BY_PARTNER: Readonly<Record<PartnerKind, FallbackKind>> = {
  lab: 'manual-upload',
  imaging: 'call',
  pharmacy: 'print',
};

/** Resolve the manual fallback an operator must use for a partner. */
export function fallbackFor(kind: PartnerKind): FallbackKind {
  return FALLBACK_BY_PARTNER[kind];
}

/** Total number of orders and prescriptions waiting for a partner. */
export function waitingCount(board: PartnerBoardWire): number {
  return board.waiting.length;
}

/** Number of waiting items that need attention now. */
export function stuckCount(board: PartnerBoardWire): number {
  return board.waiting.filter(isStuck).length;
}

/** Tag a partner name for isolated display from its script, not the UI locale. */
export function scriptForName(text: string): 'ar' | 'en' {
  return /[\u0600-\u06FF]/.test(text) ? 'ar' : 'en';
}
