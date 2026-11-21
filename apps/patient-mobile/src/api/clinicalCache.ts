/**
 * Transient, in-memory-only cache for clinical payloads (PAT-02).
 *
 * Released reports, message content and attachment bytes may be held for the
 * lifetime of one app process so a screen can render them, but they are never
 * written to a disk cache, AsyncStorage or the OS secure store. Only the single
 * session credential uses persistent storage, and that lives in
 * {@link ./tokenStore}. Sign-out and the idle timeout call
 * {@link clearClinicalCache} so no clinical value outlives the session.
 *
 * The cache is a process-local map keyed by clinical domain and identifier. A
 * new app process always starts empty, so nothing is restored from disk.
 */

/** Clinical domains that may be held transiently in memory. */
export type ClinicalNamespace = 'reports' | 'messages' | 'attachments';

/** Process-local memory only; never serialized to any persistent store. */
const entries = new Map<string, unknown>();

/** Build a namespaced key so domains cannot overwrite each other's values. */
function keyFor(namespace: ClinicalNamespace, key: string): string {
  return `${namespace}:${key}`;
}

/**
 * Hold a clinical payload in memory for the current app process.
 *
 * @param namespace clinical domain the value belongs to
 * @param key domain-scoped identifier of the value
 * @param value the payload (report projection, message text or attachment bytes)
 */
export function cacheClinical<T>(namespace: ClinicalNamespace, key: string, value: T): void {
  entries.set(keyFor(namespace, key), value);
}

/**
 * Read a transient clinical payload.
 *
 * @returns the value held in memory, or `undefined` when absent or after a clear
 */
export function readClinical<T>(namespace: ClinicalNamespace, key: string): T | undefined {
  return entries.get(keyFor(namespace, key)) as T | undefined;
}

/** Discard every clinical payload held in memory (sign-out / idle timeout). */
export function clearClinicalCache(): void {
  entries.clear();
}

/** Number of clinical payloads currently held in memory. */
export function clinicalCacheSize(): number {
  return entries.size;
}
