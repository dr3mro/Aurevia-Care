import type { PersonName } from '../../i18n/format';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { Episode, EpisodeNote, Goal, OutcomeScale, Referral } from './domain';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = syntheticOrganizations[0]!.id;
export const betaOrganizationId = syntheticOrganizations[1]!.id;

/** A synthetic patient shared by both tenants to prove scope separation. */
export const syntheticTherapyPatient: PersonName = {
  latinName: 'Synthetic Layla',
  localName: 'ليلى تجريبية',
};

/** Synthetic referrals. Alpha 'ref-001' is accepted; beta 'ref-001' is pending. */
export const syntheticAlphaReferrals: readonly Referral[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'ref-001',
    patientName: syntheticTherapyPatient,
    patientLocalId: 'patient-001',
    referrer: 'Synthetic Referrer A',
    reason: 'Synthetic knee rehabilitation',
    receivedAt: '2026-03-02T08:00:00Z',
    state: 'accepted',
    version: 2,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'ref-002',
    patientName: { latinName: 'Synthetic Omar', localName: 'عمر تجريبي' },
    patientLocalId: 'patient-002',
    referrer: 'Synthetic Referrer B',
    reason: 'Synthetic shoulder assessment',
    receivedAt: '2026-03-03T09:30:00Z',
    state: 'pending',
    version: 1,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'ref-003',
    patientName: { latinName: 'Synthetic Sara', localName: 'سارة تجريبية' },
    patientLocalId: 'patient-003',
    referrer: 'Synthetic Referrer C',
    reason: 'Synthetic gait review',
    receivedAt: '2026-02-20T07:15:00Z',
    state: 'closed',
    version: 3,
  },
];

export const syntheticBetaReferrals: readonly Referral[] = [
  {
    organizationId: betaOrganizationId,
    id: 'ref-001',
    patientName: { latinName: 'Synthetic Reem', localName: 'ريم تجريبية' },
    patientLocalId: 'patient-001',
    referrer: 'Synthetic Referrer D',
    reason: 'Synthetic hand therapy',
    receivedAt: '2026-03-04T11:00:00Z',
    state: 'pending',
    version: 1,
  },
];

export const syntheticReferrals: readonly Referral[] = [
  ...syntheticAlphaReferrals,
  ...syntheticBetaReferrals,
];

/** Synthetic goals covering all three goal states. */
export const syntheticGoals: readonly Goal[] = [
  {
    id: 'goal-1',
    description: 'Walk 50 metres independently',
    targetOn: '2026-04-15',
    state: 'active',
  },
  {
    id: 'goal-2',
    description: 'Ascend one flight of stairs',
    targetOn: '2026-04-01',
    state: 'met',
  },
  {
    id: 'goal-3',
    description: 'Discontinue assisted exercises',
    targetOn: '2026-04-20',
    state: 'discontinued',
  },
];

/** Versioned outcome scales; units are stored verbatim with each measurement. */
export const syntheticOutcomeScales: readonly OutcomeScale[] = [
  {
    id: 'scale-pain',
    name: 'Numeric pain rating',
    code: 'NPRS',
    scaleVersion: 2,
    unit: '/10',
    measurements: [
      { id: 'measure-1', recordedAt: '2026-03-05T09:00:00Z', value: 7, unit: '/10' },
      { id: 'measure-2', recordedAt: '2026-03-12T09:00:00Z', value: 4, unit: '/10' },
    ],
  },
  {
    id: 'scale-rom',
    name: 'Knee range of motion',
    code: 'ROM',
    scaleVersion: 1,
    unit: 'degrees',
    measurements: [{ id: 'measure-3', recordedAt: '2026-03-12T09:00:00Z', value: 95, unit: 'degrees' }],
  },
];

/** A signed session note: immutable in the interface, amendable by addendum. */
export const signedSessionNote: EpisodeNote = {
  organizationId: alphaOrganizationId,
  id: 'note-signed',
  episodeId: 'episode-001',
  patientLocalId: 'patient-001',
  author: 'Synthetic Therapist',
  authoredAt: '2026-03-05T10:00:00Z',
  body: 'Synthetic baseline session completed.',
  state: 'signed',
  version: 2,
  signedBy: 'Synthetic Therapist',
  signedAt: '2026-03-05T10:30:00Z',
};

/** An unsigned session note: editable and signable. */
export const draftSessionNote: EpisodeNote = {
  organizationId: alphaOrganizationId,
  id: 'note-draft',
  episodeId: 'episode-001',
  patientLocalId: 'patient-001',
  author: 'Synthetic Therapist',
  authoredAt: '2026-03-12T10:00:00Z',
  body: 'SyntheticDraft session note body.',
  state: 'draft',
  version: 1,
};

/** A signed progress note is required before discharge can be signed. */
export const signedProgressNote: EpisodeNote = {
  ...signedSessionNote,
  id: 'progress-signed',
  body: 'Synthetic progress summary.',
  version: 3,
};

/** The synthetic alpha episode used by the detail screen and page tests. */
export const syntheticAlphaEpisode: Episode = {
  organizationId: alphaOrganizationId,
  id: 'episode-001',
  referralId: 'ref-001',
  patientName: syntheticTherapyPatient,
  patientLocalId: 'patient-001',
  timeZone: 'America/New_York',
  state: 'active',
  evaluation: 'Synthetic evaluation of the left knee after referral.',
  problems: ['Synthetic reduced knee range', 'Synthetic gait asymmetry'],
  baseline: [
    { id: 'baseline-1', name: 'Pain score', value: 7, unit: '/10', measuredOn: '2026-03-02' },
  ],
  goals: syntheticGoals,
  plannedFrequency: 'Twice weekly',
  plannedDuration: 'Six weeks',
  interventions: ['Synthetic therapeutic exercise', 'Synthetic manual therapy'],
  outcomeScales: syntheticOutcomeScales,
  sessionNotes: [signedSessionNote, draftSessionNote],
  progressNotes: [signedProgressNote],
  version: 4,
};

/** A beta episode with the same local id, scoped to the other tenant. */
export const syntheticBetaEpisode: Episode = {
  ...syntheticAlphaEpisode,
  organizationId: betaOrganizationId,
  id: 'episode-001',
  referralId: 'ref-002',
  patientName: { latinName: 'Synthetic Reem', localName: 'ريم تجريبية' },
  patientLocalId: 'patient-001',
  timeZone: 'Europe/London',
};

export const syntheticEpisodes: readonly Episode[] = [syntheticAlphaEpisode, syntheticBetaEpisode];
