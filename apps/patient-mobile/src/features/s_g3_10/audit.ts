import type {
  CopyRequest,
  GovernanceClient,
  GovernanceFixture,
  LegalHold,
  MessagingAuditAction,
  MessagingAuditEvent,
  MessagingAuditOutcome,
  ProvenanceLink,
} from './types';

/** Milliseconds in one civil day, used for retention windows. */
const DAY_MS = 86_400_000;

/**
 * Find the active legal hold covering a resource in the current organization.
 *
 * @param fixture synthetic governance fixture
 * @param resourceId resource to check
 * @returns the active hold, or undefined when none applies
 */
export function activeHold(fixture: GovernanceFixture, resourceId: string): LegalHold | undefined {
  return fixture.legalHolds.find((hold) =>
    hold.organizationId === fixture.organizationId
    && hold.resourceId === resourceId
    && hold.active);
}

/**
 * Decide whether a resource has passed its retention window without a hold.
 *
 * @param fixture synthetic governance fixture
 * @param resourceId resource to check
 * @param now current instant as an ISO-8601 string
 * @returns true only when a policy exists, the window passed and no hold applies
 */
export function retentionEligible(
  fixture: GovernanceFixture,
  resourceId: string,
  now: string = fixture.now,
): boolean {
  const resource = fixture.resources.find((candidate) =>
    candidate.organizationId === fixture.organizationId && candidate.id === resourceId);
  if (resource === undefined || activeHold(fixture, resourceId) !== undefined) {
    return false;
  }
  const policy = fixture.retentionPolicies.find((candidate) =>
    candidate.organizationId === fixture.organizationId
    && candidate.resourceType === resource.resourceType);
  if (policy === undefined) {
    return false;
  }
  const expiresAt = new Date(resource.createdAt).getTime() + policy.retentionDays * DAY_MS;
  return expiresAt <= new Date(now).getTime();
}

/**
 * Check whether an attachment may be opened through a connection.
 *
 * A revoked connection blocks future attachment access even when the file bytes
 * still exist in mediated storage (MSG-07, ARC-07).
 *
 * @param fixture synthetic governance fixture
 * @param connectionId connection used for the access
 * @returns true only for an active connection in the current organization
 */
export function canAccessAttachment(fixture: GovernanceFixture, connectionId: string): boolean {
  const connection = fixture.connections.find((candidate) =>
    candidate.organizationId === fixture.organizationId && candidate.id === connectionId);
  return connection !== undefined && connection.revokedAt === undefined;
}

/**
 * Read whether earlier conversations remain readable under the approved policy.
 *
 * @param fixture synthetic governance fixture
 * @returns the patient record-access readability decision
 */
export function priorConversationsReadable(fixture: GovernanceFixture): boolean {
  return fixture.recordAccessPolicy.organizationId === fixture.organizationId
    && fixture.recordAccessPolicy.priorConversationsReadable;
}

/**
 * Create a synthetic, deterministic governance client for MSG-07.
 *
 * It mirrors the server's audit and scoping rules so the patient flow can be
 * exercised without a backend or patient data. Production clients must rely on
 * server-side authorization for every action.
 *
 * @param fixture synthetic governance data with a deterministic clock and ID generator
 * @returns a typed governance client
 */
export function createSyntheticGovernanceClient(fixture: GovernanceFixture): GovernanceClient {
  let events: readonly MessagingAuditEvent[] = [...fixture.auditEvents];

  function append(action: MessagingAuditAction, resourceId: string, outcome: MessagingAuditOutcome): void {
    events = [...events, {
      id: fixture.newId(),
      organizationId: fixture.organizationId,
      subject: 'patient-user',
      purpose: 'MSG_GOVERNANCE',
      action,
      resourceId,
      unitId: 'MESSAGING',
      correlationId: 'corr-1',
      outcome,
      occurredAt: fixture.now,
    }];
  }

  return {
    auditTrail(resourceId?: string): readonly MessagingAuditEvent[] {
      const resource = resourceId ?? 'AUDIT';
      append('audit_read', resource, 'success');
      const result = resourceId === undefined
        ? events
        : events.filter((event) => event.resourceId === resourceId);
      return [...result];
    },

    canAccessAttachment(connectionId: string): boolean {
      const allowed = canAccessAttachment(fixture, connectionId);
      append('download', connectionId, allowed ? 'success' : 'denied');
      return allowed;
    },

    requestCopy(resourceId: string, purpose: string): CopyRequest {
      const request: CopyRequest = {
        id: fixture.newId(),
        organizationId: fixture.organizationId,
        resourceId,
        requestedBy: 'patient-user',
        purpose,
        status: 'ROUTED',
        createdAt: fixture.now,
      };
      append('copy_request', resourceId, 'success');
      return request;
    },

    provenanceLinks(messageId: string): readonly ProvenanceLink[] {
      const links = fixture.provenanceLinks.filter((link) =>
        link.organizationId === fixture.organizationId && link.messageId === messageId);
      append('view', messageId, links.length === 0 ? 'not_found' : 'success');
      return [...links];
    },
  };
}
