/**
 * Real-API client for tenant facility and staff administration (IAM-01,
 * IAM-02). Every call carries the active organization through the shared
 * request builder and returns a typed projection; no response text is ever
 * copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { AuditEvent, AuditOutcome, ConfigurationVersion, StaffHistoryEvent } from './domain';
import type { Locale } from '../../i18n/messages';
import { adminText } from './messages';

/** Facility projection returned by `GET/POST /api/v1/facilities`. */
export interface FacilityResponse {
  organizationId: string;
  code: string;
  name: string;
  timeZone?: string | null;
}

/** One cursor-paginated page of facilities. */
export interface FacilityPageResponse {
  items: readonly FacilityResponse[];
  nextCursor: string | null;
}

/** Current effective projection of one staff member. */
export interface StaffMemberResponse {
  subject: string;
  displayName: string;
  status: 'active' | 'deactivated';
  roles: readonly string[];
}

/** One cursor-paginated page of staff members. */
export interface StaffPageResponse {
  items: readonly StaffMemberResponse[];
  nextCursor: string | null;
}

/** One immutable membership event from the staff history endpoint. */
export interface StaffMembershipEvent {
  id: string;
  status: 'active' | 'revoked';
  validFrom: string;
  validTo: string | null;
  createdAt: string;
}

/** One immutable role grant from the staff history endpoint. */
export interface StaffRoleGrant {
  id: string;
  roleKey: string;
  grantedBy: string;
  validFrom: string;
  validTo: string | null;
}

/** Append-only membership and role history returned by the real API. */
export interface StaffHistoryResponse {
  subject: string;
  memberships: readonly StaffMembershipEvent[];
  roleGrants: readonly StaffRoleGrant[];
}

/** Fields of the facility-create form. */
export interface FacilityDraft {
  code: string;
  name: string;
}

/** Fields of the staff-invite form. */
export interface InviteDraft {
  subject: string;
  displayName: string;
  role: string;
}

/** Fields of either administration form. */
export type AdminField = 'code' | 'name' | 'subject' | 'displayName' | 'role';

/** Localized validation message keys used by both administration forms. */
export type ValidationKey =
  | 'errorFacilityCode'
  | 'errorFacilityName'
  | 'errorStaffSubject'
  | 'errorStaffDisplayName'
  | 'errorStaffRole';

/** Field-keyed validation result for one form submission. */
export type FieldErrors<Field extends AdminField> = Partial<Record<Field, ValidationKey>>;

const FACILITY_CODE_PATTERN = /^[A-Z0-9_-]{2,32}$/;
const STAFF_SUBJECT_PATTERN = /^[a-z0-9][a-z0-9._-]{1,63}$/;
const BOUNDED_ROLES: readonly string[] = ['tenant_admin', 'reception', 'privacy_auditor'];

/** Map every form field to the validation key that explains it. */
const VALIDATION_KEY_BY_FIELD: Record<AdminField, ValidationKey> = {
  code: 'errorFacilityCode',
  name: 'errorFacilityName',
  subject: 'errorStaffSubject',
  displayName: 'errorStaffDisplayName',
  role: 'errorStaffRole',
};

/** Map server validation codes to the facility field that caused them. */
const FACILITY_FIELD_BY_CODE: Readonly<Record<string, 'code' | 'name'>> = {
  INVALID_CODE: 'code',
  INVALID_NAME: 'name',
  INVALID_TIME_ZONE: 'name',
};

/** Map server validation codes to the staff field that caused them. */
const STAFF_FIELD_BY_CODE: Readonly<Record<string, 'subject' | 'displayName' | 'role'>> = {
  INVALID_SUBJECT: 'subject',
  INVALID_DISPLAY_NAME: 'displayName',
  INVALID_ROLE: 'role',
};

/**
 * Resolve the localized validation key for a field.
 *
 * @param field field that failed validation
 * @returns the message key a summary can localize
 */
export function validationKeyForField(field: AdminField): ValidationKey {
  return VALIDATION_KEY_BY_FIELD[field];
}

/**
 * Resolve the facility field named by a server validation code.
 *
 * @param code machine-readable error code, or `undefined` when absent
 * @returns the invalid field, or `undefined` for an unmapped code
 */
export function facilityFieldForCode(code: string | undefined): 'code' | 'name' | undefined {
  return FACILITY_FIELD_BY_CODE[code ?? ''];
}

/**
 * Resolve the staff field named by a server validation code.
 *
 * @param code machine-readable error code, or `undefined` when absent
 * @returns the invalid field, or `undefined` for an unmapped code
 */
export function staffFieldForCode(
  code: string | undefined,
): 'subject' | 'displayName' | 'role' | undefined {
  return STAFF_FIELD_BY_CODE[code ?? ''];
}

/**
 * Validate a facility draft at the trust boundary. The code format and name
 * length mirror the facility contract; no tenant or author is accepted here.
 */
export function validateFacility(draft: FacilityDraft): FieldErrors<'code' | 'name'> {
  const errors: FieldErrors<'code' | 'name'> = {};
  if (!FACILITY_CODE_PATTERN.test(draft.code)) {
    errors.code = 'errorFacilityCode';
  }
  if (draft.name.trim().length < 1 || draft.name.length > 200) {
    errors.name = 'errorFacilityName';
  }
  return errors;
}

/**
 * Validate an invitation before a command is sent. The bounded roles match the
 * invitation contract; an unknown role is refused rather than guessed.
 */
export function validateInvite(
  draft: InviteDraft,
): FieldErrors<'subject' | 'displayName' | 'role'> {
  const errors: FieldErrors<'subject' | 'displayName' | 'role'> = {};
  if (!STAFF_SUBJECT_PATTERN.test(draft.subject)) {
    errors.subject = 'errorStaffSubject';
  }
  if (draft.displayName.trim().length < 1 || draft.displayName.length > 200) {
    errors.displayName = 'errorStaffDisplayName';
  }
  if (!BOUNDED_ROLES.includes(draft.role)) {
    errors.role = 'errorStaffRole';
  }
  return errors;
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asAdminTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Build a GET descriptor with only the query parameters that were supplied. */
function listDescriptor(
  path: '/api/v1/facilities' | '/api/v1/staff',
  query: Readonly<Record<string, string | undefined>>,
  organizationId: string,
): RequestDescriptor {
  const base = buildRequestDescriptor('GET', path, { activeOrganization: organizationId });
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(key, value);
    }
  }
  const suffix = params.toString();
  return suffix === '' ? base : { ...base, url: `${base.url}?${suffix}` };
}

/** Build a POST descriptor for any contract path, keeping the shared metadata. */
function postDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', '/api/v1/facilities', meta, body);
  return { ...base, url: path };
}

/** Build a POST descriptor with a fresh idempotency key for one user action. */
function commandDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  return postDescriptor(path, meta, body);
}

/** Build a GET descriptor for a contract path template with a concrete suffix. */
function getDescriptor(
  pathTemplate: Parameters<typeof buildRequestDescriptor>[1],
  path: string,
  organizationId: string,
): RequestDescriptor {
  const base = buildRequestDescriptor('GET', pathTemplate, {
    activeOrganization: organizationId,
  });
  return { ...base, url: path };
}

/**
 * Read one page of facilities in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listFacilities(
  cursor: string | undefined,
  organizationId: string,
  limit?: number,
): Promise<FacilityPageResponse> {
  return send<FacilityPageResponse>(
    listDescriptor(
      '/api/v1/facilities',
      { cursor, limit: limit === undefined ? undefined : String(limit) },
      organizationId,
    ),
  );
}

/** Create a facility in the active organization. */
export async function createFacility(
  draft: FacilityDraft,
  organizationId: string,
): Promise<FacilityResponse> {
  return send<FacilityResponse>(
    commandDescriptor(
      '/api/v1/facilities',
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      { code: draft.code, name: draft.name },
    ),
  );
}

/**
 * Read one page of staff members in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listStaff(
  cursor: string | undefined,
  status: string | undefined,
  organizationId: string,
): Promise<StaffPageResponse> {
  return send<StaffPageResponse>(
    listDescriptor('/api/v1/staff', { cursor, status }, organizationId),
  );
}

/** Invite a staff member with one bounded role grant. */
export async function inviteStaff(
  draft: InviteDraft,
  organizationId: string,
): Promise<StaffMemberResponse> {
  return send<StaffMemberResponse>(
    commandDescriptor(
      '/api/v1/staff',
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      { subject: draft.subject, displayName: draft.displayName, role: draft.role },
    ),
  );
}

/** Append a revoked membership event for one staff subject. */
export async function deactivateStaff(
  subject: string,
  organizationId: string,
): Promise<StaffMemberResponse> {
  return send<StaffMemberResponse>(
    commandDescriptor(`/api/v1/staff/${encodeURIComponent(subject)}/deactivate`, {
      activeOrganization: organizationId,
      idempotencyKey: crypto.randomUUID(),
    }),
  );
}

/** Read the immutable membership and role history of one staff subject. */
export async function getStaffHistory(
  subject: string,
  organizationId: string,
): Promise<StaffHistoryResponse> {
  const base = buildRequestDescriptor('GET', '/api/v1/staff/{subject}/history', {
    activeOrganization: organizationId,
  });
  return send<StaffHistoryResponse>({
    ...base,
    url: `/api/v1/staff/${encodeURIComponent(subject)}/history`,
  });
}

/**
 * Convert the API history projection into the existing append-only domain
 * events used by `StaffHistoryView`. The API has no deactivation-reason field,
 * so a revoked event carries an explicit localized fallback rather than an
 * empty cell.
 */
export function toStaffHistoryEvents(
  history: StaffHistoryResponse,
  organizationId: string,
  locale: Locale,
): StaffHistoryEvent[] {
  const events: StaffHistoryEvent[] = [];
  let sequence = 0;
  for (const membership of history.memberships) {
    sequence += 1;
    const base = {
      organizationId,
      subject: history.subject,
      sequence,
      actor: adminText(locale, 'systemActor'),
      effectiveFrom: membership.validFrom,
    };
    if (membership.status === 'revoked') {
      events.push({
        ...base,
        kind: 'deactivated',
        reason: adminText(locale, 'reasonNotAvailable'),
      });
    } else {
      events.push({ ...base, kind: 'invited' });
    }
  }
  for (const grant of history.roleGrants) {
    sequence += 1;
    events.push({
      organizationId,
      subject: history.subject,
      sequence,
      kind: 'role_granted',
      actor: grant.grantedBy,
      effectiveFrom: grant.validFrom,
      role: grant.roleKey,
    });
  }
  return events;
}

/** One immutable configuration version as returned by the real API (ADM-01). */
export interface ConfigEntryResponse {
  key?: string;
  version?: number;
  value?: string;
  status?: 'pending' | 'approved';
  effectiveFrom?: string | null;
  createdBy?: string | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
  note?: string | null;
}

/** Append-only configuration history as returned by the real API. */
export interface ConfigHistoryResponse {
  key?: string;
  entries?: readonly ConfigEntryResponse[];
}

/** One effective catalog entry as returned by the real API. */
export interface CatalogEntryResponse {
  family?: string;
  code?: string;
  version?: number;
  value?: string;
  effectiveFrom?: string;
  approvedBy?: string;
  approvedAt?: string;
  note?: string | null;
}

/** One append-only audit event as returned by the real API (OPS-02). */
export interface AuditEventResponse {
  id?: string;
  occurredAt?: string;
  actorId?: string | null;
  action?: string;
  resourceType?: string | null;
  resourceId?: string | null;
  purpose?: string | null;
  scope?: string | null;
  correlationId?: string | null;
  outcome?: string;
  seq?: number;
}

/** One cursor-paginated page of audit events. */
export interface AuditPageResponse {
  events?: readonly AuditEventResponse[];
  nextCursor?: string | null;
}

/** Fields sent to define one new configuration version. */
export interface ConfigDraft {
  key: string;
  value: string;
  /** UTC instant; omitted for a draft that takes effect on approval. */
  effectiveFrom?: string;
}

/** Fields sent to define one new catalog entry version. */
export interface CatalogDraft {
  code: string;
  value: string;
  /** UTC instant; omitted for a draft that takes effect on approval. */
  effectiveFrom?: string;
}

/** Validated query filters for the real audit endpoint. */
export interface AuditFilters {
  cursor?: string;
  limit?: number;
  action?: string;
  outcome?: string;
  resourceType?: string;
  from?: string;
  to?: string;
}

/**
 * Build the audit query string in contract order, omitting absent, empty and
 * zero-valued filters. Values are encoded once here so no caller concatenates
 * untrusted filter text into a URL.
 */
export function auditQueryString(filters: AuditFilters): string {
  const values: readonly (readonly [string, string | number | undefined])[] = [
    ['cursor', filters.cursor],
    ['limit', filters.limit],
    ['action', filters.action],
    ['outcome', filters.outcome],
    ['resourceType', filters.resourceType],
    ['from', filters.from],
    ['to', filters.to],
  ];
  const params = new URLSearchParams();
  for (const [name, value] of values) {
    if (value !== undefined && value !== '' && value !== 0) {
      params.set(name, String(value));
    }
  }
  return params.toString();
}

/** Validate a tenant-local configuration key before it is placed in a path. */
export function isValidConfigKey(value: string): boolean {
  return /^[A-Za-z0-9_.:-]{2,200}$/.test(value.trim());
}

/** Validate a catalog family against the contract's family pattern. */
export function isValidCatalogFamily(value: string): boolean {
  return /^[a-z][a-z0-9_]{1,31}$/.test(value.trim());
}

/** Accept only a real UTC instant so a bad filter never reaches the API or Intl. */
export function isValidUtcInstant(value: string): boolean {
  const trimmed = value.trim();
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(trimmed)) {
    return false;
  }
  const parsed = new Date(trimmed);
  if (Number.isNaN(parsed.getTime())) {
    return false;
  }
  return parsed.toISOString().startsWith(trimmed.slice(0, 19));
}

/** Collapse the four documented API outcomes onto the domain's outcome union. */
export function normalizeAuditOutcome(outcome: string | undefined): AuditOutcome {
  const upper = outcome?.toUpperCase();
  if (upper === 'SUCCESS' || upper === 'NOT_FOUND' || upper === 'CONFLICT') {
    return upper;
  }
  // DENIED is the safe presentation for an absent or unrecognised outcome: the
  // row is still shown, and it is never described as a successful action.
  return 'DENIED';
}

/** Read the one effective configuration entry for a key. */
export async function getConfiguration(
  key: string,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  return send<ConfigEntryResponse>(
    getDescriptor(
      '/api/v1/configuration/{key}',
      `/api/v1/configuration/${encodeURIComponent(key)}`,
      organizationId,
    ),
  );
}

/** Read the append-only version and approval history for a key. */
export async function getConfigurationHistory(
  key: string,
  organizationId: string,
): Promise<ConfigHistoryResponse> {
  return send<ConfigHistoryResponse>(
    getDescriptor(
      '/api/v1/configuration/{key}/history',
      `/api/v1/configuration/${encodeURIComponent(key)}/history`,
      organizationId,
    ),
  );
}

/** Append a new configuration draft; organization and author stay server-side. */
export async function defineConfiguration(
  draft: ConfigDraft,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  const body: Record<string, unknown> = { key: draft.key, value: draft.value };
  if (draft.effectiveFrom !== undefined) {
    body.effectiveFrom = draft.effectiveFrom;
  }
  return send<ConfigEntryResponse>(
    postDescriptor(
      '/api/v1/configuration',
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      body,
    ),
  );
}

/** Append a named approval for one immutable configuration version. */
export async function approveConfiguration(
  key: string,
  version: number,
  note: string,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  return send<ConfigEntryResponse>(
    postDescriptor(
      `/api/v1/configuration/${encodeURIComponent(key)}/versions/${version}/approve`,
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      { note },
    ),
  );
}

/** List the effective entries of one catalog family. */
export async function listCatalogFamily(
  family: string,
  organizationId: string,
): Promise<CatalogEntryResponse[]> {
  return send<CatalogEntryResponse[]>(
    getDescriptor(
      '/api/v1/catalogs/{family}',
      `/api/v1/catalogs/${encodeURIComponent(family)}`,
      organizationId,
    ),
  );
}

/** Append a pending version to one catalog family. */
export async function defineCatalogEntry(
  family: string,
  draft: CatalogDraft,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  const body: Record<string, unknown> = { code: draft.code, value: draft.value };
  if (draft.effectiveFrom !== undefined) {
    body.effectiveFrom = draft.effectiveFrom;
  }
  return send<ConfigEntryResponse>(
    postDescriptor(
      `/api/v1/catalogs/${encodeURIComponent(family)}/entries`,
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      body,
    ),
  );
}

/** Query one cursor-paginated page of the active tenant's audit trail. */
export async function queryAuditEvents(
  filters: AuditFilters,
  organizationId: string,
): Promise<AuditPageResponse> {
  const base = getDescriptor('/api/v1/audit-events', '/api/v1/audit-events', organizationId);
  const suffix = auditQueryString(filters);
  return send<AuditPageResponse>(suffix === '' ? base : { ...base, url: `${base.url}?${suffix}` });
}

/**
 * Map the API history into the civil-date domain versions the editor displays.
 * An API instant is truncated to its UTC civil day so `effectiveVersion` can
 * compare like with like; a missing instant becomes an explicit empty string,
 * which the editor renders as "not applicable" rather than an invalid date.
 */
export function configurationVersionsFromHistory(
  history: ConfigHistoryResponse,
  organizationId: string,
): ConfigurationVersion[] {
  const key = history.key ?? '';
  return (history.entries ?? []).map((entry) => ({
    organizationId,
    key: entry.key ?? key,
    facilityCode: key,
    version: entry.version ?? 0,
    value: entry.value ?? '',
    effectiveFrom:
      entry.effectiveFrom === null || entry.effectiveFrom === undefined
        ? ''
        : entry.effectiveFrom.slice(0, 10),
    approved: entry.status === 'approved',
    ...(entry.approvedBy === null || entry.approvedBy === undefined
      ? {}
      : { approver: entry.approvedBy }),
  }));
}

/** Map one API audit page into the immutable rows the existing list renders. */
export function auditEventsFromPage(
  page: AuditPageResponse,
  organizationId: string,
  locale: Locale,
): AuditEvent[] {
  return (page.events ?? []).map((event) => ({
    organizationId,
    sequence: event.seq ?? 0,
    actor: event.actorId ?? adminText(locale, 'systemActor'),
    purpose: event.purpose ?? adminText(locale, 'unknown'),
    scope: event.scope ?? adminText(locale, 'unknown'),
    correlationId: event.correlationId ?? adminText(locale, 'unknown'),
    action: event.action ?? adminText(locale, 'unknown'),
    resourceId: event.resourceId ?? adminText(locale, 'unknown'),
    outcome: normalizeAuditOutcome(event.outcome),
    occurredAt: event.occurredAt ?? '',
    // The real API does not project the hash chain. The list is told not to
    // render a chain verdict for these rows instead of inventing one.
    previousHash: '',
    hash: '',
  }));
}
