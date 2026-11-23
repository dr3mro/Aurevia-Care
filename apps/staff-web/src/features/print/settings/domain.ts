/**
 * Pure domain rules for per-facility print profiles (WEB-09, CLN-07). Every
 * profile is an immutable, tenant-scoped, effective-dated version: proposing or
 * approving one appends a new row, so the previous profile stays in history.
 * Only an active tenant administrator may manage these settings.
 */

import type { AdminRole } from '../../admin/domain';
import { isValidCivilDate } from '../../admin/domain';
import type { HandoutLanguage } from '../components/messages';
import type { SettingsMessageKey } from './labels';

/** Roles the print settings screen understands (reuse of the admin model). */
export type PrintSettingsRole = AdminRole;

/** Bounded paper sizes offered by the print profile editor. */
export type PaperSize = 'A4' | 'LETTER';

/** One synthetic, approved logo asset a facility may reference in its letterhead. */
export interface ApprovedLogoAsset {
  readonly reference: string;
  readonly label: string;
}

/** Editable fields of one print profile version. */
export interface PrintProfileFields {
  facilityName: string;
  address: string;
  logoReference: string;
  footerText: string;
  defaultLanguage: HandoutLanguage;
  paperSize: PaperSize;
  /** Civil date YYYY-MM-DD; compared as a string at UTC midnight. */
  effectiveFrom: string;
}

/** Fields collected by the print profile editor. */
export type PrintProfileDraft = PrintProfileFields;

/** One immutable, tenant-scoped print profile version. */
export interface PrintProfileVersion extends PrintProfileFields {
  organizationId: string;
  facilityCode: string;
  version: number;
  approved: boolean;
  approver?: string;
}

/** Fields of a draft that can fail validation at the trust boundary. */
export type ProfileField = keyof PrintProfileFields;

/** Field-keyed validation result for one submitted print profile draft. */
export type ProfileErrors = Partial<Record<ProfileField, SettingsMessageKey>>;

/** The only role allowed to propose or approve a print profile. */
const MANAGER_ROLES: ReadonlySet<PrintSettingsRole> = new Set<PrintSettingsRole>(['tenant_admin']);

/**
 * Resolve whether a viewer may manage print profiles.
 *
 * @param role active role of the viewer in the current organization
 * @returns true only for an active tenant administrator
 */
export function canManagePrintSettings(role: PrintSettingsRole): boolean {
  return MANAGER_ROLES.has(role);
}

/**
 * Accept only a logo reference present in the approved asset catalog.
 *
 * @param reference user-entered asset reference, trimmed before comparison
 * @param assets approved asset catalog for the tenant
 * @returns true when the reference names an approved asset
 */
export function isApprovedLogo(reference: string, assets: readonly ApprovedLogoAsset[]): boolean {
  const normalized = reference.trim();
  return assets.some((asset) => asset.reference === normalized);
}

/** Return one tenant and facility's versions, newest first. */
export function versionsForFacility(
  entries: readonly PrintProfileVersion[],
  organizationId: string,
  facilityCode: string,
): PrintProfileVersion[] {
  return entries
    .filter(
      (entry) => entry.organizationId === organizationId && entry.facilityCode === facilityCode,
    )
    .sort((left, right) =>
      right.version !== left.version
        ? right.version - left.version
        : right.effectiveFrom.localeCompare(left.effectiveFrom),
    );
}

/** Return the newest approved version already effective on a civil date. */
export function effectiveProfile(
  entries: readonly PrintProfileVersion[],
  organizationId: string,
  facilityCode: string,
  onCivilDate: string,
): PrintProfileVersion | undefined {
  return versionsForFacility(entries, organizationId, facilityCode).find(
    (entry) => entry.approved && entry.effectiveFrom <= onCivilDate,
  );
}

/** Allocate the next tenant- and facility-local version; an unseen profile starts at one. */
export function nextProfileVersion(
  entries: readonly PrintProfileVersion[],
  organizationId: string,
  facilityCode: string,
): number {
  return (
    versionsForFacility(entries, organizationId, facilityCode).reduce(
      (maximum, entry) => Math.max(maximum, entry.version),
      0,
    ) + 1
  );
}

/**
 * Validate a print profile draft. An unapproved logo is refused rather than
 * stored, and a bad civil date never reaches `Intl.DateTimeFormat`.
 */
export function validateProfileDraft(
  draft: PrintProfileDraft,
  assets: readonly ApprovedLogoAsset[],
): ProfileErrors {
  const errors: ProfileErrors = {};
  if (draft.facilityName.trim().length === 0) {
    errors.facilityName = 'facilityNameRequired';
  }
  if (draft.address.trim().length === 0) {
    errors.address = 'addressRequired';
  }
  if (!isApprovedLogo(draft.logoReference, assets)) {
    errors.logoReference = 'logoNotApproved';
  }
  if (draft.footerText.trim().length === 0) {
    errors.footerText = 'footerTextRequired';
  }
  if (!isValidCivilDate(draft.effectiveFrom.trim())) {
    errors.effectiveFrom = 'invalidEffectiveDate';
  }
  return errors;
}

/** Append a pending version bound to the requesting tenant and facility. */
export function proposeProfile(
  entries: readonly PrintProfileVersion[],
  organizationId: string,
  facilityCode: string,
  draft: PrintProfileDraft,
): PrintProfileVersion {
  return {
    organizationId,
    facilityCode,
    version: nextProfileVersion(entries, organizationId, facilityCode),
    approved: false,
    ...draft,
  };
}

/** Approve a version without changing its content or effective date. */
export function approveProfile(
  version: PrintProfileVersion,
  approver: string,
): PrintProfileVersion {
  return { ...version, approved: true, approver };
}
