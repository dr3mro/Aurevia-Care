import { defined } from '../../components/invariant';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { InboxView, Thread } from './types';

/**
 * Clearly synthetic unit-inbox data for two tenants whose local thread ids are
 * deliberately identical. Names, sizes and times are invented for review only.
 */

/** Synthetic alpha tenant identifier. */
export const alphaOrganizationId = defined(
  syntheticOrganizations[0],
  'alpha organization',
).id;

/** Synthetic beta tenant identifier. */
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Synthetic time zone for the front desk. */
export const deskTimeZone = 'America/New_York';

/** One open thread with a clean and an unscanned attachment. */
const openThread: Thread = {
  id: 'thread-001',
  patientName: { latinName: 'Synthetic Grace Okafor', localName: 'ليلى تجريبية' },
  patientId: 'synthetic-patient-001',
  topic: 'Appointment change',
  state: 'replied',
  owner: 'L. Haddad',
  unread: false,
  lastActivityAt: '2026-03-09T16:40:00Z',
  timeZone: deskTimeZone,
  connectionVerified: true,
  version: 3,
  messages: [
    {
      id: 'message-001',
      senderRole: 'patient',
      senderName: 'Synthetic Grace Okafor',
      body: 'Could I move Thursdays visit to the afternoon? I also attached my previous visit summary.',
      sentAt: '2026-03-09T16:12:00Z',
      timeZone: deskTimeZone,
      attachments: [
        {
          id: 'attachment-001',
          fileName: 'visit-summary.pdf',
          sizeLabel: '212 KB',
          scanState: 'clean',
        },
        {
          id: 'attachment-002',
          fileName: 'photo.jpeg',
          sizeLabel: '128 KB',
          scanState: 'unscanned',
        },
      ],
    },
    {
      id: 'message-002',
      senderRole: 'staff',
      senderName: 'Lina Haddad',
      body: 'I can offer 2:30 PM or 3:15 PM on Thursday. Which do you prefer?',
      sentAt: '2026-03-09T16:40:00Z',
      timeZone: deskTimeZone,
      attachments: [],
    },
  ],
};

/** The three remaining work-item states. */
const otherThreads: readonly Thread[] = [
  {
    id: 'thread-002',
    patientName: { latinName: 'Synthetic Hassan Ibrahim' },
    patientId: 'synthetic-patient-002',
    topic: 'Refill question',
    state: 'new',
    owner: '',
    unread: true,
    lastActivityAt: '2026-03-09T14:05:00Z',
    timeZone: deskTimeZone,
    connectionVerified: true,
    version: 1,
    messages: [],
  },
  {
    id: 'thread-003',
    patientName: { latinName: 'Synthetic Chloe Becker' },
    patientId: 'synthetic-patient-003',
    topic: 'Vaccination record',
    state: 'waiting',
    owner: 'M. Chen',
    unread: false,
    lastActivityAt: '2026-03-09T11:30:00Z',
    timeZone: deskTimeZone,
    connectionVerified: true,
    version: 2,
    messages: [],
  },
  {
    id: 'thread-004',
    patientName: { latinName: 'Synthetic Samir Aziz' },
    patientId: 'synthetic-patient-004',
    topic: 'Billing question',
    state: 'resolved',
    owner: 'L. Haddad',
    unread: false,
    lastActivityAt: '2026-03-08T15:00:00Z',
    timeZone: deskTimeZone,
    connectionVerified: true,
    version: 4,
    messages: [],
  },
];

/** Synthetic front-desk inbox matching the approved picture. */
export const alphaInboxView: InboxView = {
  unit: {
    name: 'Front desk',
    serviceHours: 'Mon to Sat 8:00 to 17:00',
    responseTarget: '1 business day',
    owner: 'Lina Haddad',
    backup: 'Maya Chen',
  },
  threads: [openThread, ...otherThreads],
  pendingFileNames: ['prep-instructions.pdf'],
};

/** Synthetic beta inbox with the same local thread ids and a different unit. */
export const betaInboxView: InboxView = {
  unit: {
    name: 'Synthetic lab desk',
    serviceHours: 'Mon to Fri 7:00 to 15:00',
    responseTarget: '4 business hours',
    owner: 'Synthetic Lab Owner',
    backup: 'Synthetic Lab Backup',
  },
  threads: otherThreads.map((thread) => ({
    ...thread,
    patientId: `beta-${thread.patientId}`,
    patientName: { latinName: `${thread.patientName.latinName} beta` },
  })),
  pendingFileNames: [],
};
