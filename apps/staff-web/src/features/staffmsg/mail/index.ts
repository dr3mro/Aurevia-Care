/** Public surface of the staff mail slice, consumed by the owning shell route. */

export { MailPage } from './MailPage';
export type { MailPageProps } from './MailPage';
export { MailScreen } from './MailScreen';
export type { MailConfirmation, MailScreenProps } from './MailScreen';
export {
  confirmedRecipientValues,
  folderCount,
  folderWordKey,
  hasMailContent,
  isSelectableRecipient,
  recipientKindWordKey,
  replyAllValues,
  replyValues,
  resolveRecipientValues,
} from './domain';
export { mailKeys, mailMessages, mailText } from './labels';
export type { MailMessageKey } from './labels';
export {
  createDraft,
  forwardMail,
  listFolder,
  loadMailOverview,
  narrowDraft,
  narrowMessage,
  replyAllMail,
  replyMail,
  saveDraft,
  sendDraft,
} from './api';
export { MAIL_FOLDERS, RECIPIENT_KINDS } from './types';
export type {
  ComposeCommand,
  ComposeDraft,
  ComposeMode,
  DirectoryEntry,
  FolderCounts,
  MailActor,
  MailAttachmentView,
  MailFolder,
  MailMessageView,
  MailOverviewView,
  RecipientKind,
  RecipientView,
  WireMailComposeRequest,
  WireMailDraft,
  WireMailRecipient,
  WireMailView,
} from './types';
