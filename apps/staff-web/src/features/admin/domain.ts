/**
 * Pure domain rules for the synthetic staff-web administration screens
 * (ADM-02, OPS-02). Every function is tenant-aware and deterministic: the
 * screens never contact an endpoint and never see real identities.
 */

/** Roles allowed to review the audit trail and access state (IAM-01, OPS-02). */
export type AdminRole = 'tenant_admin' | 'privacy_auditor' | 'reception';

/** The three observable access states of a resource review (IAM-01). */
export type AccessState = 'permitted' | 'denied' | 'concealed';

/** Machine-readable outcome carried by every audit row (OPS-02). */
export type AuditOutcome = 'SUCCESS' | 'DENIED' | 'NOT_FOUND' | 'CONFLICT';

/** Roles whose active members may review audit and access state. */
export const AUDIT_REVIEW_ROLES: ReadonlySet<AdminRole> = new Set<AdminRole>([
  'tenant_admin',
  'privacy_auditor',
]);

/** A synthetic authenticated viewer and the organization they are active in. */
export interface ViewerPermission {
  /** Tenant derived from the active organization switch, never from a body. */
  organizationId: string;
  roles: readonly AdminRole[];
  /** False once membership is revoked; applies to the very next review. */
  activeMembership: boolean;
}

/** One fixture-driven access review shown by the screen. */
export interface AccessProbe {
  id: string;
  label: string;
  resourceOrganizationId: string;
  viewer: ViewerPermission;
}

/**
 * Resolve access to a resource. Existence outside the active tenant is always
 * concealed; same-tenant access requires both active membership and a role.
 */
export function resolveAccess(
  viewer: ViewerPermission,
  resourceOrganizationId: string,
): AccessState {
  if (resourceOrganizationId !== viewer.organizationId) {
    return 'concealed';
  }
  if (!viewer.activeMembership || !viewer.roles.some((role) => AUDIT_REVIEW_ROLES.has(role))) {
    return 'denied';
  }
  return 'permitted';
}

/** One immutable, versioned tenant configuration entry (ADM-01 display). */
export interface ConfigurationVersion {
  organizationId: string;
  key: string;
  facilityCode: string;
  version: number;
  value: string;
  /** Civil date YYYY-MM-DD; compared as a string at UTC midnight. */
  effectiveFrom: string;
  approved: boolean;
  approver?: string;
}

/** Local editor input for a proposed configuration version. */
export interface ConfigurationDraft {
  value: string;
  effectiveFrom: string;
}

/**
 * Accept only a real calendar date in YYYY-MM-DD form so the UI never hands
 * an invalid instant to `Intl.DateTimeFormat` (a RangeError crash path).
 */
export function isValidCivilDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    return false;
  }
  return parsed.toISOString().slice(0, 10) === value;
}

/** The single tenant boundary shared by every admin selection rule. */
function forTenant<T extends { organizationId: string }>(
  rows: readonly T[],
  organizationId: string,
): T[] {
  return rows.filter((row) => row.organizationId === organizationId);
}

/** Return one tenant's versions for a key, newest version first. */
export function versionsFor(
  entries: readonly ConfigurationVersion[],
  organizationId: string,
  key: string,
): ConfigurationVersion[] {
  return forTenant(entries, organizationId)
    .filter((entry) => entry.key === key)
    .sort((left, right) => {
      const byVersion = right.version - left.version;
      return byVersion === 0 ? right.effectiveFrom.localeCompare(left.effectiveFrom) : byVersion;
    });
}

/** Return the highest approved version already effective on the anchor date. */
export function effectiveVersion(
  entries: readonly ConfigurationVersion[],
  organizationId: string,
  key: string,
  onCivilDate: string,
): ConfigurationVersion | undefined {
  return versionsFor(entries, organizationId, key).find(
    (entry) => entry.approved && entry.effectiveFrom <= onCivilDate,
  );
}

/** Allocate the next tenant-local version; an unseen key starts at one. */
export function nextVersion(
  entries: readonly ConfigurationVersion[],
  organizationId: string,
  key: string,
): number {
  return (
    forTenant(entries, organizationId)
      .filter((entry) => entry.key === key)
      .reduce((maximum, entry) => Math.max(maximum, entry.version), 0) + 1
  );
}

/** Build a pending draft bound to the requesting tenant and facility. */
export function proposeVersion(
  entries: readonly ConfigurationVersion[],
  organizationId: string,
  target: { key: string; facilityCode: string },
  draft: ConfigurationDraft,
): ConfigurationVersion {
  const { key, facilityCode } = target;
  return {
    organizationId,
    key,
    facilityCode,
    version: nextVersion(entries, organizationId, key),
    value: draft.value,
    effectiveFrom: draft.effectiveFrom,
    approved: false,
  };
}

/** Shared fields of every append-only staff history event. */
export interface StaffEventBase {
  organizationId: string;
  subject: string;
  sequence: number;
  actor: string;
  effectiveFrom: string;
}

/** An append-only staff lifecycle event with role- and reason-specific data. */
export type StaffHistoryEvent =
  | (StaffEventBase & { kind: 'invited' })
  | (StaffEventBase & { kind: 'role_granted'; role: string })
  | (StaffEventBase & { kind: 'deactivated'; reason: string });

/** Narrow a history event to a role grant. */
function isRoleGrant(
  event: StaffHistoryEvent,
): event is Extract<StaffHistoryEvent, { kind: 'role_granted' }> {
  return event.kind === 'role_granted';
}

/** Return one tenant-local subject's events in append order. */
export function historyFor(
  events: readonly StaffHistoryEvent[],
  organizationId: string,
  subject: string,
): StaffHistoryEvent[] {
  return forTenant(events, organizationId)
    .filter((event) => event.subject === subject)
    .sort((left, right) => left.sequence - right.sequence);
}

/** True when any append-only event deactivated the subject. */
export function isDeactivated(events: readonly StaffHistoryEvent[]): boolean {
  return events.some((event) => event.kind === 'deactivated');
}

/** Roles currently in force; a deactivation clears every earlier grant. */
export function currentRoles(events: readonly StaffHistoryEvent[]): string[] {
  if (isDeactivated(events)) {
    return [];
  }
  return events.filter(isRoleGrant).map((event) => event.role);
}

/** Deterministic synthetic hash seed; the first event links to this value. */
export const ADMIN_CHAIN_GENESIS = '00000000';

/** Hashable portion of an audit event, excluding its own stored hash. */
export interface AuditEventFields {
  organizationId: string;
  sequence: number;
  actor: string;
  purpose: string;
  scope: string;
  correlationId: string;
  action: string;
  resourceId: string;
  outcome: AuditOutcome;
  occurredAt: string;
  previousHash: string;
}

/** A stored synthetic audit event with its chain-link hash. */
export interface AuditEvent extends AuditEventFields {
  hash: string;
}

/** Canonical field order for the synthetic chain check (OPS-02). */
export function chainPayload(event: AuditEventFields): string {
  return [
    event.organizationId,
    event.sequence,
    event.actor,
    event.purpose,
    event.scope,
    event.correlationId,
    event.action,
    event.resourceId,
    event.outcome,
    event.occurredAt,
    event.previousHash,
  ].join('|');
}

/** A deterministic non-cryptographic FNV-1a hash for synthetic fixtures only. */
export function recomputeHash(event: AuditEventFields): string {
  const payload = chainPayload(event);
  let hash = 0x811c9dc5;
  for (let index = 0; index < payload.length; index += 1) {
    hash ^= payload.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}

/** Return one tenant's audit rows in ascending sequence order. */
export function adminChainFor(events: readonly AuditEvent[], organizationId: string): AuditEvent[] {
  return forTenant(events, organizationId).sort((left, right) => left.sequence - right.sequence);
}

/**
 * Verify every stored hash and every link of a synthetic chain. An empty
 * chain cannot be verified and therefore shows no verified badge.
 */
export function verifyChain(events: readonly AuditEvent[]): boolean {
  if (events.length === 0) {
    return false;
  }
  let previous = ADMIN_CHAIN_GENESIS;
  for (const event of events) {
    if (event.previousHash !== previous || event.hash !== recomputeHash(event)) {
      return false;
    }
    previous = event.hash;
  }
  return true;
}
