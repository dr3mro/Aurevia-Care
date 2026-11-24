/**
 * View and command types for the staff mail slice (MSG-02, MSG-03, WEB-09).
 *
 * Wire shapes come from the generated OpenAPI client, whose fields are all
 * optional. State and commands use the required-field local types below; the
 * API module narrows a wire response once at the boundary so an absent id,
 * version or recipient can never reach a command. Every fixture is synthetic
 * and every identifier is tenant-local.
 */

import type { components } from '../../../api/generated/openapi';

/** Published mail folder vocabulary, in navigation order. */
export const MAIL_FOLDERS = ['inbox', 'sent', 'drafts', 'archive', 'trash'] as const;

/** One published mail folder. */
export type MailFolder = (typeof MAIL_FOLDERS)[number];

/** Published recipient kinds; blind copy is deliberately absent from the wire. */
export const RECIPIENT_KINDS = ['person', 'role', 'unit_mailbox'] as const;

/** One published recipient kind. */
export type RecipientKind = (typeof RECIPIENT_KINDS)[number];

/** A recipient as published on the wire. */
export type WireMailRecipient = components['schemas']['MailRecipient'];

/** A versioned draft as published on the wire. */
export type WireMailDraft = components['schemas']['MailDraft'];

/** One message in a folder listing as published on the wire. */
export type WireMailView = components['schemas']['MailView'];

/** A compose or save body as published on the wire. */
export type WireMailComposeRequest = components['schemas']['MailComposeRequest'];

/** One recipient as the mail view shows it: an address kind, value and label. */
export interface RecipientView {
  readonly kind: RecipientKind;
  readonly value: string;
  readonly label: string;
}

/** A staged attachment, identified by its tenant-local document id. */
export interface MailAttachmentView {
  readonly documentId: string;
  readonly fileName: string;
}

/** One immutable published message shown in the reading pane. */
export interface MailMessageView {
  readonly id: string;
  readonly subject: string;
  readonly body: string;
  readonly from: RecipientView;
  readonly to: readonly RecipientView[];
  readonly cc: readonly RecipientView[];
  readonly attachments: readonly MailAttachmentView[];
  readonly sentAt: string;
  readonly timeZone: string;
  readonly folder: MailFolder;
}

/** A message count for every folder, including folders with zero messages. */
export type FolderCounts = Record<MailFolder, number>;

/** The tenant's folder counts and messages, read from the published folder routes. */
export interface MailOverviewView {
  readonly facilityId: string;
  readonly counts: FolderCounts;
  readonly messages: readonly MailMessageView[];
}

/** A tenant-scoped addressable entry in the compose recipient directory. */
export interface DirectoryEntry {
  readonly value: string;
  readonly label: string;
  readonly kind: RecipientKind;
  readonly organizationId: string;
  readonly facilityId: string;
  /** Holder values a role expands to at send time; empty for other kinds. */
  readonly holderValues: readonly string[];
}

/** The active author scope used by the fail-closed recipient rules. */
export interface MailActor {
  readonly organizationId: string;
  readonly facilityId: string;
}

/** The versioned compose draft held by the page, including its save precondition. */
export interface ComposeDraft {
  /** Empty until the first autosave creates the private draft. */
  readonly draftId: string;
  /** Zero until the draft exists; then the version last read from the server. */
  readonly version: number;
  readonly facilityId: string;
  readonly subject: string;
  readonly body: string;
  readonly to: readonly RecipientView[];
  readonly cc: readonly RecipientView[];
  readonly attachments: readonly MailAttachmentView[];
}

/** The body sent when creating or saving a private draft. */
export interface ComposeCommand {
  readonly facilityId: string;
  readonly subject: string;
  readonly body: string;
  readonly to: readonly RecipientView[];
  readonly cc: readonly RecipientView[];
  readonly attachmentDocumentIds: readonly string[];
}

/** How a compose draft was primed from a message in the reading pane. */
export type ComposeMode = 'new' | 'reply' | 'reply-all' | 'forward';
