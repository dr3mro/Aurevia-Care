import { describe, expect, it } from 'vitest';
import {
  alphaActor,
  alphaDirectory,
  alphaMessage,
  alphaOverview,
  contentDraft,
  emptyDraft,
  personRecipient,
  roleRecipient,
  selfSubject,
  unitMailboxRecipient,
} from './fixtures';
import {
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
import type { RecipientView } from './types';

describe('mail content rule', () => {
  it('treats a blank draft and whitespace-only text as empty', () => {
    expect(hasMailContent(emptyDraft)).toBe(false);
    expect(
      hasMailContent({
        ...emptyDraft,
        subject: '   ',
        body: '\n\t ',
        to: [],
        cc: [],
        attachments: [],
      }),
    ).toBe(false);
  });

  it.each([
    ['subject', { subject: 'Synthetic subject' }],
    ['body', { body: 'Synthetic body' }],
    ['to', { to: [personRecipient] }],
    ['cc', { cc: [roleRecipient] }],
    ['attachment', { attachments: [{ documentId: 'synthetic-id', fileName: 'a.pdf' }] }],
  ] as const)('treats a draft with %s as content', (_label, patch) => {
    expect(hasMailContent({ ...emptyDraft, ...patch })).toBe(true);
  });
});

describe('mail label keys', () => {
  it('resolves every folder to its own label key', () => {
    expect(folderWordKey('inbox')).toBe('folderInbox');
    expect(folderWordKey('sent')).toBe('folderSent');
    expect(folderWordKey('drafts')).toBe('folderDrafts');
    expect(folderWordKey('archive')).toBe('folderArchive');
    expect(folderWordKey('trash')).toBe('folderTrash');
  });

  it('resolves every recipient kind to its own label key', () => {
    expect(recipientKindWordKey('person')).toBe('kindPerson');
    expect(recipientKindWordKey('role')).toBe('kindRole');
    expect(recipientKindWordKey('unit_mailbox')).toBe('kindUnitMailbox');
  });

  it('reads a count for every folder, including empty ones', () => {
    expect(folderCount(alphaOverview.counts, 'inbox')).toBe(1);
    expect(folderCount(alphaOverview.counts, 'drafts')).toBe(1);
    expect(folderCount(alphaOverview.counts, 'sent')).toBe(0);
    expect(folderCount(alphaOverview.counts, 'archive')).toBe(0);
    expect(folderCount(alphaOverview.counts, 'trash')).toBe(0);
  });
});

describe('mail reply rules', () => {
  it('replies only to the sender', () => {
    expect(replyValues(alphaMessage)).toEqual(['synthetic-patient-001']);
  });

  it('reply-all includes to and cc, drops the author and deduplicates', () => {
    expect(replyAllValues(alphaMessage, selfSubject)).toEqual([
      'synthetic-patient-001',
      personRecipient.value,
      roleRecipient.value,
      unitMailboxRecipient.value,
    ]);
  });

  it('reply-all keeps a recipient equal to the author out of every part', () => {
    const onlySelf: RecipientView = { kind: 'person', value: selfSubject, label: 'Synthetic Self' };
    expect(
      replyAllValues(
        { ...alphaMessage, from: onlySelf, to: [onlySelf], cc: [onlySelf] },
        selfSubject,
      ),
    ).toEqual([]);
  });
});

describe('mail confirmation rules', () => {
  it('lists the visible to and cc values a user confirmed', () => {
    expect(confirmedRecipientValues(contentDraft)).toEqual([
      personRecipient.value,
      roleRecipient.value,
    ]);
    expect(confirmedRecipientValues(emptyDraft)).toEqual([]);
  });

  it('expands a role to its holders while passing person and unit mailbox through', () => {
    expect(resolveRecipientValues(contentDraft, alphaDirectory)).toEqual([
      personRecipient.value,
      'synthetic-staff-nora',
      'synthetic-staff-omar',
    ]);
  });

  it('omits a role whose directory entry has been removed', () => {
    expect(resolveRecipientValues(contentDraft, [])).toEqual([personRecipient.value]);
  });
});

describe('mail recipient scoping', () => {
  it('excludes a recipient in another tenant', () => {
    expect(
      isSelectableRecipient(
        {
          value: 'synthetic-person-beta',
          label: 'Beta',
          kind: 'person',
          organizationId: 'org-synthetic-beta',
          facilityId: alphaActor.facilityId,
          holderValues: [],
        },
        alphaActor,
        true,
      ),
    ).toBe(false);
  });

  it('allows a recipient in the same facility', () => {
    expect(
      isSelectableRecipient(
        {
          value: personRecipient.value,
          label: personRecipient.label,
          kind: 'person',
          organizationId: alphaActor.organizationId,
          facilityId: alphaActor.facilityId,
          holderValues: [],
        },
        alphaActor,
        false,
      ),
    ).toBe(true);
  });

  it('allows another facility only when the tenant approved crossing', () => {
    const northRole = {
      value: 'synthetic-role-north-triage',
      label: 'North triage',
      kind: 'role' as const,
      organizationId: alphaActor.organizationId,
      facilityId: 'facility-synthetic-north',
      holderValues: ['synthetic-staff-layla'],
    };
    expect(isSelectableRecipient(northRole, alphaActor, true)).toBe(true);
    expect(isSelectableRecipient(northRole, alphaActor, false)).toBe(false);
  });
});

