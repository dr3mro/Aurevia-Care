import type {
  CareUnit,
  CareUnitDirectoryClient,
  ConnectResult,
  ConnectionContext,
  DirectoryFixture,
  PatientUnitConnection,
  VerifiedRelationship,
} from './types';

/**
 * Whether a relationship may currently authorize a connection (MSG-01, IAM-05).
 *
 * @param relationship verified upstream relationship
 * @param now current UTC instant as an ISO-8601 string
 * @returns true only while active, unconsumed and unexpired
 */
function isUsable(relationship: VerifiedRelationship, now: string): boolean {
  if (!relationship.active || relationship.consumed) {
    return false;
  }
  return relationship.expiresAt === undefined || relationship.expiresAt > now;
}

/**
 * Select the care units a verified patient may contact.
 *
 * A directory entry alone grants nothing: a unit appears only when an active
 * verified relationship ties that exact patient to that exact unit in the same
 * organization. This blocks arbitrary directory contact and cross-unit sharing.
 *
 * @param units all care units visible to the app
 * @param relationships verified relationships for the current account
 * @param patientId currently selected verified chart
 * @param now current UTC instant as an ISO-8601 string
 * @returns eligible units in directory order
 */
export function eligibleUnits(
  units: readonly CareUnit[],
  relationships: readonly VerifiedRelationship[],
  patientId: string,
  now: string,
): readonly CareUnit[] {
  return units.filter((candidate) =>
    candidate.active && relationships.some((relationship) =>
      relationship.patientId === patientId
      && relationship.unitId === candidate.id
      && relationship.organizationId === candidate.organizationId
      && isUsable(relationship, now)));
}

/**
 * Create a synthetic, deterministic directory client for MSG-01 / ARC-07.
 *
 * It mirrors the server's verification and scoping rules so the patient flow can
 * be exercised without a backend or patient data. Production clients must use
 * the generated API client and rely on server-side authorization.
 *
 * @param fixture synthetic units, relationships and deterministic clock
 * @returns a typed directory client
 */
export function createSyntheticCareUnitClient(fixture: DirectoryFixture): CareUnitDirectoryClient {
  let relationships: readonly VerifiedRelationship[] = fixture.relationships;
  let connections: readonly PatientUnitConnection[] = [];

  function findRelationship(
    patientId: string,
    unitId: string,
    context: ConnectionContext,
    reference: string,
  ): VerifiedRelationship | undefined {
    return relationships.find((candidate) =>
      candidate.patientId === patientId
      && candidate.unitId === unitId
      && candidate.context === context
      && candidate.reference === reference
      && candidate.organizationId === fixture.organizationId
      && candidate.active);
  }

  return {
    listEligibleUnits(patientId: string): readonly CareUnit[] {
      if (!fixture.verifiedCharts.includes(patientId)) {
        return [];
      }
      // A directory listing is never an authorization: constrain the visible
      // units to the active tenant before applying relationship eligibility.
      const inTenant = fixture.units.filter((candidate) => candidate.organizationId === fixture.organizationId);
      return eligibleUnits(inTenant, relationships, patientId, fixture.now);
    },

    connect(patientId: string, unitId: string, context: ConnectionContext, reference: string): ConnectResult {
      if (!fixture.verifiedCharts.includes(patientId)) {
        return { ok: false, reason: 'UNVERIFIED_CHART' };
      }
      const unit = fixture.units.find((candidate) => candidate.id === unitId);
      if (unit === undefined) {
        return { ok: false, reason: 'UNKNOWN_UNIT' };
      }
      if (unit.organizationId !== fixture.organizationId) {
        return { ok: false, reason: 'CROSS_TENANT_UNIT' };
      }
      if (!unit.active) {
        return { ok: false, reason: 'INACTIVE_UNIT' };
      }
      const existing = connections.find((candidate) =>
        candidate.patientId === patientId
        && candidate.unitId === unitId
        && candidate.context === context
        && candidate.contextReference === reference);
      if (existing !== undefined) {
        return { ok: true, connection: existing };
      }
      const relationship = findRelationship(patientId, unitId, context, reference);
      if (relationship === undefined) {
        return { ok: false, reason: 'NO_ELIGIBLE_RELATIONSHIP' };
      }
      if (relationship.expiresAt !== undefined && relationship.expiresAt <= fixture.now) {
        return { ok: false, reason: 'EXPIRED_INVITATION' };
      }
      if (relationship.consumed) {
        return { ok: false, reason: 'CONSUMED_INVITATION' };
      }
      if (context === 'invitation') {
        relationships = relationships.map((candidate) =>
          candidate.id === relationship.id ? { ...candidate, consumed: true } : candidate);
      }
      const connection: PatientUnitConnection = {
        id: fixture.newId(),
        organizationId: fixture.organizationId,
        patientId,
        unitId,
        context,
        contextReference: reference,
        state: 'active',
        verifiedBy: 'patient-user',
        verifiedAt: fixture.now,
      };
      connections = [...connections, connection];
      return { ok: true, connection };
    },

    listConnections(patientId: string): readonly PatientUnitConnection[] {
      if (!fixture.verifiedCharts.includes(patientId)) {
        return [];
      }
      return connections.filter((candidate) => candidate.patientId === patientId);
    },
  };
}
