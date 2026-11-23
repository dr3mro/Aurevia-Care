import type { InboxMessageKey } from './labels';
import type { ScanState, ThreadState } from './types';

/**
 * Pure unit-inbox rules. State and scan words are resolved here so the screen
 * never decides a clinical or file-safety state from colour or presentation,
 * and an unscanned file is never offered as an openable control. Work items
 * themselves are created server-authoritatively and arrive through `listInbox`.
 */
export type ThreadTone = 'urgent' | 'waiting' | 'calm';

/** The bilingual state word for each work-item state. */
const STATE_WORD: Record<ThreadState, InboxMessageKey> = {
  new: 'stateNew',
  replied: 'stateReplied',
  waiting: 'stateWaiting',
  resolved: 'stateResolved',
};

/** The presentation role for each work-item state. */
const STATE_TONE: Record<ThreadState, ThreadTone> = {
  new: 'urgent',
  replied: 'waiting',
  waiting: 'waiting',
  resolved: 'calm',
};

/** The bilingual scan word for each attachment scan state. */
const SCAN_WORD: Record<ScanState, InboxMessageKey> = {
  clean: 'scanClean',
  unscanned: 'scanUnscanned',
  failed: 'scanFailed',
};

/** Resolve the word key naming a work-item state. */
export function stateWordKey(state: ThreadState): InboxMessageKey {
  return STATE_WORD[state];
}

/** Resolve the presentation role for a work-item state. */
export function stateTone(state: ThreadState): ThreadTone {
  return STATE_TONE[state];
}

/** Resolve the word key naming an attachment scan result. */
export function scanWordKey(scan: ScanState): InboxMessageKey {
  return SCAN_WORD[scan];
}

/** Only a clean attachment may be opened; other states stay visible but inert. */
export function canOpenAttachment(scan: ScanState): boolean {
  return scan === 'clean';
}

/** True when the composer holds something worth sending. */
export function hasDraftContent(value: string): boolean {
  return value.trim().length > 0;
}

/** True when an Assign or Resolve reason contains non-whitespace content. */
export function hasReason(value: string): boolean {
  return value.trim().length > 0;
}

