import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { AccessionEvent, AccessionPage, AccessionView, LabAccession } from './types';

/** The two synthetic tenants; their identifiers are fixed test constants. */
export const alphaOrganizationId = syntheticOrganizations[0]!.id;
export const betaOrganizationId = syntheticOrganizations[1]!.id;

/**
 * Build one fully populated synthetic accession. Every value is clearly fake:
 * numbers use the `SYN` prefix and identifiers are UUID-shaped test constants.
 *
 * @param overrides fields to replace for a particular state scenario
 * @returns a synthetic accession projection
 */
export function accession(overrides: Partial<LabAccession> = {}): LabAccession {
  return {
    id: 'aaaaaaaa-0000-4000-8000-000000000001',
    version: 1,
    accessionNo: 'SYN-ACC-0001',
    accessionIssuer: 'SYNLAB',
    patientId: 'bbbbbbbb-0000-4000-8000-000000000001',
    sourceOrderRef: 'SYN-ORDER-0001',
    state: 'accessioned',
    specimenType: null,
    siteCode: null,
    collector: null,
    collectedAt: null,
    receivedAt: null,
    quality: null,
    rejectionReason: null,
    rejectionNotification: null,
    replacesAccessionId: null,
    ...overrides,
  };
}

/**
 * Build one synthetic history event.
 *
 * @param overrides fields to replace for a particular event
 * @returns a synthetic accession event
 */
export function accessionEvent(overrides: Partial<AccessionEvent> = {}): AccessionEvent {
  return {
    id: 'cccccccc-0000-4000-8000-000000000001',
    accessionId: 'aaaaaaaa-0000-4000-8000-000000000001',
    accessionVersion: 1,
    action: 'accessioned',
    fromState: null,
    toState: 'accessioned',
    reason: null,
    matchedPatientId: null,
    occurredAt: '2026-03-09T06:00:00Z',
    ...overrides,
  };
}

/**
 * Build one synthetic accession view with history.
 *
 * @param overrides fields to replace for a particular scenario
 * @returns a synthetic accession view
 */
export function accessionView(overrides: Partial<AccessionView> = {}): AccessionView {
  return {
    accession: accession(),
    events: [accessionEvent()],
    ...overrides,
  };
}

/**
 * Build one synthetic page of accessions.
 *
 * @param overrides fields to replace for a particular paging scenario
 * @returns a synthetic accession page
 */
export function accessionPage(overrides: Partial<AccessionPage> = {}): AccessionPage {
  return {
    items: [accessionView()],
    nextCursor: null,
    ...overrides,
  };
}
