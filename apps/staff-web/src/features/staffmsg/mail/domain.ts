import type { MailMessageKey } from './labels';
import type {
  ComposeDraft,
  DirectoryEntry,
  FolderCounts,
  MailActor,
  MailFolder,
  MailMessageView,
  RecipientKind,
} from './types';

/**
 * Pure staff-mail rules (MSG-02, MSG-03, WEB-09).
 *
 * The recipient scope and reply rules live here so the screen never decides a
 * tenant or facility boundary from presentation, and confirmation never depends
 * on what the user happened to see on screen. Role expansion uses the supplied
 * tenant-scoped directory; the server remains authoritative at send time.
 */

/** Bilingual word for each folder. */
const FOLDER_WORD: Record<MailFolder, MailMessageKey> = {
  inbox: 'folderInbox',
  sent: 'folderSent',
  drafts: 'folderDrafts',
  archive: 'folderArchive',
  trash: 'folderTrash',
};

/** Bilingual word for each recipient kind. */
const KIND_WORD: Record<RecipientKind, MailMessageKey> = {
  person: 'kindPerson',
  role: 'kindRole',
  unit_mailbox: 'kindUnitMailbox',
};

/** True when a draft holds anything worth autosaving or sending. */
export function hasMailContent(draft: ComposeDraft): boolean {
  return [
    draft.subject.trim().length > 0,
    draft.body.trim().length > 0,
    draft.to.length > 0,
    draft.cc.length > 0,
    draft.attachments.length > 0,
  ].includes(true);
}

/** Resolve a folder to its bilingual label key. */
export function folderWordKey(folder: MailFolder): MailMessageKey {
  return FOLDER_WORD[folder];
}

/** Resolve a recipient kind to its bilingual label key. */
export function recipientKindWordKey(kind: RecipientKind): MailMessageKey {
  return KIND_WORD[kind];
}

/** Read one folder's count, including a folder that holds no messages. */
export function folderCount(counts: FolderCounts, folder: MailFolder): number {
  return counts[folder];
}

/** Reply goes to the sender alone. */
export function replyValues(message: MailMessageView): string[] {
  return [message.from.value];
}

/**
 * Reply-all names the sender, everyone in To and Cc, without the author and
 * without repeats. A Set keeps the rule independent of display order.
 */
export function replyAllValues(message: MailMessageView, selfValue: string): string[] {
  const values = [
    message.from.value,
    ...message.to.map((recipient) => recipient.value),
    ...message.cc.map((recipient) => recipient.value),
  ];
  return Array.from(new Set(values)).filter((value) => value !== selfValue);
}

/** The visible recipient values the author is about to confirm. */
export function confirmedRecipientValues(draft: ComposeDraft): string[] {
  return [...draft.to, ...draft.cc].map((recipient) => recipient.value);
}

/**
 * Expand confirmed recipients to the address values the server will resolve.
 * A role maps to every holder in the supplied directory; a person or a unit
 * mailbox maps to its own value. An unknown role contributes nobody rather than
 * guessing an address.
 */
export function resolveRecipientValues(
  draft: ComposeDraft,
  directory: readonly DirectoryEntry[],
): string[] {
  const holders = new Map(directory.map((entry) => [entry.value, entry.holderValues]));
  const values: string[] = [];
  for (const recipient of [...draft.to, ...draft.cc]) {
    if (recipient.kind === 'role') {
      const expanded = holders.get(recipient.value);
      if (expanded !== undefined) {
        values.push(...expanded);
      }
    } else {
      values.push(recipient.value);
    }
  }
  return values;
}

/**
 * Fail-closed recipient rule. A recipient from another tenant is always
 * refused; another facility is addressable only when this tenant approved
 * crossing. A blank identifier compares unequal and is therefore refused.
 */
export function isSelectableRecipient(
  entry: DirectoryEntry,
  actor: MailActor,
  crossFacilityEnabled: boolean,
): boolean {
  if (entry.organizationId !== actor.organizationId) {
    return false;
  }
  if (entry.facilityId === actor.facilityId) {
    return true;
  }
  return crossFacilityEnabled;
}

