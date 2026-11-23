# Patient access clause 3 — protect local credentials with OS-backed storage

## Clause

Protect local credentials with OS-backed storage.

## Status

Proven by tests

## Evidence

Test class `Protect local credentials with OS-backed storage`
(`apps/patient-mobile/src/spec/patient-access/protect-local-credentials-os-backed-storage.test.ts`),
a focused Jest suite using an in-memory `expo-secure-store` replacement and an injected
recording secure-store adapter with synthetic credentials only:

- `stores the session credential in the OS-backed store and reads it back` —
  `saveToken` writes the exact key `aurevia.patient.session` and the non-blank
  credential to the native store, `loadToken` reads it back, and
  `SecureCredentialStore.save`/`read` use the same key through the injected
  OS-backed adapter.
- `refuses a blank credential without writing to the OS store` — a blank
  credential is rejected with `INVALID_CREDENTIAL` by both store paths and
  produces zero native or adapter writes.
- `removes the credential from the OS store on sign-out` — `clearToken` and
  `SecureCredentialStore.clear` delete the session key, and a following read
  finds no credential.
- `still clears clinical memory when the OS store delete fails` — a simulated
  native keychain failure propagates after the delete is attempted, and no
  in-memory clinical payload survives.
- `refuses a protected request when the OS-backed credential is missing` —
  `ReleasedReportClient` rejects with `NOT_AUTHENTICATED` and issues no
  transport request when the OS store holds no credential.
- `refuses a protected request when the OS-backed credential is blank` — the
  same refusal and zero transport requests when the stored value is a blank
  string.
- `re-reads the OS credential before each protected request` — two successive
  protected reads perform two OS-store reads and two authenticated transport
  calls, so a change in stored credential takes effect on the next action.

Production classes and modules exercised by the entry points:
`apps/patient-mobile/src/api/tokenStore.ts` (`saveToken`, `loadToken`,
`clearToken`), `apps/patient-mobile/src/features/reports/credentials.ts`
(`SecureCredentialStore`, `SecureStoreAdapter`) and
`apps/patient-mobile/src/features/reports/reportClient.ts`
(`ReleasedReportClient` protected reads through `requireToken`).
`apps/patient-mobile/src/api/clinicalCache.ts` is exercised only as the
sign-out side effect that prevents a credential-bearing session from leaving
transient clinical memory behind.

Verified locally with `bash apps/patient-mobile/check.sh` and
`python3 -I scripts/check-docs.py`.

## Gap

None.
