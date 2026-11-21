/**
 * Transient store for the patient's released handout projections (PAT-02).
 *
 * Handouts are clinical projections like reports, so they are held in the same
 * in-memory-only clinical cache. The existing sign-out path
 * (`clearToken` -> `clearClinicalCache`) therefore removes them without adding
 * any persistent storage. A namespaced, prefixed key keeps them distinct from
 * released-report and prescription entries in that shared cache.
 */
import { cacheClinical, clearClinicalCache, readClinical } from '../../api/clinicalCache';
import type { ClinicalNamespace } from '../../api/clinicalCache';

import type { ReleasedHandout } from './types';

// Reuse the reports namespace rather than extending the shared union: the cache
// has no per-key removal API, so sign-out clears the whole namespace anyway.
const HANDOUT_NAMESPACE: ClinicalNamespace = 'reports';
const VISIBLE_KEY = 'handout:visible-handouts';

/**
 * Hold the current session's released handouts in memory only.
 *
 * @param items handout projections fetched through the authenticated client
 */
export function rememberHandouts(items: readonly ReleasedHandout[]): void {
  cacheClinical(HANDOUT_NAMESPACE, VISIBLE_KEY, items);
}

/**
 * Read the handouts held for this session.
 *
 * @returns the remembered handouts, or an empty list before load and after sign-out
 */
export function visibleHandouts(): readonly ReleasedHandout[] {
  return readClinical<readonly ReleasedHandout[]>(HANDOUT_NAMESPACE, VISIBLE_KEY) ?? [];
}

/**
 * Sign-out hook: discard every held clinical projection so no handout data
 * outlives the session. The shared cache has no per-key removal API.
 */
export function clearSessionHandouts(): void {
  clearClinicalCache();
}
