import { defined } from '../../../components/invariant';
/** Synthetic, non-patient fixtures for the print profile settings screen. */

import type { ApprovedLogoAsset, PrintProfileVersion } from './domain';

/** Active tenant used by every synthetic print profile fixture. */
export const settingsOrganizationId = '00000000-0000-4000-8000-000000000091';

/** A different tenant whose profiles must never be shown to the active tenant. */
export const otherOrganizationId = '00000000-0000-4000-8000-000000000092';

/** Facility whose print profile is edited by the synthetic administrator. */
export const settingsFacilityCode = 'MAIN';

/** Civil date used to decide which profile version is currently effective. */
export const anchorCivilDate = '2026-11-21';

/** Synthetic approved assets: references, never real image bytes or patient data. */
export const approvedLogoAssets: readonly ApprovedLogoAsset[] = [
  { reference: 'asset://synthetic/brand/main-en', label: 'Synthetic English letterhead logo' },
  { reference: 'asset://synthetic/brand/main-ar', label: 'Synthetic Arabic letterhead logo' },
];

/** A reference deliberately absent from the approved asset catalog. */
export const unapprovedLogoReference = 'asset://synthetic/brand/not-approved';

/** Append-only synthetic history: an approved effective version, a pending one, and another tenant. */
export const syntheticVersions: readonly PrintProfileVersion[] = [
  {
    organizationId: settingsOrganizationId,
    facilityCode: settingsFacilityCode,
    version: 1,
    facilityName: 'Synthetic Main Clinic v1',
    address: '1 Synthetic Way, Test City',
    logoReference: defined(approvedLogoAssets[0], 'approved logo asset').reference,
    footerText: 'Synthetic footer v1 — not a real notice',
    defaultLanguage: 'en',
    paperSize: 'A4',
    effectiveFrom: '2026-01-01',
    approved: true,
    approver: 'Synthetic administrator',
  },
  {
    organizationId: settingsOrganizationId,
    facilityCode: settingsFacilityCode,
    version: 2,
    facilityName: 'Synthetic Main Clinic v2',
    address: '2 Synthetic Way, Test City',
    logoReference: defined(approvedLogoAssets[1], 'other approved logo asset').reference,
    footerText: 'Synthetic footer v2 — not a real notice',
    defaultLanguage: 'ar',
    paperSize: 'LETTER',
    effectiveFrom: '2026-11-01',
    approved: false,
  },
  {
    organizationId: otherOrganizationId,
    facilityCode: settingsFacilityCode,
    version: 1,
    facilityName: 'Other tenant clinic',
    address: '9 Other Way, Test City',
    logoReference: defined(approvedLogoAssets[0], 'approved logo asset').reference,
    footerText: 'Other tenant synthetic footer',
    defaultLanguage: 'en',
    paperSize: 'A4',
    effectiveFrom: '2026-01-01',
    approved: true,
  },
];
