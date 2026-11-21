/**
 * Real-API client for tenant facility and staff administration (IAM-01,
 * IAM-02). Every call carries the active organization through the shared
 * request builder and returns a typed projection; no response text is ever
 * copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { StaffHistoryEvent } from './domain';
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
export function validateInvite(draft: InviteDraft): FieldErrors<'subject' | 'displayName' | 'role'> {
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

/** Build a POST descriptor with a fresh idempotency key for one user action. */
function commandDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', '/api/v1/facilities', meta, body);
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
    commandDescriptor(
      `/api/v1/staff/${encodeURIComponent(subject)}/deactivate`,
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
    ),
  );
}

/** Read the immutable membership and role history of one staff subject. */
export async function getStaffHistory(
  subject: string,
  organizationId: string,
): Promise<StaffHistoryResponse> {
  const base = buildRequestDescriptor(
    'GET',
    '/api/v1/staff/{subject}/history',
    { activeOrganization: organizationId },
  );
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
      events.push({ ...base, kind: 'deactivated', reason: adminText(locale, 'reasonNotAvailable') });
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
