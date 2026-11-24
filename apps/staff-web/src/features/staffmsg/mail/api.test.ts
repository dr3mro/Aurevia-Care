import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defined } from '../../../components/invariant';
import type { RequestDescriptor } from '../../../api/client';
import { TransportError, send } from '../../../api/transport';
import type * as TransportModule from '../../../api/transport';
import {
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
import {
  alphaOrganizationId,
  contentDraft,
  homeFacilityId,
  invalidWireDraft,
  personRecipient,
  roleRecipient,
  stagedAttachment,
  validWireDraft,
  validWireListing,
} from './fixtures';
import { emptyDraft } from './fixtures';

vi.mock('../../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Read one descriptor by call index in the single-call unit tests. */
function descriptorAt(call: number): RequestDescriptor {
  return defined(sendMock.mock.calls[call], 'send call')[0];
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('mail folder reads', () => {
  it('reads one named folder with the tenant header and narrows its messages', async () => {
    sendMock.mockResolvedValueOnce([defined(validWireListing[0], 'first wire view')]);
    const messages = await listFolder(alphaOrganizationId, 'inbox');
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/staff/mail/folders/inbox');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(messages).toHaveLength(1);
    expect(messages[0]).toMatchObject({
      id: 'synthetic-message-001',
      subject: 'Synthetic appointment change',
      from: { kind: 'person', value: 'synthetic-patient-001' },
      folder: 'inbox',
      timeZone: 'UTC',
    });
    expect(messages[0]?.attachments[0]?.fileName).toBe(stagedAttachment.documentId);
  });

  it('derives a count for all five folders, including the empty ones', async () => {
    sendMock.mockImplementation((descriptor) => {
      if (descriptor.url.endsWith('/folders/inbox')) {
        return Promise.resolve([defined(validWireListing[0], 'first wire view')]);
      }
      if (descriptor.url.endsWith('/folders/sent')) {
        return Promise.resolve([defined(validWireListing[1], 'second wire view')]);
      }
      return Promise.resolve([]);
    });
    const overview = await loadMailOverview(alphaOrganizationId, homeFacilityId);
    expect(sendMock).toHaveBeenCalledTimes(5);
    expect(overview.counts).toEqual({ inbox: 1, sent: 1, drafts: 0, archive: 0, trash: 0 });
    expect(overview.messages).toHaveLength(2);
    expect(overview.facilityId).toBe(homeFacilityId);
  });

  it('narrows a sparse wire message with safe empty defaults', () => {
    const sparse = {
      message: { id: 'sparse-id', senderSubject: 'synthetic-sender', sentAt: '2026-03-09T16:40:00Z' },
      viewerFolder: 'inbox' as const,
    };
    expect(narrowMessage(sparse, 'inbox')).toMatchObject({
      subject: '',
      body: '',
      to: [],
      cc: [],
      attachments: [],
      folder: 'inbox',
    });
  });

  it('fails closed when a wire message has no identifier', () => {
    const malformed = {
      message: { senderSubject: 'synthetic-sender', sentAt: '2026-03-09T16:40:00Z' },
      viewerFolder: 'inbox' as const,
    };
    expect(() => narrowMessage(malformed, 'inbox')).toThrow(TransportError);
  });
});

describe('mail boundary narrowing', () => {
  it('narrows a complete wire draft into a versioned local draft', () => {
    expect(narrowDraft(validWireDraft)).toMatchObject({
      draftId: 'synthetic-draft-002',
      version: 2,
      subject: 'Wire subject',
      to: [{ kind: 'person', value: personRecipient.value }],
    });
  });

  it('rejects a wire draft missing its id and version before any request', () => {
    expect(() => narrowDraft(invalidWireDraft)).toThrow(TransportError);
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('rejects a wire recipient with no kind', () => {
    expect(() => narrowDraft({ ...validWireDraft, to: [{ value: 'synthetic-recipient' }] })).toThrow(
      TransportError,
    );
  });
});

describe('mail draft commands', () => {
  it('creates a draft with a fresh idempotency key and the compose body', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    const draft = await createDraft(alphaOrganizationId, {
      facilityId: homeFacilityId,
      subject: contentDraft.subject,
      body: contentDraft.body,
      to: contentDraft.to,
      cc: contentDraft.cc,
      attachmentDocumentIds: [stagedAttachment.documentId],
    });
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/staff/mail/drafts');
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
    expect(descriptor.body).toMatchObject({
      facilityId: homeFacilityId,
      to: [{ kind: 'person', value: personRecipient.value }],
      cc: [{ kind: 'role', value: roleRecipient.value }],
      attachmentDocumentIds: [stagedAttachment.documentId],
    });
    expect(draft.version).toBe(2);
  });

  it('saves a versioned draft as PUT with If-Match equal to the current version', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    await saveDraft(alphaOrganizationId, contentDraft);
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('PUT');
    expect(descriptor.url).toBe('/api/v1/staff/mail/drafts/synthetic-draft-001');
    expect(descriptor.headers['If-Match']).toBe('3');
  });

  it('omits If-Match when the draft has no version yet', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    await saveDraft(alphaOrganizationId, emptyDraft);
    expect(descriptorAt(0).headers['If-Match']).toBeUndefined();
  });

  it('encodes a reserved-character draft id in the path', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    await saveDraft(alphaOrganizationId, { ...contentDraft, draftId: 'draft/with space' });
    expect(descriptorAt(0).url).toBe('/api/v1/staff/mail/drafts/draft%2Fwith%20space');
  });
});

describe('mail send and reply commands', () => {
  it('omits confirmedRecipients on the first send so a 428 can request confirmation', async () => {
    sendMock.mockResolvedValueOnce(defined(validWireListing[0], 'first wire view'));
    const descriptorBefore = sendMock.mock.calls.length;
    await sendDraft(alphaOrganizationId, 'draft-1', undefined, 'key-1');
    expect(descriptorAt(descriptorBefore).body?.confirmedRecipients).toBeUndefined();
    expect(descriptorAt(descriptorBefore).headers['Idempotency-Key']).toBe('key-1');
  });

  it('sends the confirmed recipient list and reuses the caller idempotency key', async () => {
    sendMock.mockResolvedValueOnce(defined(validWireListing[0], 'first wire view'));
    await sendDraft(alphaOrganizationId, 'draft/1', ['synthetic-staff-nora'], 'key-1');
    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/staff/mail/drafts/draft%2F1/send');
    expect(descriptor.body?.confirmedRecipients).toEqual(['synthetic-staff-nora']);
    expect(descriptor.headers['Idempotency-Key']).toBe('key-1');
  });

  it('posts a reply body to the reply route', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    await replyMail(alphaOrganizationId, 'message-1', 'Reply text', 'key-reply');
    expect(descriptorAt(0).url).toBe('/api/v1/staff/mail/messages/message-1/reply');
    expect(descriptorAt(0).body).toEqual({ body: 'Reply text' });
    expect(descriptorAt(0).headers['Idempotency-Key']).toBe('key-reply');
  });

  it('posts a reply-all body to the reply-all route', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    await replyAllMail(alphaOrganizationId, 'message-1', 'Reply all text', 'key-all');
    expect(descriptorAt(0).url).toBe('/api/v1/staff/mail/messages/message-1/reply-all');
    expect(descriptorAt(0).body).toEqual({ body: 'Reply all text' });
  });

  it('forwards a message with a fresh compose body', async () => {
    sendMock.mockResolvedValueOnce(validWireDraft);
    await forwardMail(
      alphaOrganizationId,
      'message-1',
      {
        facilityId: homeFacilityId,
        subject: 'Fwd: Synthetic subject',
        body: contentDraft.body,
        to: contentDraft.to,
        cc: contentDraft.cc,
        attachmentDocumentIds: [stagedAttachment.documentId],
      },
      'key-forward',
    );
    expect(descriptorAt(0).url).toBe('/api/v1/staff/mail/messages/message-1/forward');
    expect(descriptorAt(0).body).toMatchObject({ subject: 'Fwd: Synthetic subject' });
  });
});
