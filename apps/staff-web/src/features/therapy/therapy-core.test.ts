import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  AddendumNotAllowed,
  EpisodeNotDischargeable,
  MissingAttestation,
  ReferralNotActionable,
  SignedNoteLocked,
  StaleTherapyVersion,
  WrongTherapyPatient,
  acceptReferral,
  canAddAddendum,
  closeReferral,
  createAddendum,
  episodesForTenant,
  findEpisodeForReferral,
  findReferral,
  isEditableNote,
  referralsForTenant,
  signDischarge,
  signNote,
  updateNoteBody,
} from './domain';
import type { DischargeCommand, EpisodeNote, SignNoteCommand } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  draftSessionNote,
  signedProgressNote,
  signedSessionNote,
  syntheticAlphaEpisode,
  syntheticAlphaReferrals,
  syntheticBetaEpisode,
  syntheticEpisodes,
  syntheticReferrals,
} from './fixtures';
import { therapyKeys, therapyText } from './messages';
import type { TherapyMessageKey } from './messages';

const signCommand: SignNoteCommand = {
  signer: 'Synthetic Therapist',
  signedAt: '2026-03-15T12:00:00Z',
  attested: true,
  expectedVersion: draftSessionNote.version,
  patientLocalId: draftSessionNote.patientLocalId,
};

const dischargeCommand: DischargeCommand = {
  summaryId: 'discharge-001',
  body: 'Synthetic discharge summary',
  followUp: 'Synthetic outpatient review',
  signer: 'Synthetic Therapist',
  signedAt: '2026-03-16T12:00:00Z',
  sentToReferrerAt: '2026-03-16T12:30:00Z',
  attested: true,
  expectedVersion: syntheticAlphaEpisode.version,
};

describe('tenant-scoped therapy selection', () => {
  it('keeps two tenants with identical local identifiers apart', () => {
    const alphaReferrals = referralsForTenant(syntheticReferrals, alphaOrganizationId);
    const betaReferrals = referralsForTenant(syntheticReferrals, betaOrganizationId);
    expect(alphaReferrals).toHaveLength(3);
    expect(betaReferrals).toHaveLength(1);
    expect(findReferral(syntheticReferrals, alphaOrganizationId, 'ref-001')?.referrer).toBe(
      'Synthetic Referrer A',
    );
    expect(findReferral(syntheticReferrals, betaOrganizationId, 'ref-001')?.referrer).toBe(
      'Synthetic Referrer D',
    );
    expect(findReferral(syntheticReferrals, betaOrganizationId, 'missing')).toBeUndefined();
  });

  it('finds the episode linked to a referral within the active tenant only', () => {
    expect(episodesForTenant(syntheticEpisodes, alphaOrganizationId)).toEqual([
      syntheticAlphaEpisode,
    ]);
    expect(
      findEpisodeForReferral(syntheticEpisodes, betaOrganizationId, 'ref-002')?.id,
    ).toBe(syntheticBetaEpisode.id);
    expect(
      findEpisodeForReferral(syntheticEpisodes, alphaOrganizationId, 'ref-002'),
    ).toBeUndefined();
  });
});

describe('referral acceptance and closure', () => {
  it('opens an empty episode for a pending referral and advances the version', () => {
    const pending = syntheticAlphaReferrals[1]!;
    const result = acceptReferral(pending, 'episode-new');
    expect(result.referral.state).toBe('accepted');
    expect(result.referral.version).toBe(pending.version + 1);
    expect(result.episode.id).toBe('episode-new');
    expect(result.episode.referralId).toBe(pending.id);
    expect(result.episode.state).toBe('active');
    expect(result.episode.goals).toEqual([]);
    expect(result.episode.sessionNotes).toEqual([]);
    expect(result.episode.version).toBe(1);
  });

  it('refuses to accept an already accepted or closed referral', () => {
    expect(() => acceptReferral(syntheticAlphaReferrals[0]!, 'episode-x')).toThrow(
      ReferralNotActionable,
    );
    expect(() => acceptReferral(syntheticAlphaReferrals[2]!, 'episode-x')).toThrow(
      /cannot be changed from closed/,
    );
  });

  it('closes an accepted referral and refuses any other state', () => {
    const closed = closeReferral(syntheticAlphaReferrals[0]!);
    expect(closed.state).toBe('closed');
    expect(closed.version).toBe(syntheticAlphaReferrals[0]!.version + 1);
    expect(() => closeReferral(syntheticAlphaReferrals[1]!)).toThrow(ReferralNotActionable);
    expect(() => closeReferral(syntheticAlphaReferrals[2]!)).toThrow(ReferralNotActionable);
  });
});

describe('note editability and signatures', () => {
  it('marks only drafts as editable', () => {
    expect(isEditableNote(draftSessionNote)).toBe(true);
    expect(isEditableNote(signedSessionNote)).toBe(false);
  });

  it('allows an addendum only on a clean signed original', () => {
    const signedAddendum: EpisodeNote = { ...signedSessionNote, addendumOf: 'note-signed' };
    expect(canAddAddendum(signedSessionNote)).toBe(true);
    expect(canAddAddendum(draftSessionNote)).toBe(false);
    expect(canAddAddendum(signedAddendum)).toBe(false);
  });

  it('edits a draft body but never a signed note', () => {
    expect(updateNoteBody(draftSessionNote, 'Synthetic updated body').body).toBe(
      'Synthetic updated body',
    );
    expect(() => updateNoteBody(signedSessionNote, 'Synthetic overwrite')).toThrow(
      SignedNoteLocked,
    );
  });

  it('signs a draft with provenance and a new version', () => {
    const signed = signNote(draftSessionNote, signCommand);
    expect(signed.state).toBe('signed');
    expect(signed.signedBy).toBe(signCommand.signer);
    expect(signed.signedAt).toBe(signCommand.signedAt);
    expect(signed.version).toBe(draftSessionNote.version + 1);
    expect(draftSessionNote.state).toBe('draft');
  });

  it('rejects signing a signed note, a missing attestation, a stale version and a wrong patient', () => {
    expect(() => signNote(signedSessionNote, signCommand)).toThrow(SignedNoteLocked);
    expect(() => signNote(draftSessionNote, { ...signCommand, attested: false })).toThrow(
      MissingAttestation,
    );
    expect(() => signNote(draftSessionNote, { ...signCommand, expectedVersion: 99 })).toThrow(
      StaleTherapyVersion,
    );
    expect(() => signNote(draftSessionNote, { ...signCommand, patientLocalId: 'other' })).toThrow(
      WrongTherapyPatient,
    );
  });

  it('creates an editable addendum linked to the signed original', () => {
    const draft = createAddendum(signedSessionNote, {
      id: 'addendum-001',
      author: 'Synthetic Therapist',
      authoredAt: '2026-03-15T12:00:00Z',
      body: 'Synthetic addendum body',
    });
    expect(draft.state).toBe('draft');
    expect(draft.addendumOf).toBe(signedSessionNote.id);
    expect(draft.body).toBe('Synthetic addendum body');
    expect(draft.version).toBe(signedSessionNote.version + 1);
    expect(signedSessionNote.body).toBe('Synthetic baseline session completed.');
  });

  it('refuses an addendum on a draft or on another addendum', () => {
    expect(() =>
      createAddendum(draftSessionNote, {
        id: 'a',
        author: 'x',
        authoredAt: 'x',
        body: 'x',
      }),
    ).toThrow(AddendumNotAllowed);
    expect(() =>
      createAddendum(
        { ...signedSessionNote, addendumOf: 'note-signed' },
        { id: 'a', author: 'x', authoredAt: 'x', body: 'x' },
      ),
    ).toThrow(AddendumNotAllowed);
  });
});

describe('discharge signing', () => {
  it('signs the summary and marks the episode discharged', () => {
    const discharged = signDischarge(syntheticAlphaEpisode, dischargeCommand);
    expect(discharged.state).toBe('discharged');
    expect(discharged.version).toBe(syntheticAlphaEpisode.version + 1);
    expect(discharged.dischargeSummary?.id).toBe('discharge-001');
    expect(discharged.dischargeSummary?.signedBy).toBe('Synthetic Therapist');
    expect(discharged.dischargeSummary?.sentToReferrerAt).toBe(dischargeCommand.sentToReferrerAt);
    expect(syntheticAlphaEpisode.state).toBe('active');
  });

  it('refuses a discharge of a closed episode, without attestation, at a stale version or without signed progress', () => {
    expect(() =>
      signDischarge({ ...syntheticAlphaEpisode, state: 'discharged' }, dischargeCommand),
    ).toThrow(EpisodeNotDischargeable);
    expect(() => signDischarge(syntheticAlphaEpisode, { ...dischargeCommand, attested: false })).toThrow(
      MissingAttestation,
    );
    expect(() =>
      signDischarge(syntheticAlphaEpisode, { ...dischargeCommand, expectedVersion: 99 }),
    ).toThrow(StaleTherapyVersion);
    expect(() =>
      signDischarge(
        { ...syntheticAlphaEpisode, progressNotes: [draftSessionNote] },
        dischargeCommand,
      ),
    ).toThrow(EpisodeNotDischargeable);
  });

  it('accepts a signed progress note as the required discharge evidence', () => {
    expect(signedProgressNote.state).toBe('signed');
    expect(syntheticAlphaEpisode.progressNotes).toContain(signedProgressNote);
  });
});

describe('synthetic fixtures and bilingual labels', () => {
  it('uses synthetic values and covers every state', () => {
    expect(syntheticAlphaReferrals.map((referral) => referral.state)).toEqual([
      'accepted',
      'pending',
      'closed',
    ]);
    for (const episode of syntheticEpisodes) {
      expect(episode.patientName.latinName).toContain('Synthetic');
    }
    expect(syntheticAlphaEpisode.goals.map((goal) => goal.state)).toEqual([
      'active',
      'met',
      'discontinued',
    ]);
    expect(syntheticAlphaEpisode.outcomeScales[0]?.measurements[0]?.unit).toBe('/10');
  });

  it('provides a non-empty label in both bundles for every therapy key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of therapyKeys) {
        expect(therapyText(locale, key).length).toBeGreaterThan(0);
      }
    }
    const key: TherapyMessageKey = 'signDischarge';
    expect(therapyText('en', key)).not.toBe(therapyText('ar', key));
    expect(therapyText('ar', key)).toMatch(/[\u0600-\u06FF]/);
  });

  it('maps every lifecycle state to a defined message key', () => {
    const keys: readonly TherapyMessageKey[] = [
      'statePending',
      'stateAccepted',
      'stateClosed',
      'stateActive',
      'stateDischarged',
      'stateDraft',
      'stateSigned',
      'goalActive',
      'goalMet',
      'goalDiscontinued',
    ];
    for (const key of keys) {
      expect(therapyKeys).toContain(key);
    }
  });

  it('uses logical styling properties in every therapy component', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const components = readdirSync(featureDir).filter(
      (name) => name.endsWith('.tsx') && !name.includes('.test.'),
    );
    expect(components.length).toBeGreaterThan(0);
    for (const name of components) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
