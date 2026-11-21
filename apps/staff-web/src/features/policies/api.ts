/**
 * API client for policy administration. Every call reuses the shared request
 * descriptor and transport through the administration client, so tenant,
 * correlation, idempotency and typed-failure handling stay in one place.
 * No response body text is copied into an error or a log.
 */

import {
  type CatalogEntryResponse,
  type ConfigEntryResponse,
  type ConfigHistoryResponse,
  asAdminTransportError,
  approveConfiguration,
  configurationVersionsFromHistory,
  defineCatalogEntry,
  defineConfiguration,
  getConfigurationHistory,
  isValidCatalogFamily,
  isValidConfigKey,
  listCatalogFamily,
} from '../admin/api';
import type { ConfigurationVersion } from '../admin/domain';
import {
  type PolicyChangeDraft,
  type PolicyVersion,
  decodePolicyValue,
  encodePolicyValue,
  releaseModeForKey,
} from './domain';

export type { CatalogEntryResponse, ConfigEntryResponse, ConfigHistoryResponse };
export { asAdminTransportError as asPolicyTransportError, isValidCatalogFamily, isValidConfigKey };

/** Map tenant configuration history into immutable policy versions. */
export function toPolicyVersions(
  rows: readonly ConfigurationVersion[],
  key: string,
): PolicyVersion[] {
  return rows.map((row) => {
    const proposal = decodePolicyValue(row.value, releaseModeForKey(key));
    return {
      organizationId: row.organizationId,
      key,
      version: row.version,
      value: proposal.text,
      effectiveFrom: row.effectiveFrom,
      approval: row.approved ? 'approved' : 'pending',
      ...(row.approver === undefined ? {} : { approver: row.approver }),
      ...(proposal.approver === '' ? {} : { proposedBy: proposal.approver }),
      mode: proposal.mode,
      ...(proposal.reason === '' ? {} : { reason: proposal.reason }),
      ...(proposal.expiry === '' ? {} : { expiry: proposal.expiry }),
    };
  });
}

/** Map effective catalog entries into immutable field-dictionary versions. */
export function toDictionaryVersions(
  entries: readonly CatalogEntryResponse[],
  family: string,
  organizationId: string,
): PolicyVersion[] {
  return entries.map((entry) => ({
    organizationId,
    key: `${family}.${entry.code ?? ''}`,
    version: entry.version ?? 0,
    value: entry.value ?? '',
    effectiveFrom: entry.effectiveFrom === undefined ? '' : entry.effectiveFrom.slice(0, 10),
    approval: 'approved',
    ...(entry.approvedBy === undefined ? {} : { approver: entry.approvedBy }),
  }));
}

/** Read the append-only history for one template or release-policy key. */
export function getPolicyHistory(key: string, organizationId: string): Promise<ConfigHistoryResponse> {
  return getConfigurationHistory(key, organizationId);
}

/** Read the effective entries of one field-dictionary catalog family. */
export function listDictionaryFamily(
  family: string,
  organizationId: string,
): Promise<CatalogEntryResponse[]> {
  return listCatalogFamily(family, organizationId);
}

/** Load and project one key's history into display versions. */
export async function loadPolicyVersions(key: string, organizationId: string): Promise<PolicyVersion[]> {
  const history = await getPolicyHistory(key, organizationId);
  return toPolicyVersions(configurationVersionsFromHistory(history, organizationId), key);
}

/** Load and project one catalog family into display versions. */
export async function loadDictionaryVersions(
  family: string,
  organizationId: string,
): Promise<PolicyVersion[]> {
  const entries = await listDictionaryFamily(family, organizationId);
  return toDictionaryVersions(entries, family, organizationId);
}

/** Append a new draft version for a template or release policy. */
export function proposePolicyVersion(
  key: string,
  draft: PolicyChangeDraft,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  return defineConfiguration(
    { key, value: encodePolicyValue(draft), effectiveFrom: `${draft.effectiveFrom}T00:00:00Z` },
    organizationId,
  );
}

/** Append a named approval for one immutable version. */
export function approvePolicyVersion(
  key: string,
  version: number,
  note: string,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  return approveConfiguration(key, version, note, organizationId);
}

/** Append a pending version to a field-dictionary catalog family. */
export function defineDictionaryField(
  family: string,
  draft: PolicyChangeDraft,
  organizationId: string,
): Promise<ConfigEntryResponse> {
  return defineCatalogEntry(
    family,
    {
      code: draft.code.trim(),
      value: draft.value,
      ...(draft.effectiveFrom === '' ? {} : { effectiveFrom: `${draft.effectiveFrom}T00:00:00Z` }),
    },
    organizationId,
  );
}
