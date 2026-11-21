import type {
  DeadLetterWire,
  DuplicatePairWire,
  FollowUpWire,
  WorkItemWire,
  WorklistItemWire,
} from './domain';

/** Synthetic tenant identifiers; two tenants may reuse local row identifiers. */
export const alphaOrganizationId = 'synthetic-org-alpha';
export const betaOrganizationId = 'synthetic-org-beta';

/** Fixed clock so age assertions never depend on the wall clock. */
export const fixedNowMs = Date.parse('2026-03-09T12:00:00Z');

/** Synthetic exchange work item. */
export function workItem(overrides: Partial<WorkItemWire> = {}): WorkItemWire {
  return {
    id: 'work-001',
    senderOrg: 'synthetic-sender',
    destinationOrg: 'synthetic-destination',
    orderRef: 'ORD-SYN-001',
    patientRef: 'patient-ref-1',
    patientMrn: 'MRN-SYN-001',
    mrnIssuer: 'SYNTHETIC',
    localPatientId: 'SYN-0001',
    patientVerified: false,
    priority: 'routine',
    clinicalQuestion: 'synthetic question',
    workQueue: 'exchange',
    fingerprint: 'fingerprint-1',
    state: 'awaiting_local_patient',
    createdAt: '2026-03-09T11:30:00Z',
    ...overrides,
  };
}

/** Synthetic dead-letter entry. */
export function deadLetter(overrides: Partial<DeadLetterWire> = {}): DeadLetterWire {
  return {
    eventId: 'evt-001',
    organizationId: alphaOrganizationId,
    reason: 'partner unavailable',
    attempt: 3,
    failedAt: '2026-03-09T11:00:00Z',
    reviewer: null,
    reviewedAt: null,
    resolution: null,
    reviewReason: null,
    ...overrides,
  };
}

/** Synthetic overdue instruction follow-up. */
export function followUp(overrides: Partial<FollowUpWire> = {}): FollowUpWire {
  return {
    organizationId: alphaOrganizationId,
    instructionId: 'instruction-001',
    revision: 2,
    unitId: 'synthetic-unit',
    routedAt: '2026-03-09T10:45:00Z',
    state: 'overdue',
    resolvedBy: null,
    resolvedAt: null,
    reason: 'no acknowledgment',
    ...overrides,
  };
}

/** Synthetic duplicate pair; display names are deliberately not projected. */
export function duplicatePair(overrides: Partial<DuplicatePairWire> = {}): DuplicatePairWire {
  return {
    survivorId: 'SYN-0002',
    survivorName: 'synthetic survivor',
    duplicateId: 'SYN-0003',
    duplicateName: 'synthetic duplicate',
    ...overrides,
  };
}

/** Synthetic clinic worklist item. */
export function worklistItem(overrides: Partial<WorklistItemWire> = {}): WorklistItemWire {
  return {
    id: 'wl-001',
    appointmentId: 'apt-001',
    intakeReason: 'walk-in',
    patientId: 'SYN-0004',
    facilityCode: 'MAIN',
    startsAt: '2026-03-09T09:30:00Z',
    priority: 1,
    assigneeSubject: null,
    assignedAt: null,
    version: 1,
    ...overrides,
  };
}
