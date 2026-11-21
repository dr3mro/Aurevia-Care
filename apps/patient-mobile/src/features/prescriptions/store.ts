/**
 * Transient store for the patient's released prescription projections (PAT-02).
 *
 * Prescriptions are released clinical projections like reports, so they are held
 * in the same in-memory-only clinical cache. The existing sign-out path
 * (`clearToken` -> `clearClinicalCache`) therefore removes them without any new
 * persistent storage. A namespaced, prefixed key keeps them distinct from
 * released-report entries in that shared cache.
 */
import { cacheClinical, clearClinicalCache, readClinical } from '../../api/clinicalCache';
import type { ClinicalNamespace } from '../../api/clinicalCache';

import type { ReleasedPrescription } from './types';

const PRESCRIPTION_NAMESPACE: ClinicalNamespace = 'reports';
const VISIBLE_KEY = 'rx:visible-prescriptions';

/**
 * Hold the current session's released prescriptions in memory only.
 *
 * @param items prescription projections fetched through the authenticated client
 */
export function rememberPrescriptions(items: readonly ReleasedPrescription[]): void {
  cacheClinical(PRESCRIPTION_NAMESPACE, VISIBLE_KEY, items);
}

/**
 * Read the held prescriptions for this session.
 *
 * @returns the remembered prescriptions, or an empty list after sign-out
 */
export function visiblePrescriptions(): readonly ReleasedPrescription[] {
  return readClinical<readonly ReleasedPrescription[]>(PRESCRIPTION_NAMESPACE, VISIBLE_KEY) ?? [];
}

/**
 * Sign-out hook: discard every held clinical projection (reports, messages,
 * attachments and prescriptions), so no prescription data outlives the session.
 *
 * This deliberately clears the whole transient clinical cache rather than a
 * feature-local subset; the shared cache has no per-key removal API.
 */
export function clearSessionClinicalCache(): void {
  clearClinicalCache();
}
