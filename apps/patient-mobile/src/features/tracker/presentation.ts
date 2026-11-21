/**
 * Pure presentation rules for the patient tracker (PAT-02, XCH-03, UX-01).
 *
 * The server decides which facts are released to the patient; this module only
 * orders and words them. It never invents a step, never reveals a hidden fact
 * and performs no clinical decision, so each rule is traceable to a test.
 */
import { trackerText } from './messages';
import type { StepLineEntry, TrackerItem, TrackerLocale } from './types';
import { TRACKER_STEP_KEYS } from './types';

/**
 * Parse a supported locale wire key.
 *
 * @param value client or server supplied locale key
 * @returns the locale, or `null` when it is not supported
 */
export function parseTrackerLocale(value: string): TrackerLocale | null {
  return value === 'en' || value === 'ar' ? value : null;
}

/**
 * Whether a locale is laid out right-to-left (UX-01).
 *
 * @param locale resolved display locale
 * @returns true for Arabic
 */
export function isTrackerRtl(locale: TrackerLocale): boolean {
  return locale === 'ar';
}

/**
 * React Native row direction that mirrors the step line under RTL (UX-01).
 * Steppers and timelines advance with reading direction; the step data order
 * itself never changes.
 *
 * @param locale resolved display locale
 * @returns `row-reverse` for Arabic, otherwise `row`
 */
export function rowFlexDirection(locale: TrackerLocale): 'row' | 'row-reverse' {
  return locale === 'ar' ? 'row-reverse' : 'row';
}

/**
 * Build the plain-word step line for one item from the facts released to the
 * patient. Hidden facts are dropped and the remainder follow the canonical
 * step order, not the order the server happened to send them in.
 *
 * @param item the tracked item and its released facts
 * @param locale active display locale
 * @returns ordered released steps with plain-word labels
 */
export function stepLineFor(item: TrackerItem, locale: TrackerLocale): readonly StepLineEntry[] {
  return item.steps
    .filter((step) => step.releasedToPatient)
    .slice()
    .sort((left, right) => TRACKER_STEP_KEYS.indexOf(left.key) - TRACKER_STEP_KEYS.indexOf(right.key))
    .map((step) => ({ key: step.key, label: trackerText(locale, `${item.kind}.${step.key}`) }));
}
