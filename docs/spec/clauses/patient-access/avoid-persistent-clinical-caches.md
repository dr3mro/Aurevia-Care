# PAT-02 clause 4 — Patient app test evidence

## Clause

Avoid persistent clinical caches.

## Status

Proven by tests

## Evidence

Test suite: `apps/patient-mobile/src/spec/patient-access/avoid-persistent-clinical-caches.test.ts`
(Jest). All three tests are active (no `it.skip`, `.skip` or `xit`) and pass under the
patient-app gate with synthetic fixtures only. The suite spies on the single persistent
API the app uses (`expo-secure-store.setItemAsync`) and resets the transient cache before
each test.

- `keepsClinicalValuesInProcessMemoryOnly` — positive proof. A synthetic released report is
  cached under the `reports` namespace and a domain key, read back intact, and the cache
  size grows from 0 to 1, showing the value is held in process memory for the session.
- `neverWritesClinicalDataToPersistentStorage` — negative proof of the persistence rule.
  Caching a synthetic report and synthetic attachment bytes leaves
  `expo-secure-store.setItemAsync` uncalled, so no clinical payload reaches OS-backed
  storage.
- `refusesToReturnAClinicalValueAfterSignOut` — negative proof of the forbidden stale state.
  After the cache is cleared at sign-out, the previously held clinical value is no longer
  returned (`undefined`) and the cache is empty, so a stale clinical payload cannot survive
  the session.

Production unit exercised directly by these tests:

- `apps/patient-mobile/src/api/clinicalCache.ts` — `cacheClinical`, `readClinical`,
  `clearClinicalCache` and `clinicalCacheSize` operate on a single process-local `Map` and
  import no storage API, so a new app process always starts empty. The three tests cover
  its write, read, clear and size paths.

Wiring context, covered by its own suites and not exercised directly here:
`apps/patient-mobile/src/api/tokenStore.ts` calls `clearClinicalCache` from `clearToken` in
a `finally` block at sign-out, and `apps/patient-mobile/src/api/idleSession.ts` routes its
idle deadline through `clearToken`, so both session-ending paths discard clinical memory.
The `apps/patient-mobile/src/features/prescriptions/store.ts` and
`apps/patient-mobile/src/features/handouts/store.ts` released-projection stores use the same
shared in-memory clinical cache. `apps/patient-mobile/src/features/tracker/cache.ts` keeps its
own process-local `TrackerCache` with a `clear()` method; no production sign-out handler calls
it today (grep finds no caller outside its own tests). This does not affect the clause because
the map is process-local and nothing is persisted.

## Gap

None. The clause is built and proven by active tests. This task added proof only and
changed no production code, existing test, contract or migration.
