import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  AmendmentNotAllowed,
  IncompleteDraft,
  MissingAttestation,
  SignedRevisionLocked,
  StaleDraftVersion,
  WrongPatientContext,
  canAmend,
  chartsForTenant,
  createAmendment,
  currentRevision,
  findChart,
  isEditable,
  markEnteredInError,
  markSuperseded,
  replaceDocument,
  replaceRevision,
  rotateDocument,
  signDraft,
  syntheticChecksum,
  updateDraft,
  visibleDocuments,
  voidDocument,
} from './domain';
import type { NoteRevision } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  syntheticAlphaChart,
  syntheticBetaChart,
  syntheticCharts,
  syntheticEncounterLocalId,
  syntheticEnteredInErrorReason,
  syntheticSigner,
  syntheticVoidReason,
} from './fixtures';
import { chartKeys, chartText, verificationMessageKey } from './messages';
import { must } from '../../../tests/must';

const alphaCurrent = (): NoteRevision => {
  const revision = currentRevision(syntheticAlphaChart.revisions);
  if (!revision) {
    throw new Error('alpha chart has no current revision');
  }
  return revision;
};

const betaCurrent = (): NoteRevision => {
  const revision = currentRevision(syntheticBetaChart.revisions);
  if (!revision) {
    throw new Error('beta chart has no current revision');
  }
  return revision;
};

const signCommand = (revision: NoteRevision) => ({
  signer: syntheticSigner,
  attested: true,
  signedAt: '2026-03-09T12:00:00Z',
  effectiveOn: '2026-03-09',
  patientLocalId: revision.patientLocalId,
  encounterLocalId: syntheticEncounterLocalId,
  expectedVersion: revision.version,
});

describe('tenant-scoped chart selection', () => {
  it('keeps two tenants that use the same local chart id apart', () => {
    const alpha = chartsForTenant(syntheticCharts, alphaOrganizationId);
    const beta = chartsForTenant(syntheticCharts, betaOrganizationId);

    expect(alpha).toHaveLength(1);
    expect(beta).toHaveLength(1);
    expect(alpha[0]?.localId).toBe('chart-001');
    expect(beta[0]?.localId).toBe('chart-001');
    expect(alpha[0]?.patientName.latinName).toBe('Synthetic Layla');
    expect(beta[0]?.patientName.latinName).toBe('Synthetic Reem');
  });

  it('finds a chart only by the composite tenant key and local id', () => {
    expect(
      findChart(syntheticCharts, alphaOrganizationId, 'chart-001')?.patientName.latinName,
    ).toBe('Synthetic Layla');
    expect(findChart(syntheticCharts, betaOrganizationId, 'chart-001')?.patientName.latinName).toBe(
      'Synthetic Reem',
    );
    expect(findChart(syntheticCharts, alphaOrganizationId, 'missing')).toBeUndefined();
    expect(findChart(syntheticCharts, 'unknown', 'chart-001')).toBeUndefined();
    expect(chartsForTenant(syntheticCharts, 'unknown')).toEqual([]);
  });
});

describe('note revision state and guards', () => {
  it('allows editing only draft revisions and amending only clean signed ones', () => {
    const draft = betaCurrent();
    const signed = must(syntheticAlphaChart.revisions[1]);
    const enteredInError = must(syntheticAlphaChart.revisions[0]);

    expect(isEditable(draft)).toBe(true);
    expect(isEditable(signed)).toBe(false);
    expect(canAmend(signed)).toBe(true);
    expect(canAmend(draft)).toBe(false);
    expect(canAmend(enteredInError)).toBe(false);
    expect(canAmend(markSuperseded(signed, 'rev-amend'))).toBe(false);
  });

  it('updates a draft field and refuses to edit a signed revision', () => {
    const draft = betaCurrent();
    const updated = updateDraft(draft, 'assessment', 'Synthetic revised assessment');
    expect(updated.content.assessment).toBe('Synthetic revised assessment');
    expect(updated.version).toBe(draft.version);
    expect(draft.content.assessment).not.toBe('Synthetic revised assessment');
    expect(() => updateDraft(must(syntheticAlphaChart.revisions[1]), 'plan', 'x')).toThrow(
      SignedRevisionLocked,
    );
  });

  it('signs a complete draft with attestation, correct context and a fresh version', () => {
    const draft = betaCurrent();
    const signed = signDraft(draft, signCommand(draft));

    expect(signed.state).toBe('signed');
    expect(signed.signer).toBe(syntheticSigner);
    expect(signed.signedAt).toBe('2026-03-09T12:00:00Z');
    expect(signed.effectiveOn).toBe('2026-03-09');
    expect(signed.checksum).toBe(syntheticChecksum(draft.content));
    expect(signed.checksum).toMatch(/^synthetic:/);
    expect(isEditable(signed)).toBe(false);
  });

  it('refuses signing with missing fields, no attestation, wrong context or a stale version', () => {
    const draft = betaCurrent();
    const incomplete: NoteRevision = {
      ...draft,
      content: { ...draft.content, plan: '   ' },
    };
    expect(() => signDraft(incomplete, signCommand(incomplete))).toThrow(IncompleteDraft);
    expect(() => signDraft(draft, { ...signCommand(draft), attested: false })).toThrow(
      MissingAttestation,
    );
    expect(() =>
      signDraft(draft, {
        ...signCommand(draft),
        patientLocalId: 'chart-wrong',
        encounterLocalId: 'enc-wrong',
      }),
    ).toThrow(WrongPatientContext);
    expect(() =>
      signDraft(draft, { ...signCommand(draft), expectedVersion: draft.version - 1 }),
    ).toThrow(StaleDraftVersion);
    expect(() =>
      signDraft(must(syntheticAlphaChart.revisions[1]), signCommand(alphaCurrent())),
    ).toThrow(SignedRevisionLocked);
  });

  it('creates an amendment draft linked to a clean signed revision', () => {
    const signed = must(syntheticAlphaChart.revisions[1]);
    const amendment = createAmendment(signed, 'rev-003');

    expect(amendment.id).toBe('rev-003');
    expect(amendment.kind).toBe('amendment');
    expect(amendment.state).toBe('draft');
    expect(amendment.amends).toBe(signed.id);
    expect(amendment.version).toBe(signed.version + 1);
    expect(isEditable(amendment)).toBe(true);
    expect(() => createAmendment(betaCurrent(), 'rev-x')).toThrow(AmendmentNotAllowed);
  });

  it('records entered-in-error evidence without changing the revision content', () => {
    const signed = must(syntheticAlphaChart.revisions[1]);
    const flagged = markEnteredInError(signed, {
      actor: syntheticSigner,
      reason: syntheticEnteredInErrorReason,
      affectedRecipients: ['synthetic.referrer'],
      recordedAt: '2026-03-09T13:00:00Z',
    });

    expect(flagged.content).toEqual(signed.content);
    expect(flagged.version).toBe(signed.version);
    expect(flagged.enteredInError?.actor).toBe(syntheticSigner);
    expect(flagged.enteredInError?.reason).toBe(syntheticEnteredInErrorReason);
    expect(flagged.enteredInError?.affectedRecipients).toEqual(['synthetic.referrer']);
    expect(canAmend(flagged)).toBe(false);
  });

  it('returns the highest version and stays undefined for an empty history', () => {
    expect(currentRevision([])).toBeUndefined();
    expect(currentRevision([alphaCurrent(), must(syntheticAlphaChart.revisions[0])])).toBe(
      alphaCurrent(),
    );
  });

  it('replaces only the matching tenant and revision id', () => {
    const updated = replaceRevision(
      must(syntheticCharts[0]).revisions,
      alphaOrganizationId,
      'rev-002',
      (revision) => ({
        ...revision,
        content: { ...revision.content, plan: 'Synthetic replacement' },
      }),
    );
    expect(updated).toHaveLength(syntheticAlphaChart.revisions.length);
    expect(currentRevision(updated)?.content.plan).toBe('Synthetic replacement');

    expect(
      replaceRevision(must(syntheticCharts[1]).revisions, alphaOrganizationId, 'rev-002', (r) => r),
    ).toEqual(syntheticBetaChart.revisions);
    expect(
      replaceRevision(syntheticAlphaChart.revisions, alphaOrganizationId, 'missing', (r) => r),
    ).toEqual(syntheticAlphaChart.revisions);
  });
});

describe('document associations', () => {
  it('hides voided documents from the live list but keeps them in the source array', () => {
    const visible = visibleDocuments(syntheticAlphaChart.documents);
    expect(visible.map((document) => document.id)).toEqual(['doc-001', 'doc-002', 'doc-003']);
    expect(syntheticAlphaChart.documents.some((document) => document.void !== undefined)).toBe(
      true,
    );
  });

  it('voids a document with an audited reason', () => {
    const target = must(syntheticAlphaChart.documents[0]);
    const voided = voidDocument(target, {
      actor: syntheticSigner,
      reason: syntheticVoidReason,
      voidedAt: '2026-03-09T14:00:00Z',
    });
    expect(voided.void?.actor).toBe(syntheticSigner);
    expect(voided.void?.reason).toBe(syntheticVoidReason);
    expect(target.void).toBeUndefined();
    expect(visibleDocuments([voided])).toEqual([]);
  });

  it('normalizes rotation as presentation metadata only', () => {
    const target = must(syntheticAlphaChart.documents[2]);
    expect(rotateDocument(target, 90).rotationDegrees).toBe(180);
    expect(rotateDocument(target, -180).rotationDegrees).toBe(270);
    expect(rotateDocument(target, 360).rotationDegrees).toBe(90);
    expect(rotateDocument(target, 90).checksum).toBe(target.checksum);
  });

  it('replaces only the matching tenant and document id', () => {
    const updated = replaceDocument(
      syntheticAlphaChart.documents,
      alphaOrganizationId,
      'doc-001',
      (document) => ({ ...document, version: document.version + 1 }),
    );
    expect(updated.find((document) => document.id === 'doc-001')?.version).toBe(3);
    expect(
      replaceDocument(syntheticBetaChart.documents, alphaOrganizationId, 'doc-001', (d) => d),
    ).toEqual(syntheticBetaChart.documents);
    expect(
      replaceDocument(syntheticAlphaChart.documents, alphaOrganizationId, 'missing', (d) => d),
    ).toEqual(syntheticAlphaChart.documents);
  });
});

describe('synthetic fixtures and localized labels', () => {
  it('carries units, actors, verification states and tenant-scoped versions', () => {
    for (const chart of syntheticCharts) {
      expect(
        chart.vitals.every((vital) => vital.unit.length > 0 && vital.measuredAt.endsWith('Z')),
      ).toBe(true);
    }
    expect(syntheticAlphaChart.problems[0]?.clinicalAuthor).toBe('synthetic.clinician');
    expect(syntheticAlphaChart.problems[0]?.enteredBy).toBe('synthetic.assistant');
    expect(syntheticAlphaChart.problems[0]?.originalText).toContain('Synthetic');
    expect(syntheticAlphaChart.problems.some((entry) => entry.verification === 'verified')).toBe(
      true,
    );
    expect(syntheticAlphaChart.allergies.some((entry) => entry.verification === 'none')).toBe(true);
    expect(syntheticAlphaChart.allergies.some((entry) => entry.verification === 'unknown')).toBe(
      true,
    );
    expect(syntheticAlphaChart.documents.map((document) => document.language)).toEqual([
      'en',
      'ar',
      'en',
      'en',
    ]);
    expect(syntheticBetaChart.documents[0]?.version).not.toBe(
      syntheticAlphaChart.documents[0]?.version,
    );
  });

  it('provides a non-empty label in both bundles for every chart key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of chartKeys) {
        expect(chartText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(chartText('ar', 'longitudinalChart')).toMatch(/[\u0600-\u06FF]/);
    expect(chartText('en', 'longitudinalChart')).not.toBe(chartText('ar', 'longitudinalChart'));
  });

  it('maps every verification state to a defined message key', () => {
    for (const state of Object.keys(
      verificationMessageKey,
    ) as (keyof typeof verificationMessageKey)[]) {
      expect(chartKeys).toContain(verificationMessageKey[state]);
    }
  });
});

describe('RTL-safe layout', () => {
  it('uses logical styling properties in every feature component', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const componentFiles = readdirSync(featureDir).filter(
      (name) => name.endsWith('.tsx') && !name.includes('.test.'),
    );
    expect(componentFiles.length).toBeGreaterThan(0);
    for (const name of componentFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
