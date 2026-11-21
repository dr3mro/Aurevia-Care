/** Synthetic, tenant-scoped fixtures for the policies screens. */

import { alphaOrganizationId, betaOrganizationId } from '../admin/fixtures';
import type { PolicyVersion, TemplateKind } from './domain';

/** Civil date used as "today" so every decision in a test is deterministic. */
export const policyAnchorCivilDate = '2026-03-09';

/** The two synthetic tenants; their keys intentionally collide across tenants. */
export { alphaOrganizationId, betaOrganizationId };

/** Template families approved for site use (CLN-04). */
export const templateKinds: readonly TemplateKind[] = [
  'soap',
  'operative',
  'obgyn',
  'perinatal',
  'pediatric-development',
];

/** Configuration key backing one template kind. */
export function templateKey(kind: TemplateKind): string {
  return `template.${kind}`;
}

/** Catalog family used for the synthetic approved field dictionary. */
export const dictionaryFamily = 'obstetric_history';

/** Configuration keys backing the three release policies (XCH-06). */
export const immediateReleaseKey = 'release.default';
export const timedReleaseKey = 'release.timed';
export const holdReleaseKey = 'release.hold';

/** Default key offered for each review area. */
export const defaultKeys = {
  templates: templateKey('soap'),
  dictionaries: dictionaryFamily,
  release: holdReleaseKey,
} as const;

/**
 * Synthetic template versions. The OB/GYN family keeps an older approved field
 * meaning beside its replacement so historical versions remain interpretable.
 */
export const syntheticTemplateVersions: readonly PolicyVersion[] = [
  {
    organizationId: alphaOrganizationId,
    key: templateKey('soap'),
    version: 1,
    value: 'Synthetic SOAP fields: concern, history, examination, plan',
    effectiveFrom: '2026-01-01',
    approval: 'approved',
    approver: 'Synthetic clinical governance A',
  },
  {
    organizationId: alphaOrganizationId,
    key: templateKey('soap'),
    version: 2,
    value: 'Synthetic SOAP fields with structured vitals section',
    effectiveFrom: '2026-03-01',
    approval: 'approved',
    approver: 'Synthetic clinical governance B',
  },
  {
    organizationId: alphaOrganizationId,
    key: templateKey('soap'),
    version: 3,
    value: 'Synthetic SOAP fields with pediatric prompts (draft)',
    effectiveFrom: '2026-07-01',
    approval: 'pending',
  },
  {
    organizationId: alphaOrganizationId,
    key: templateKey('obgyn'),
    version: 1,
    value: 'Synthetic obstetric history with LMP as a civil date',
    effectiveFrom: '2026-02-01',
    approval: 'approved',
    approver: 'Synthetic clinical governance A',
  },
  {
    organizationId: alphaOrganizationId,
    key: templateKey('obgyn'),
    version: 2,
    value: 'Synthetic obstetric history with LMP and gestational-age basis',
    effectiveFrom: '2026-06-01',
    approval: 'approved',
    approver: 'Synthetic clinical governance C',
  },
  {
    organizationId: betaOrganizationId,
    key: templateKey('soap'),
    version: 1,
    value: 'Synthetic beta SOAP fields',
    effectiveFrom: '2026-01-01',
    approval: 'approved',
    approver: 'Synthetic beta governance',
  },
];

/** Synthetic field-dictionary entries with preserved historical meanings. */
export const syntheticDictionaryVersions: readonly PolicyVersion[] = [
  {
    organizationId: alphaOrganizationId,
    key: `${dictionaryFamily}.LMP`,
    version: 1,
    value: 'Synthetic LMP: last menstrual period recorded as a civil date',
    effectiveFrom: '2026-01-15',
    approval: 'approved',
    approver: 'Synthetic dictionary steward A',
  },
  {
    organizationId: alphaOrganizationId,
    key: `${dictionaryFamily}.LMP`,
    version: 2,
    value: 'Synthetic LMP: civil date with estimated gestational-age basis',
    effectiveFrom: '2026-04-01',
    approval: 'approved',
    approver: 'Synthetic dictionary steward B',
  },
  {
    organizationId: alphaOrganizationId,
    key: `${dictionaryFamily}.GRAVIDA`,
    version: 1,
    value: 'Synthetic gravida: counted pregnancies, unknown becomes explicit',
    effectiveFrom: '2026-02-01',
    approval: 'approved',
    approver: 'Synthetic dictionary steward A',
  },
  {
    organizationId: alphaOrganizationId,
    key: `${dictionaryFamily}.GRAVIDA`,
    version: 2,
    value: 'Synthetic gravida: counted pregnancies with parity split (draft)',
    effectiveFrom: '',
    approval: 'pending',
  },
  {
    organizationId: betaOrganizationId,
    key: `${dictionaryFamily}.LMP`,
    version: 1,
    value: 'Synthetic beta LMP meaning',
    effectiveFrom: '2026-01-15',
    approval: 'approved',
    approver: 'Synthetic beta steward',
  },
];

/** Synthetic release policies: immediate, timed and held (XCH-06). */
export const syntheticReleaseVersions: readonly PolicyVersion[] = [
  {
    organizationId: alphaOrganizationId,
    key: immediateReleaseKey,
    version: 1,
    value: 'Synthetic immediate release of the signed revision',
    effectiveFrom: '2026-01-01',
    approval: 'approved',
    approver: 'Synthetic release authority A',
    mode: 'immediate',
  },
  {
    organizationId: alphaOrganizationId,
    key: timedReleaseKey,
    version: 1,
    value: 'Synthetic release after a 24 hour review window',
    effectiveFrom: '2026-04-01',
    approval: 'approved',
    approver: 'Synthetic release authority B',
    mode: 'timed',
    expiry: '2026-04-01',
  },
  {
    organizationId: alphaOrganizationId,
    key: holdReleaseKey,
    version: 1,
    value: 'Synthetic hold pending specialist review',
    effectiveFrom: '2026-02-01',
    approval: 'approved',
    approver: 'Synthetic release authority C',
    mode: 'hold',
    reason: 'Synthetic specialist review not complete',
    expiry: '2026-05-01',
  },
  {
    organizationId: alphaOrganizationId,
    key: holdReleaseKey,
    version: 2,
    value: 'Synthetic hold extended pending patient consent check',
    effectiveFrom: '2026-03-01',
    approval: 'pending',
    mode: 'hold',
    reason: 'Synthetic consent check pending',
    expiry: '2026-06-01',
  },
  {
    organizationId: betaOrganizationId,
    key: holdReleaseKey,
    version: 1,
    value: 'Synthetic beta hold',
    effectiveFrom: '2026-02-01',
    approval: 'approved',
    approver: 'Synthetic beta authority',
    mode: 'hold',
    reason: 'Synthetic beta review',
    expiry: '2026-05-01',
  },
];
