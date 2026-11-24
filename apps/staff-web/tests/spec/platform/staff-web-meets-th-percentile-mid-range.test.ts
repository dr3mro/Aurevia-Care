/**
 * WEB-08 clause 2 evidence, staff web: 75th-percentile Core Web Vitals on a
 * mid-range laptop over broadband.
 *
 * This file is deliberately a STRUCTURAL CONTRACT test, not a browser timing
 * proof. jsdom cannot measure largest contentful paint, interaction to next
 * paint or layout shift, and no collector or percentile harness exists yet.
 * The enabled tests therefore assert two things that are true today: the
 * approved budgets and qualifiers are encoded as one contract, and the clause
 * status document records exactly that contract plus an honest gap. The full
 * would-be browser test lives verbatim in that document's Gap section.
 *
 * Synthetic fixtures only; no production module is imported or changed.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { syntheticFacility, syntheticStaff } from '../../../src/fixtures/synthetic';

/** The three WEB-08 clause-2 budgets, with explicit units and an approved source. */
interface LaptopVitalsBudget {
  /** Largest contentful paint, in milliseconds (threshold: 2500). */
  readonly largestContentfulPaintMs: number;
  /** Interaction to next paint, in milliseconds (threshold: 200). */
  readonly interactionToNextPaintMs: number;
  /** Cumulative layout shift, unitless (threshold: 0.1). */
  readonly cumulativeLayoutShift: number;
}

/** A candidate profile a future measurement run would submit to the gate. */
interface CandidateProfile extends LaptopVitalsBudget {
  /** Synthetic subject the harness would use; never a real staff identity. */
  readonly subject: string;
  /** Synthetic facility code the harness would open. */
  readonly facilityCode: string;
  /** Aggregation quantile; the clause fixes it at the 75th percentile. */
  readonly percentile: number;
  /** Viewport of the mid-range laptop project in the Playwright matrix. */
  readonly viewport: string;
  /** Device class the budget was approved for. */
  readonly deviceClass: string;
  /** Network class the budget was approved for. */
  readonly connection: string;
  /** Minimum number of cold navigation samples before a p75 is trustworthy. */
  readonly sampleCount: number;
}

/** The first rule a candidate profile breaks, in the order the gate checks them. */
type Refusal =
  | 'percentile'
  | 'viewport'
  | 'deviceOrNetwork'
  | 'sampleCount'
  | 'largestContentfulPaintMs'
  | 'interactionToNextPaintMs'
  | 'cumulativeLayoutShift';

/** Approved p75 laptop/broadband budgets from WEB-08 clause 2. */
const LAPTOP_P75_BUDGET: LaptopVitalsBudget = {
  largestContentfulPaintMs: 2500,
  interactionToNextPaintMs: 200,
  cumulativeLayoutShift: 0.1,
};

/** The mobile LCP target from the separate, unbuilt performance story. */
const MOBILE_LCP_TARGET_MS = 4000;

/** Qualifiers without which a timing figure is not the WEB-08 clause-2 rule. */
const CLAUSE_QUALIFIERS = ['75th percentile', 'mid-range laptop', 'broadband'] as const;

/** The threshold literals that must appear in the clause document, word for word. */
const CLAUSE_THRESHOLDS = ['2.5 s or less', '200 ms or less', '0.1 or less'] as const;

/**
 * Synthetic subject label the would-be harness binds to. The generated staff
 * type leaves `subject` optional, so fall back to the fixture's synthetic value;
 * never a real staff identity.
 */
const syntheticSubject = syntheticStaff.subject ?? 'synthetic.reception';

/** Absolute path to the clause status document this contract test guards. */
const clauseDocumentPath = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  '..',
  '..',
  '..',
  'docs',
  'spec',
  'clauses',
  'platform',
  'staff-web-meets-th-percentile-mid-range.md',
);

/**
 * Build the only candidate profile this contract accepts: the approved laptop
 * p75 budgets bound to the synthetic facility and subject.
 */
function approvedSyntheticProfile(): CandidateProfile {
  return {
    ...LAPTOP_P75_BUDGET,
    subject: syntheticSubject,
    facilityCode: syntheticFacility.code,
    percentile: 0.75,
    viewport: '1280x720',
    deviceClass: 'mid-range laptop',
    connection: 'broadband',
    sampleCount: 24,
  };
}

/**
 * Refuse a candidate that changes the measurement context or relaxes a budget.
 *
 * The checks are deterministic so a later, real gate can reuse this rule: the
 * percentile, laptop viewport, device and network class, and minimum sample
 * count are fixed, and every metric value strictly above its approved p75
 * budget — including the mobile LCP target — is rejected with the name of the
 * first offending rule. A profile that satisfies every rule is accepted. This
 * is local test vocabulary, not a production guard; it documents the decision
 * the future harness must make.
 */
function firstRefusal(profile: CandidateProfile): Refusal | null {
  if (profile.percentile !== 0.75) {
    return 'percentile';
  }
  if (profile.viewport !== '1280x720') {
    return 'viewport';
  }
  if (profile.deviceClass !== 'mid-range laptop' || profile.connection !== 'broadband') {
    return 'deviceOrNetwork';
  }
  if (profile.sampleCount < 20) {
    return 'sampleCount';
  }
  if (profile.largestContentfulPaintMs > LAPTOP_P75_BUDGET.largestContentfulPaintMs) {
    return 'largestContentfulPaintMs';
  }
  if (profile.interactionToNextPaintMs > LAPTOP_P75_BUDGET.interactionToNextPaintMs) {
    return 'interactionToNextPaintMs';
  }
  if (profile.cumulativeLayoutShift > LAPTOP_P75_BUDGET.cumulativeLayoutShift) {
    return 'cumulativeLayoutShift';
  }
  return null;
}

describe('WEB-08 clause 2 — laptop p75 Core Web Vitals contract (structural only)', () => {
  it('recordsP75LaptopThresholdsAsTheClauseContract', () => {
    expect(LAPTOP_P75_BUDGET).toEqual({
      largestContentfulPaintMs: 2500,
      interactionToNextPaintMs: 200,
      cumulativeLayoutShift: 0.1,
    });
    expect(MOBILE_LCP_TARGET_MS).toBe(4000);
    expect(MOBILE_LCP_TARGET_MS).toBeGreaterThan(LAPTOP_P75_BUDGET.largestContentfulPaintMs);
    expect(approvedSyntheticProfile()).toEqual({
      largestContentfulPaintMs: 2500,
      interactionToNextPaintMs: 200,
      cumulativeLayoutShift: 0.1,
      subject: syntheticSubject,
      facilityCode: syntheticFacility.code,
      percentile: 0.75,
      viewport: '1280x720',
      deviceClass: 'mid-range laptop',
      connection: 'broadband',
      sampleCount: 24,
    });
    expect(firstRefusal(approvedSyntheticProfile())).toBeNull();

    const clauseDocument = readFileSync(clauseDocumentPath, 'utf8');
    for (const qualifier of CLAUSE_QUALIFIERS) {
      expect(clauseDocument).toContain(qualifier);
    }
    for (const threshold of CLAUSE_THRESHOLDS) {
      expect(clauseDocument).toContain(threshold);
    }
    expect(clauseDocument).toContain(
      'largest contentful paint of 2.5 s or less, interaction to next paint of 200 ms or less and layout shift of 0.1 or less',
    );
    // The document must stay honest about what is and is not proven, and must
    // name the synthetic identity the would-be harness is scoped to.
    expect(clauseDocument).toContain('Not built');
    expect(clauseDocument).toContain('structural contract evidence only');
    expect(clauseDocument).toContain(syntheticSubject);
    expect(clauseDocument).toContain(syntheticFacility.code);
  });

  it('rejectsRelaxingALaptopThresholdOrSubstitutingMobileTargets', () => {
    const approved = approvedSyntheticProfile();
    expect(firstRefusal(approved)).toBeNull();

    expect(firstRefusal({ ...approved, percentile: 0.9 })).toBe('percentile');
    expect(firstRefusal({ ...approved, viewport: '390x844' })).toBe('viewport');
    expect(firstRefusal({ ...approved, deviceClass: 'phone' })).toBe('deviceOrNetwork');
    expect(firstRefusal({ ...approved, connection: 'lte' })).toBe('deviceOrNetwork');
    expect(firstRefusal({ ...approved, sampleCount: 19 })).toBe('sampleCount');

    expect(firstRefusal({ ...approved, largestContentfulPaintMs: 2501 })).toBe(
      'largestContentfulPaintMs',
    );
    expect(firstRefusal({ ...approved, interactionToNextPaintMs: 201 })).toBe(
      'interactionToNextPaintMs',
    );
    expect(firstRefusal({ ...approved, cumulativeLayoutShift: 0.1001 })).toBe(
      'cumulativeLayoutShift',
    );

    // The mobile LCP target alone is not a laptop budget, and a full mobile
    // substitute fails on its context before any timing figure is considered.
    const mobileLcp: CandidateProfile = {
      ...approved,
      largestContentfulPaintMs: MOBILE_LCP_TARGET_MS,
    };
    expect(firstRefusal(mobileLcp)).toBe('largestContentfulPaintMs');
    const mobileSubstitute: CandidateProfile = {
      ...mobileLcp,
      viewport: '390x844',
      deviceClass: 'phone',
      connection: 'lte',
      sampleCount: 8,
    };
    expect(firstRefusal(mobileSubstitute)).toBe('viewport');
  });
});
