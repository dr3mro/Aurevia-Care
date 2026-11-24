import type { Dispatch, RefObject, SetStateAction } from 'react';
import type { Locale } from '../../../i18n/messages';
import { TransportError } from '../../../api/transport';
import { defined as requirePresent } from '../../../components/invariant';
import { createDraft, saveDraft, sendDraft } from './api';
import {
  confirmedRecipientValues,
  hasMailContent,
  replyAllValues,
  replyValues,
  resolveRecipientValues,
} from './domain';
import { mailText } from './labels';
import type { MailConfirmation } from './MailScreen';
import type {
  ComposeDraft,
  ComposeMode,
  DirectoryEntry,
  MailAttachmentView,
  MailMessageView,
  RecipientView,
} from './types';

/**
 * Command handlers for the staff mail page (MSG-02, MSG-03, WEB-09).
 *
 * Keeping the commands outside the component makes the page a thin owner of
 * state, and makes each failure path independently testable through the page.
 * Every handler captures the tenant it started under and ignores a result that
 * settles after the author has switched organization.
 */

/** Serialization state for draft autosaves: one request at a time, newest wins. */
export interface AutosaveState {
  /** True while a create or save request is in flight. */
  inFlight: boolean;
  /** Monotonic token identifying the most recently requested autosave. */
  sequence: number;
  /** Newest draft received while a save was in flight, if any. */
  latest: ComposeDraft | undefined;
}

/** State setters and scope a mail command needs to do its work. */
export interface MailCommandContext {
  readonly autosaveState: RefObject<AutosaveState>;
  readonly organizationId: string;
  readonly locale: Locale;
  readonly directory: readonly DirectoryEntry[];
  readonly selfValue: string;
  readonly activeOrganization: RefObject<string>;
  readonly setDraft: Dispatch<SetStateAction<ComposeDraft>>;
  readonly setCommandError: Dispatch<SetStateAction<TransportError | undefined>>;
  readonly setRetry: Dispatch<SetStateAction<(() => void) | undefined>>;
  readonly setNotice: Dispatch<SetStateAction<string | undefined>>;
  readonly setConfirmation: Dispatch<SetStateAction<MailConfirmation | undefined>>;
  readonly setSendKey: Dispatch<SetStateAction<string>>;
  readonly finishSend: (count: number) => void;
}

/** Normalise an unexpected command rejection without copying response text. */
export function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Build the published compose body from the current draft state. */
function composeCommand(draft: ComposeDraft) {
  return {
    facilityId: draft.facilityId,
    subject: draft.subject,
    body: draft.body,
    to: draft.to,
    cc: draft.cc,
    attachmentDocumentIds: draft.attachments.map((attachment) => attachment.documentId),
  };
}

/** Turn confirm values into visible recipient chips for a primed reply. */
function valueRecipients(values: readonly string[]): RecipientView[] {
  return values.map((value) => ({ kind: 'person', value, label: value }));
}

/**
 * Create the private draft on first content, then save each new version.
 *
 * Saves are serialized: a second edit arriving while one request is in flight
 * is remembered but not sent twice, so two creates with different idempotency
 * keys can never make duplicate drafts. Only a response that is still the
 * newest may replace the editor state; a superseded response instead adopts
 * the saved identity into the newer typed work before it is sent.
 */
export async function autosave(ctx: MailCommandContext, next: ComposeDraft): Promise<void> {
  const state = ctx.autosaveState.current;
  state.latest = next;
  const requested = ++state.sequence;
  if (state.inFlight) {
    return;
  }
  state.inFlight = true;
  state.latest = undefined;
  const sending = next;
  const submittingOrganization = ctx.organizationId;
  try {
    const saved =
      sending.draftId === ''
        ? await createDraft(ctx.organizationId, composeCommand(sending))
        : await saveDraft(ctx.organizationId, sending);
    if (ctx.activeOrganization.current !== submittingOrganization) {
      return;
    }
    if (requested === state.sequence) {
      ctx.setDraft(saved);
      ctx.setNotice(
        mailText(ctx.locale, 'autosaveStatus').replace('{version}', String(saved.version)),
      );
      ctx.setCommandError(undefined);
      ctx.setRetry(undefined);
    } else {
      const newer: ComposeDraft = requirePresent<ComposeDraft>(state.latest, 'queued draft');
      const patched = { ...newer, draftId: saved.draftId, version: saved.version };
      ctx.setDraft(patched);
      // The queued autosave must carry the identity just saved, or it would
      // create a second draft rather than advance the version.
      state.latest = patched;
    }
  } catch (failure: unknown) {
    if (ctx.activeOrganization.current !== submittingOrganization) {
      return;
    }
    ctx.setRetry(() => () => {
      void autosave(ctx, next);
    });
    ctx.setCommandError(toTransportError(failure));
  } finally {
    state.inFlight = false;
  }
  const queued = state.latest;
  if (queued !== undefined && ctx.activeOrganization.current === submittingOrganization) {
    state.latest = undefined;
    void autosave(ctx, queued);
  }
}

/** Store an edit and autosave it only when the draft holds content. */
export function updateDraft(ctx: MailCommandContext, next: ComposeDraft): void {
  ctx.setDraft(next);
  if (hasMailContent(next)) {
    void autosave(ctx, next);
  }
}

/** Prime a compose draft for a new mail, a reply, a reply-all or a forward. */
export function startCompose(
  ctx: MailCommandContext,
  mode: ComposeMode,
  message: MailMessageView | undefined,
  emptyDraft: ComposeDraft,
): void {
  let next: ComposeDraft = emptyDraft;
  if (mode === 'reply' && message !== undefined) {
    next = { ...emptyDraft, to: valueRecipients(replyValues(message)) };
  } else if (mode === 'reply-all' && message !== undefined) {
    next = { ...emptyDraft, to: valueRecipients(replyAllValues(message, ctx.selfValue)) };
  } else if (mode === 'forward' && message !== undefined) {
    next = {
      ...emptyDraft,
      subject: `Fwd: ${message.subject}`,
      body: message.body,
      attachments: message.attachments,
    };
  }
  ctx.setDraft(next);
  ctx.setConfirmation(undefined);
  ctx.setSendKey(crypto.randomUUID());
  updateDraft(ctx, next);
}

/** Add a scoped directory entry to one recipient field. */
export function addRecipient(
  ctx: MailCommandContext,
  draft: ComposeDraft,
  field: 'to' | 'cc',
  entry: DirectoryEntry,
): void {
  const recipient: RecipientView = { kind: entry.kind, value: entry.value, label: entry.label };
  updateDraft(
    ctx,
    field === 'to'
      ? { ...draft, to: [...draft.to, recipient] }
      : { ...draft, cc: [...draft.cc, recipient] },
  );
}

/** Remove one recipient by its value from one field. */
export function removeRecipient(
  ctx: MailCommandContext,
  draft: ComposeDraft,
  field: 'to' | 'cc',
  value: string,
): void {
  updateDraft(
    ctx,
    field === 'to'
      ? { ...draft, to: draft.to.filter((recipient) => recipient.value !== value) }
      : { ...draft, cc: draft.cc.filter((recipient) => recipient.value !== value) },
  );
}

/** Stage one file name for the compose and its confirmation. */
export function attach(
  ctx: MailCommandContext,
  draft: ComposeDraft,
  attachment: MailAttachmentView,
): void {
  updateDraft(ctx, { ...draft, attachments: [...draft.attachments, attachment] });
}

/** First send attempts publication with no confirmed list so 428 can request one. */
export async function submitSend(
  ctx: MailCommandContext,
  draft: ComposeDraft,
  sendKey: string,
): Promise<void> {
  if (draft.draftId === '') {
    ctx.setNotice(mailText(ctx.locale, 'validationNoDraft'));
    return;
  }
  const sending = draft;
  const submittingOrganization = ctx.organizationId;
  try {
    await sendDraft(ctx.organizationId, sending.draftId, undefined, sendKey);
    if (ctx.activeOrganization.current !== submittingOrganization) {
      return;
    }
    ctx.finishSend(confirmedRecipientValues(sending).length);
  } catch (failure: unknown) {
    if (ctx.activeOrganization.current !== submittingOrganization) {
      return;
    }
    const failureTyped = toTransportError(failure);
    if (failureTyped.kind === 'precondition-required') {
      ctx.setConfirmation({
        recipients: resolveRecipientValues(sending, ctx.directory),
        files: sending.attachments.map((item) => item.fileName),
      });
      ctx.setCommandError(undefined);
      ctx.setRetry(undefined);
      return;
    }
    ctx.setRetry(() => () => {
      void submitSend(ctx, sending, sendKey);
    });
    ctx.setCommandError(failureTyped);
  }
}

/** Re-send with the exact recipients the author confirmed, reusing one key. */
export async function confirmSend(
  ctx: MailCommandContext,
  draft: ComposeDraft,
  confirmation: MailConfirmation | undefined,
  sendKey: string,
): Promise<void> {
  if (confirmation === undefined) {
    return;
  }
  const sending = draft;
  const submittingOrganization = ctx.organizationId;
  try {
    await sendDraft(ctx.organizationId, sending.draftId, confirmation.recipients, sendKey);
    if (ctx.activeOrganization.current !== submittingOrganization) {
      return;
    }
    ctx.finishSend(confirmedRecipientValues(sending).length);
  } catch (failure: unknown) {
    if (ctx.activeOrganization.current !== submittingOrganization) {
      return;
    }
    ctx.setConfirmation(undefined);
    ctx.setRetry(() => () => {
      void confirmSend(ctx, sending, confirmation, sendKey);
    });
    ctx.setCommandError(toTransportError(failure));
  }
}
