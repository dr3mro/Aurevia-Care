import type { InboxMessageKey } from './labels';
import type {
  InboundMessage,
  InboxView,
  Message,
  ScanState,
  Thread,
  ThreadState,
} from './types';

/**
 * Pure unit-inbox rules. Work-item creation and state words are derived here so
 * the screen never decides clinical state from presentation, and an unscanned
 * file is never offered as an openable control.
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

/**
 * Build the work item created by a first inbound patient message. The work item
 * is unread and unassigned so a supervisor can see outstanding work immediately
 * (MSG-02); the server remains authoritative about the connection.
 */
function newWorkItem(inbound: InboundMessage): Thread {
  return {
    id: inbound.threadId,
    patientName: inbound.patientName,
    patientId: inbound.patientId,
    topic: inbound.topic,
    state: 'new',
    owner: '',
    unread: true,
    lastActivityAt: inbound.message.sentAt,
    timeZone: inbound.message.timeZone,
    connectionVerified: true,
    version: 1,
    messages: [inbound.message],
  };
}

/**
 * Apply one inbound message: a new thread becomes a work item, and an existing
 * (including resolved) thread is reopened as new unread work. Other threads are
 * returned unchanged so a refresh never rebinds an open row to another patient.
 */
export function applyInboundMessage(view: InboxView, inbound: InboundMessage): InboxView {
  const existing = view.threads.find((thread) => thread.id === inbound.threadId);
  if (existing === undefined) {
    return { ...view, threads: [newWorkItem(inbound), ...view.threads] };
  }
  const appended: Message = inbound.message;
  return {
    ...view,
    threads: view.threads.map((thread) =>
      thread.id === inbound.threadId
        ? {
            ...thread,
            messages: [...thread.messages, appended],
            state: 'new',
            unread: true,
            lastActivityAt: appended.sentAt,
          }
        : thread,
    ),
  };
}
