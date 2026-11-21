/**
 * Patient-visible "Where is it?" tracker contracts (PAT-02, XCH-03).
 *
 * These are projections of the server's journey timeline: a patient never sees
 * internal workflow states, only facts the server marked as released to the
 * patient. The app holds no clinical payload or persistent cache — tracker data
 * is rendered from the authenticated response and discarded on sign-out.
 */
import type { Locale } from '../../app/messages';

/** Supported display locales; the patient app already owns this vocabulary. */
export type TrackerLocale = Locale;

/** Item kinds whose journey the patient can track. */
export const TRACKER_ITEM_KINDS = ['prescription', 'lab_order', 'imaging_order'] as const;

/** One kind of item whose journey is projected. */
export type TrackerItemKind = (typeof TRACKER_ITEM_KINDS)[number];

/**
 * Canonical step vocabulary, in timeline order, shared with the server's
 * journey read model. The client does not invent steps; it reuses these wire
 * keys so a released fact renders in the same position for every item kind.
 */
export const TRACKER_STEP_KEYS = [
  'placed',
  'delivered',
  'accepted',
  'collected_or_scheduled',
  'resulted',
  'released',
  'acknowledged',
] as const;

/** One canonical journey step. */
export type TrackerStepKey = (typeof TRACKER_STEP_KEYS)[number];

/**
 * One step the server included for an item.
 *
 * `releasedToPatient` is authoritative: the server sets it only when the fact
 * is approved for this patient. A false value is a hidden internal step and is
 * never shown, even though it exists in the workflow.
 */
export interface TrackerStep {
  readonly key: TrackerStepKey;
  readonly releasedToPatient: boolean;
}

/** One item and the journey facts the patient is allowed to see for it. */
export interface TrackerItem {
  readonly itemId: string;
  readonly kind: TrackerItemKind;
  readonly steps: readonly TrackerStep[];
}

/** One released step rendered in the patient's plain-word step line. */
export interface StepLineEntry {
  readonly key: TrackerStepKey;
  readonly label: string;
}
