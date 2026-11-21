/**
 * Pure domain rules for the staff-web administration of templates, field
 * dictionaries and patient release policies (ADM-01, CLN-04, XCH-06).
 *
 * Every row is tenant scoped and every decision is a deterministic function of
 * explicit inputs. No function here reads the clock, the network or a browser
 * store, so the same rules can be exercised with synthetic fixtures only.
 */

import { isValidCivilDate } from '../admin/domain';

/** Template families approved for site use (CLN-04). */
export type TemplateKind = 'soap' | 'operative' | 'obgyn' | 'perinatal' | 'pediatric-development';

/** Patient release modes supported by the policy editor (XCH-06). */
export type ReleaseMode = 'immediate' | 'timed' | 'hold';

/** Approval state derived from the presence of a named approval (ADM-01). */
export type ApprovalState = 'pending' | 'approved';

/** Observed patient-access state derived from an approved release policy. */
export type ReleaseAccess = 'released' | 'scheduled' | 'held' | 'review';

/**
 * One immutable version of a template, field dictionary entry or release
 * policy. `value`, `reason` and `expiry` are opaque bounded text; no clinical
 * payload is stored in this client.
 */
export interface PolicyVersion {
  organizationId: string;
  /** Business key: template kind, dictionary code or release-policy scope. */
  key: string;
  version: number;
  value: string;
  /** Civil date `YYYY-MM-DD`, or empty when no effective date was recorded. */
  effectiveFrom: string;
  approval: ApprovalState;
  /** Named clinical approver; absent while the version is pending. */
  approver?: string;
  /** Approver named on the proposed change, carried in the opaque value. */
  proposedBy?: string;
  /** Release mode, present only for release-policy versions. */
  mode?: ReleaseMode;
  /** Reason recorded for a held release. */
  reason?: string;
  /** Civil date on which a timed release starts or a hold is reviewed. */
  expiry?: string;
}

/** Fields the policy editor can refuse. */
export type PolicyField = 'code' | 'value' | 'approver' | 'effectiveFrom' | 'reason' | 'expiry';

/** Localized message keys describing each refused field. */
export type PolicyValidationKey =
  | 'codeRequired'
  | 'valueRequired'
  | 'valueTooLong'
  | 'approverRequired'
  | 'effectiveDateRequired'
  | 'effectiveDateInvalid'
  | 'reasonRequired'
  | 'expiryInvalid';

/** Field-keyed validation result; an empty object means the change is valid. */
export type PolicyErrors = Partial<Record<PolicyField, PolicyValidationKey>>;

/** Draft collected by the policy editor before it is sent to the API. */
export interface PolicyChangeDraft {
  /** Dictionary field code; required only for a field dictionary change. */
  code: string;
  value: string;
  /** Named approver required for a clinical or market policy (ADM-01). */
  approver: string;
  effectiveFrom: string;
  mode: ReleaseMode;
  reason: string;
  expiry: string;
}

/** An empty draft; the editor always starts from this value. */
export const EMPTY_POLICY_DRAFT: PolicyChangeDraft = {
  code: '',
  value: '',
  approver: '',
  effectiveFrom: '',
  mode: 'immediate',
  reason: '',
  expiry: '',
};

/**
 * Return one tenant's versions for a business key, newest version first.
 * A row from another tenant is never returned, even with an identical key.
 */
export function versionsFor(
  versions: readonly PolicyVersion[],
  organizationId: string,
  key: string,
): PolicyVersion[] {
  return versions
    .filter((row) => row.organizationId === organizationId && row.key === key)
    .sort((left, right) => right.version - left.version);
}

/**
 * Return the highest approved version already effective on a civil date.
 * Pending, future-dated and undated versions never become effective early.
 */
export function effectiveVersion(
  versions: readonly PolicyVersion[],
  organizationId: string,
  key: string,
  onCivilDate: string,
): PolicyVersion | undefined {
  return versionsFor(versions, organizationId, key).find(
    (row) =>
      row.approval === 'approved' && row.effectiveFrom !== '' && row.effectiveFrom <= onCivilDate,
  );
}

/**
 * Derive a safe fallback mode from a bounded configuration key suffix. Used
 * only when a stored value has no decodable envelope, so a plain value under a
 * hold or timed key never fails open as an immediate release.
 */
export function releaseModeForKey(key: string): ReleaseMode {
  if (key.endsWith('.timed')) {
    return 'timed';
  }
  if (key.endsWith('.hold')) {
    return 'hold';
  }
  return 'immediate';
}

/** Marker for a structured policy proposal carried in opaque contract text. */
export const POLICY_VALUE_PREFIX = 'sc-policy/v1 ';

/** A structured proposal that survives the configuration/catalog round trip. */
export interface PolicyEnvelope {
  text: string;
  approver: string;
  mode: ReleaseMode;
  reason: string;
  expiry: string;
}

/**
 * Encode a validated draft into the contract's opaque, bounded text value.
 * The configuration and catalog contracts carry a single `value` string, so
 * the release mode, hold reason, review date and named approver are kept in a
 * versioned envelope rather than silently discarded.
 */
export function encodePolicyValue(draft: PolicyChangeDraft): string {
  const envelope: PolicyEnvelope = {
    text: draft.value.trim(),
    approver: draft.approver.trim(),
    mode: draft.mode,
    reason: draft.reason.trim(),
    expiry: draft.expiry.trim(),
  };
  return `${POLICY_VALUE_PREFIX}${JSON.stringify(envelope)}`;
}

/**
 * Decode a stored value. Plain historical text stays valid as an immediate
 * release with no recorded approver, reason or expiry. A malformed envelope is
 * treated the same way rather than crashing the review screen.
 */
export function decodePolicyValue(value: string, fallbackMode: ReleaseMode = 'immediate'): PolicyEnvelope {
  const plain = (): PolicyEnvelope => ({
    text: value,
    approver: '',
    mode: fallbackMode,
    reason: '',
    expiry: '',
  });
  if (!value.startsWith(POLICY_VALUE_PREFIX)) {
    return plain();
  }
  try {
    const parsed = JSON.parse(value.slice(POLICY_VALUE_PREFIX.length)) as Partial<PolicyEnvelope>;
    return {
      text: typeof parsed.text === 'string' ? parsed.text : '',
      approver: typeof parsed.approver === 'string' ? parsed.approver : '',
      mode: parsed.mode === 'timed' || parsed.mode === 'hold' ? parsed.mode : fallbackMode,
      reason: typeof parsed.reason === 'string' ? parsed.reason : '',
      expiry: typeof parsed.expiry === 'string' ? parsed.expiry : '',
    };
  } catch {
    return plain();
  }
}

/**
 * Resolve the patient-access state of a release policy on a civil date.
 * A pending policy never releases a revision; an approved timed policy waits
 * for its effective date, and an approved hold stays withheld until its review
 * date, when the policy must be reviewed rather than silently released.
 */
export function releaseAccess(
  version: Pick<PolicyVersion, 'approval' | 'effectiveFrom' | 'mode' | 'expiry'>,
  onCivilDate: string,
): ReleaseAccess {
  if (version.approval !== 'approved') {
    return 'held';
  }
  if (version.mode === 'immediate') {
    return 'released';
  }
  if (version.mode === 'timed') {
    return version.effectiveFrom !== '' && version.effectiveFrom <= onCivilDate
      ? 'released'
      : 'scheduled';
  }
  return version.expiry !== undefined && version.expiry !== '' && version.expiry <= onCivilDate
    ? 'review'
    : 'held';
}

/**
 * Validate one proposed policy change at the trust boundary.
 *
 * A clinical or market policy is refused without a named approver, and a hold
 * is refused without a reason and expiry. The caller must not send a command
 * while any field is refused.
 */
export function validatePolicyChange(
  draft: PolicyChangeDraft,
  requiresApprover: boolean,
  requiresCode = false,
): PolicyErrors {
  const errors: PolicyErrors = {};
  if (requiresCode && draft.code.trim() === '') {
    errors.code = 'codeRequired';
  }
  if (draft.value.trim() === '') {
    errors.value = 'valueRequired';
  } else if (encodePolicyValue(draft).length > 4000) {
    errors.value = 'valueTooLong';
  }
  if (requiresApprover && draft.approver.trim() === '') {
    errors.approver = 'approverRequired';
  }
  const effectiveFrom = draft.effectiveFrom.trim();
  if (effectiveFrom === '') {
    errors.effectiveFrom = 'effectiveDateRequired';
  } else if (!isValidCivilDate(effectiveFrom)) {
    errors.effectiveFrom = 'effectiveDateInvalid';
  }
  if (draft.mode === 'hold' && draft.reason.trim() === '') {
    errors.reason = 'reasonRequired';
  }
  if (draft.mode === 'timed' || draft.mode === 'hold') {
    const expiry = draft.expiry.trim();
    if (expiry === '' || !isValidCivilDate(expiry)) {
      errors.expiry = 'expiryInvalid';
    }
  }
  return errors;
}

/** True when a validation result contains at least one refused field. */
export function hasPolicyErrors(errors: PolicyErrors): boolean {
  return Object.keys(errors).length > 0;
}
