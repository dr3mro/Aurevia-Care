/**
 * Clearly synthetic mail data for the staff mail view (MSG-02, MSG-03).
 *
 * Two tenants reuse the same local identifier shapes, one directory role expands
 * to holders, and one role sits in another facility while one person sits in
 * another organization. No real patient, staff identity or credential appears.
 */

import { defined } from '../../../components/invariant';
import type {
  ComposeDraft,
  DirectoryEntry,
  MailActor,
  MailAttachmentView,
  MailMessageView,
  MailOverviewView,
  RecipientView,
  WireMailDraft,
  WireMailView,
} from './types';

/** Synthetic alpha tenant, owner of the primary fixture set. */
export const alphaOrganizationId = 'org-synthetic-alpha';

/** Synthetic beta tenant; its local ids deliberately resemble alpha's. */
export const betaOrganizationId = 'org-synthetic-beta';

/** The author's home facility. */
export const homeFacilityId = 'facility-synthetic-main';

/** A second facility of the same synthetic organization. */
export const northFacilityId = 'facility-synthetic-north';

/** The acting subject, used to exclude the author from reply-all. */
export const selfSubject = 'synthetic-staff-self';

/** Synthetic time zone used by every message fixture. */
export const mailTimeZone = 'America/New_York';

/** A person recipient in the home facility. */
export const personRecipient: RecipientView = {
  kind: 'person',
  value: 'synthetic-person-nora',
  label: 'Synthetic Nora',
};

/** A role recipient that expands to two holders in the home facility. */
export const roleRecipient: RecipientView = {
  kind: 'role',
  value: 'synthetic-role-triage',
  label: 'Synthetic triage role',
};

/** A unit mailbox recipient in the home facility. */
export const unitMailboxRecipient: RecipientView = {
  kind: 'unit_mailbox',
  value: 'synthetic-unit-cardiology',
  label: 'Synthetic cardiology mailbox',
};

/** A staged synthetic attachment. */
export const stagedAttachment: MailAttachmentView = {
  documentId: 'synthetic-document-001',
  fileName: 'visit-summary.pdf',
};

/** Files staged for a synthetic compose session. */
export const stagedFiles: readonly MailAttachmentView[] = [stagedAttachment];

/** Synthetic directory available to the compose picker, already tenant-scoped. */
export const alphaDirectory: readonly DirectoryEntry[] = [
  {
    value: personRecipient.value,
    label: personRecipient.label,
    kind: 'person',
    organizationId: alphaOrganizationId,
    facilityId: homeFacilityId,
    holderValues: [],
  },
  {
    value: roleRecipient.value,
    label: roleRecipient.label,
    kind: 'role',
    organizationId: alphaOrganizationId,
    facilityId: homeFacilityId,
    holderValues: ['synthetic-staff-nora', 'synthetic-staff-omar'],
  },
  {
    value: unitMailboxRecipient.value,
    label: unitMailboxRecipient.label,
    kind: 'unit_mailbox',
    organizationId: alphaOrganizationId,
    facilityId: homeFacilityId,
    holderValues: [],
  },
  {
    value: 'synthetic-role-north-triage',
    label: 'Synthetic north triage role',
    kind: 'role',
    organizationId: alphaOrganizationId,
    facilityId: northFacilityId,
    holderValues: ['synthetic-staff-layla'],
  },
  {
    value: 'synthetic-person-beta',
    label: 'Synthetic beta partner',
    kind: 'person',
    organizationId: betaOrganizationId,
    facilityId: homeFacilityId,
    holderValues: [],
  },
];

/** The author scoped to the alpha tenant and home facility. */
export const alphaActor: MailActor = {
  organizationId: alphaOrganizationId,
  facilityId: homeFacilityId,
};

/** One synthetic published message with a duplicated cc and the author in cc. */
export const alphaMessage: MailMessageView = {
  id: 'synthetic-message-001',
  subject: 'Synthetic appointment change',
  body: 'Could we move Thursdays visit to the afternoon?',
  from: { kind: 'person', value: 'synthetic-patient-001', label: 'Synthetic Grace Okafor' },
  to: [personRecipient, roleRecipient],
  cc: [
    unitMailboxRecipient,
    personRecipient,
    { kind: 'person', value: selfSubject, label: 'Synthetic Self' },
  ],
  attachments: [stagedAttachment],
  sentAt: '2026-03-09T16:40:00Z',
  timeZone: mailTimeZone,
  folder: 'inbox',
};

/** A beta message whose local id shape matches alpha's. */
export const betaMessage: MailMessageView = {
  ...alphaMessage,
  id: 'synthetic-message-001',
  from: { kind: 'person', value: 'synthetic-patient-001', label: 'Synthetic Grace Okafor beta' },
  subject: 'Synthetic beta mail',
};

/** Alpha overview: one inbox message, one draft, three empty folders. */
export const alphaOverview: MailOverviewView = {
  facilityId: homeFacilityId,
  counts: { inbox: 1, sent: 0, drafts: 1, archive: 0, trash: 0 },
  messages: [alphaMessage],
};

/** Beta overview with the same message id and a different subject. */
export const betaOverview: MailOverviewView = {
  facilityId: homeFacilityId,
  counts: { inbox: 1, sent: 0, drafts: 0, archive: 0, trash: 0 },
  messages: [betaMessage],
};

/** An overview with every folder empty, for the empty-state branch. */
export const emptyOverview: MailOverviewView = {
  facilityId: homeFacilityId,
  counts: { inbox: 0, sent: 0, drafts: 0, archive: 0, trash: 0 },
  messages: [],
};

/** A compose draft before the first autosave creates it. */
export const emptyDraft: ComposeDraft = {
  draftId: '',
  version: 0,
  facilityId: homeFacilityId,
  subject: '',
  body: '',
  to: [],
  cc: [],
  attachments: [],
};

/** A content-bearing versioned draft used by autosave and confirmation tests. */
export const contentDraft: ComposeDraft = {
  draftId: 'synthetic-draft-001',
  version: 3,
  facilityId: homeFacilityId,
  subject: 'Synthetic subject',
  body: 'Synthetic body',
  to: [personRecipient],
  cc: [roleRecipient],
  attachments: [stagedAttachment],
};

/** A complete wire draft for the boundary-narrowing test. */
export const validWireDraft: WireMailDraft = {
  id: 'synthetic-draft-002',
  version: 2,
  facilityId: homeFacilityId,
  subject: 'Wire subject',
  body: 'Wire body',
  to: [{ kind: 'person', value: personRecipient.value }],
  cc: [],
  attachmentDocumentIds: [stagedAttachment.documentId],
};

/** A wire draft missing the id and version preconditions. */
export const invalidWireDraft: WireMailDraft = {
  subject: 'missing identifier and version',
  body: 'must fail closed',
};

/** A complete wire folder listing for the boundary-narrowing test. */
export const validWireListing: readonly WireMailView[] = [
  {
    message: {
      id: alphaMessage.id,
      subject: alphaMessage.subject,
      body: alphaMessage.body,
      senderSubject: alphaMessage.from.value,
      sentAt: alphaMessage.sentAt,
      to: [{ kind: 'person', value: personRecipient.value }],
      cc: [],
      attachmentDocumentIds: [stagedAttachment.documentId],
    },
    viewerFolder: 'inbox',
  },
  {
    message: {
      id: 'synthetic-message-002',
      subject: 'Synthetic sent mail',
      body: 'Sent body',
      senderSubject: selfSubject,
      sentAt: '2026-03-10T09:00:00Z',
      to: [{ kind: 'person', value: personRecipient.value }],
      cc: [],
      attachmentDocumentIds: [],
    },
    viewerFolder: 'sent',
  },
];

/** The first wire listing entry, narrowed by the boundary test when present. */
export const firstWireView = defined(validWireListing[0], 'first wire mail view');
