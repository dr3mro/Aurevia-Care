/**
 * View and command types for the MSG-02/MSG-06 unit inbox (WEB-09).
 *
 * Thread and message field names follow the conversation projection. The unit
 * strip carries the service facts MSG-06 requires before a reply is sent. All
 * fixtures are synthetic; no patient identifier is placed in a route.
 */

import type { PersonName } from '../../i18n/format';

/** The four work-item states shown in the thread list. */
export const THREAD_STATES = ['new', 'replied', 'waiting', 'resolved'] as const;

/** One thread work-item state. */
export type ThreadState = (typeof THREAD_STATES)[number];

/** The three attachment scan states; only clean files may be opened. */
export const SCAN_STATES = ['clean', 'unscanned', 'failed'] as const;

/** One attachment scan state. */
export type ScanState = (typeof SCAN_STATES)[number];

/** Filesystem scan result shown beside an attachment name. */
export interface Attachment {
  readonly id: string;
  readonly fileName: string;
  readonly sizeLabel: string;
  readonly scanState: ScanState;
}

/** One immutable message in a conversation trail. */
export interface Message {
  readonly id: string;
  readonly senderRole: 'patient' | 'staff';
  readonly senderName: string;
  readonly body: string;
  readonly sentAt: string;
  readonly timeZone: string;
  readonly attachments: readonly Attachment[];
}

/** One unit's conversation work item with its complete message trail. */
export interface Thread {
  readonly id: string;
  readonly patientName: PersonName;
  readonly patientId: string;
  readonly topic: string;
  readonly state: ThreadState;
  readonly owner: string;
  readonly unread: boolean;
  readonly lastActivityAt: string;
  readonly timeZone: string;
  readonly connectionVerified: boolean;
  readonly version: number;
  readonly messages: readonly Message[];
}

/** The service facts a unit publishes under MSG-06. */
export interface UnitProfile {
  readonly name: string;
  readonly serviceHours: string;
  readonly responseTarget: string;
  readonly owner: string;
  readonly backup: string;
}

/** One tenant's complete unit inbox. */
export interface InboxView {
  readonly unit: UnitProfile;
  readonly threads: readonly Thread[];
  readonly pendingFileNames: readonly string[];
}

/** A reply explicitly naming its recipient and staged files. */
export interface SendDraft {
  readonly threadId: string;
  readonly version: number;
  readonly recipientName: string;
  readonly recipientId: string;
  readonly draft: string;
  readonly pendingFiles: readonly string[];
}

/** Commands that require a reason and a version precondition. */
export interface WorkDraft {
  readonly threadId: string;
  readonly version: number;
  readonly action: 'assign' | 'resolve';
  readonly reason: string;
  readonly assignee: string;
}

