/**
 * MSG-01 / ARC-07 domain types for the patient care-unit directory.
 *
 * Every relationship here is independently verified by the server. Demographic
 * similarity, a shared phone number or a directory listing never establishes a
 * connection. These types are transport-safe projections of the API contract.
 */

/** Verified upstream relationship kinds that can authorize one connection. */
export type ConnectionContext =
  | 'care_relationship'
  | 'order'
  | 'referral'
  | 'prescription'
  | 'invitation';

/** A staffed care unit a verified patient may be eligible to contact (MSG-01). */
export interface CareUnit {
  readonly id: string;
  readonly organizationId: string;
  readonly facilityId: string;
  readonly name: string;
  readonly topics: readonly string[];
  readonly active: boolean;
}

/** An independently verified relationship tying a patient to one unit. */
export interface VerifiedRelationship {
  readonly id: string;
  readonly organizationId: string;
  readonly patientId: string;
  readonly unitId: string;
  readonly context: ConnectionContext;
  readonly reference: string;
  readonly active: boolean;
  readonly expiresAt?: string;
  readonly consumed: boolean;
}

/** A verified, connection-scoped patient-unit relationship (ARC-07). */
export interface PatientUnitConnection {
  readonly id: string;
  readonly organizationId: string;
  readonly patientId: string;
  readonly unitId: string;
  readonly context: ConnectionContext;
  readonly contextReference: string;
  readonly state: 'active' | 'revoked';
  readonly verifiedBy: string;
  readonly verifiedAt: string;
}

/** Machine-readable reason a connection was not granted. */
export type ConnectDenialReason =
  | 'UNVERIFIED_CHART'
  | 'UNKNOWN_UNIT'
  | 'CROSS_TENANT_UNIT'
  | 'INACTIVE_UNIT'
  | 'NO_ELIGIBLE_RELATIONSHIP'
  | 'EXPIRED_INVITATION'
  | 'CONSUMED_INVITATION';

/** Result of a connect command: either one connection or a stable denial reason. */
export type ConnectResult =
  | { readonly ok: true; readonly connection: PatientUnitConnection }
  | { readonly ok: false; readonly reason: ConnectDenialReason };

/** Synthetic fixture input for the in-memory directory client. */
export interface DirectoryFixture {
  readonly organizationId: string;
  /** Charts independently verified for this account (IAM-05). */
  readonly verifiedCharts: readonly string[];
  readonly units: readonly CareUnit[];
  readonly relationships: readonly VerifiedRelationship[];
  /** Current UTC instant used for invitation expiry, as an ISO-8601 string. */
  readonly now: string;
  /** Injected identifier generator so tests remain deterministic. */
  readonly newId: () => string;
}

/** Typed boundary for the patient directory and connection commands. */
export interface CareUnitDirectoryClient {
  listEligibleUnits(patientId: string): readonly CareUnit[];
  connect(
    patientId: string,
    unitId: string,
    context: ConnectionContext,
    reference: string,
  ): ConnectResult;
  listConnections(patientId: string): readonly PatientUnitConnection[];
}
