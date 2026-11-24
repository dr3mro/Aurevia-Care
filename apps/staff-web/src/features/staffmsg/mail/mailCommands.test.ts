import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RefObject } from 'react';
import { TransportError } from '../../../api/transport';
import { createDraft, narrowDraft, narrowMessage, saveDraft, sendDraft } from './api';
import type * as ApiModule from './api';
import {
  addRecipient,
  attach,
  autosave,
  confirmSend,
  removeRecipient,
  startCompose,
  submitSend,
  toTransportError,
} from './mailCommands';
import type { AutosaveState, MailCommandContext } from './mailCommands';
import {
  alphaDirectory,
  contentDraft,
  emptyDraft,
  validWireDraft,
  validWireListing,
} from './fixtures';
import { defined } from '../../../components/invariant';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, createDraft: vi.fn(), saveDraft: vi.fn(), sendDraft: vi.fn() };
});

const createMock = vi.mocked(createDraft);
const saveMock = vi.mocked(saveDraft);
const sendMock = vi.mocked(sendDraft);

/** Narrowed fixtures so mocked return values match the local command types. */
const savedDraft = narrowDraft(validWireDraft);
const sentMessage = narrowMessage(defined(validWireListing[0], 'first wire view'), 'sent');

/** A deferred promise so a command can settle after the tenant changes. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

/** A minimal command context whose setters and retry updates are observable. */
function context() {
  const active: RefObject<string> = { current: 'org-alpha' };
  const autosaveState: RefObject<AutosaveState> = {
    current: { inFlight: false, sequence: 0, latest: undefined },
  };
  const retryCalls: unknown[] = [];
  const ctx: MailCommandContext = {
    autosaveState,
    organizationId: 'org-alpha',
    locale: 'en',
    directory: alphaDirectory,
    selfValue: 'synthetic-staff-self',
    activeOrganization: active,
    setDraft: vi.fn(),
    setCommandError: vi.fn(),
    setRetry: (value) => {
      retryCalls.push(value);
    },
    setNotice: vi.fn(),
    setConfirmation: vi.fn(),
    setSendKey: vi.fn(),
    finishSend: vi.fn(),
  };
  return { ctx, active, retryCalls, autosaveState };
}

/** Apply the React-style state updater recorded by setRetry. */
function retryFrom(calls: readonly unknown[]): (() => void) | undefined {
  const first = calls[0];
  if (typeof first !== 'function') {
    return undefined;
  }
  return (first as (previous: unknown) => () => void)(undefined);
}

/** A typed server failure for late-settlement cases. */
function serverFailure(): TransportError {
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

beforeEach(() => {
  createMock.mockReset();
  saveMock.mockReset();
  sendMock.mockReset();
});

describe('mail autosave serialization', () => {
  it('does not requeue a queued edit after the tenant changes mid-save', async () => {
    const pending = deferred<typeof savedDraft>();
    createMock.mockReturnValueOnce(pending.promise);
    saveMock.mockResolvedValue(savedDraft);
    const { ctx, active } = context();
    const first = { ...contentDraft, draftId: '', subject: 'first' };
    const running = autosave(ctx, first);
    await autosave(ctx, { ...first, subject: 'queued for old tenant' });
    active.current = 'org-beta';
    pending.resolve(savedDraft);
    await running;
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(createMock).toHaveBeenCalledTimes(1);
    expect(saveMock).not.toHaveBeenCalled();
  });

  it('creates once for overlapping edits and then saves the newest draft version', async () => {
    createMock.mockResolvedValue(savedDraft);
    saveMock.mockResolvedValue({ ...savedDraft, version: 3 });
    const { ctx, autosaveState } = context();
    const first = { ...contentDraft, draftId: '', subject: 'first' };
    const second = { ...first, subject: 'second' };
    const running = autosave(ctx, first);
    await autosave(ctx, second);
    await running;
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(createMock).toHaveBeenCalledTimes(1);
    expect(saveMock).toHaveBeenCalledTimes(1);
    expect(ctx.setDraft).toHaveBeenCalledWith(
      expect.objectContaining({ subject: 'second', draftId: savedDraft.draftId }),
    );
    expect(autosaveState.current.inFlight).toBe(false);
  });
});

describe('mail command error normalization', () => {
  it('keeps a typed transport failure and wraps an unexpected one', () => {
    const typed = serverFailure();
    expect(toTransportError(typed)).toBe(typed);
    expect(toTransportError(new Error('unexpected')).kind).toBe('server');
  });
});

describe('mail command late settlement', () => {
  it('ignores an autosave that succeeds after the tenant changes', async () => {
    const pending = deferred<typeof savedDraft>();
    createMock.mockReturnValueOnce(pending.promise);
    const { ctx, active } = context();
    const running = autosave(ctx, { ...contentDraft, draftId: '' });
    active.current = 'org-beta';
    pending.resolve(savedDraft);
    await running;
    expect(ctx.setDraft).not.toHaveBeenCalled();
    expect(ctx.setCommandError).not.toHaveBeenCalled();
  });

  it('ignores an autosave that fails after the tenant changes', async () => {
    const pending = deferred<typeof savedDraft>();
    createMock.mockReturnValueOnce(pending.promise);
    const { ctx, active } = context();
    const running = autosave(ctx, { ...contentDraft, draftId: '' });
    active.current = 'org-beta';
    pending.reject(serverFailure());
    await running;
    expect(ctx.setCommandError).not.toHaveBeenCalled();
  });

  it('finishes a send that succeeds in the current tenant', async () => {
    sendMock.mockResolvedValueOnce(sentMessage);
    const { ctx } = context();
    await submitSend(ctx, contentDraft, 'key-1');
    expect(ctx.finishSend).toHaveBeenCalledWith(2);
  });

  it('ignores a send that succeeds after the tenant changes', async () => {
    const pending = deferred<typeof sentMessage>();
    sendMock.mockReturnValueOnce(pending.promise);
    const { ctx, active } = context();
    const running = submitSend(ctx, contentDraft, 'key-1');
    active.current = 'org-beta';
    pending.resolve(sentMessage);
    await running;
    expect(ctx.finishSend).not.toHaveBeenCalled();
  });

  it('ignores a send that fails after the tenant changes', async () => {
    const pending = deferred<typeof sentMessage>();
    sendMock.mockReturnValueOnce(pending.promise);
    const { ctx, active } = context();
    const running = submitSend(ctx, contentDraft, 'key-1');
    active.current = 'org-beta';
    pending.reject(serverFailure());
    await running;
    expect(ctx.setCommandError).not.toHaveBeenCalled();
  });

  it('stores and runs a retry closure for a send that fails in the current tenant', async () => {
    sendMock
      .mockRejectedValueOnce(new Error('plain failure'))
      .mockResolvedValueOnce(sentMessage);
    const { ctx, retryCalls } = context();
    await submitSend(ctx, contentDraft, 'key-1');
    expect(ctx.setCommandError).toHaveBeenCalledTimes(1);
    expect(retryCalls).toHaveLength(1);
    retryFrom(retryCalls)?.();
    await Promise.resolve();
    await Promise.resolve();
    expect(sendMock).toHaveBeenCalledTimes(2);
    expect(ctx.finishSend).toHaveBeenCalledTimes(1);
  });

  it('does nothing when a confirmation is requested without a payload', async () => {
    const { ctx } = context();
    await confirmSend(ctx, contentDraft, undefined, 'key-1');
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('ignores a confirmation that succeeds after the tenant changes', async () => {
    const pending = deferred<typeof sentMessage>();
    sendMock.mockReturnValueOnce(pending.promise);
    const { ctx, active } = context();
    const running = confirmSend(ctx, contentDraft, { recipients: ['synthetic-staff-nora'], files: [] }, 'key-1');
    active.current = 'org-beta';
    pending.resolve(sentMessage);
    await running;
    expect(ctx.finishSend).not.toHaveBeenCalled();
  });

  it('ignores a confirmation that fails after the tenant changes', async () => {
    const pending = deferred<typeof sentMessage>();
    sendMock.mockReturnValueOnce(pending.promise);
    const { ctx, active } = context();
    const running = confirmSend(ctx, contentDraft, { recipients: ['synthetic-staff-nora'], files: [] }, 'key-1');
    active.current = 'org-beta';
    pending.reject(serverFailure());
    await running;
    expect(ctx.setCommandError).not.toHaveBeenCalled();
  });

  it('closes the confirmation and runs a retry when confirming fails', async () => {
    sendMock
      .mockRejectedValueOnce(serverFailure())
      .mockResolvedValueOnce(sentMessage);
    const { ctx, retryCalls } = context();
    await confirmSend(ctx, contentDraft, { recipients: ['synthetic-staff-nora'], files: [] }, 'key-1');
    expect(ctx.setConfirmation).toHaveBeenCalledWith(undefined);
    expect(ctx.setCommandError).toHaveBeenCalledTimes(1);
    retryFrom(retryCalls)?.();
    await Promise.resolve();
    await Promise.resolve();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });
});

describe('mail command recipient edits', () => {
  it('primes a message-less compose without creating a draft', () => {
    const { ctx } = context();
    startCompose(ctx, 'forward', undefined, emptyDraft);
    expect(ctx.setDraft).toHaveBeenCalledWith(emptyDraft);
  });

  it('adds a scoped entry to either recipient field', () => {
    createMock.mockResolvedValue(savedDraft);
    const { ctx } = context();
    addRecipient(ctx, emptyDraft, 'to', defined(alphaDirectory[0], 'person entry'));
    addRecipient(ctx, emptyDraft, 'cc', defined(alphaDirectory[1], 'role entry'));
    expect(ctx.setDraft).toHaveBeenNthCalledWith(1, expect.objectContaining({
      to: [expect.objectContaining({ value: 'synthetic-person-nora' })],
    }));
    expect(ctx.setDraft).toHaveBeenNthCalledWith(2, expect.objectContaining({
      cc: [expect.objectContaining({ value: 'synthetic-role-triage' })],
    }));
  });

  it('stages an attachment and removes recipients from both fields', () => {
    createMock.mockResolvedValue(savedDraft);
    const { ctx } = context();
    const primed = {
      ...emptyDraft,
      to: [{ kind: 'person' as const, value: 'synthetic-person-nora', label: 'Nora' }],
      cc: [{ kind: 'role' as const, value: 'synthetic-role-triage', label: 'Triage' }],
    };
    attach(ctx, emptyDraft, { documentId: 'synthetic-document-001', fileName: 'visit-summary.pdf' });
    removeRecipient(ctx, primed, 'to', 'synthetic-person-nora');
    removeRecipient(ctx, primed, 'cc', 'synthetic-role-triage');
    expect(ctx.setDraft).toHaveBeenCalledWith(expect.objectContaining({
      attachments: [{ documentId: 'synthetic-document-001', fileName: 'visit-summary.pdf' }],
    }));
    expect(ctx.setDraft).toHaveBeenCalledWith(expect.objectContaining({ to: [] }));
    expect(ctx.setDraft).toHaveBeenCalledWith(expect.objectContaining({ cc: [] }));
  });
});
