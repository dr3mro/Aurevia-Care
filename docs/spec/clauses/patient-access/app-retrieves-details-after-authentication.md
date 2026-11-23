## Clause

The app retrieves details after authentication.

## Status

Proven by tests

## Evidence

All links are relative to this file. The proof is a dedicated Jest/TypeScript test that drives the app's real detail-read path: the screen providers call `createPatientTransport`, which re-reads the OS-backed session credential on every request and fails closed when none is present. No production code was changed for this story; the fixtures are synthetic projections and the token store is replaced at the OS boundary with an in-memory mock.

Test file: [app-retrieves-details-after-authentication.test.ts](../../../../apps/patient-mobile/src/spec/patient-access/app-retrieves-details-after-authentication.test.ts), suite `retrieving patient details after authentication`:

- `retrievesPatientDetailsAfterAuthentication` — with a valid synthetic session credential, `loadAppointments` returns one mapped detail view; the single fetch goes to `/api/v1/patient/appointments?patientId=synthetic-patient` on the patient-scoped endpoint with `Authorization: Bearer synthetic-session-token`.
- `refusesToRetrieveDetailsWithoutACredential` (negative) — with the credential absent (`null`) or blank (`'   '`), the read rejects with a `TransportError` of kind `unauthenticated` and the fetch is never called, so no detail request leaves the device.
- `refusesDetailsWhenServerReportsForbidden` (negative) — a synthetic HTTP 403 produces a `TransportError` of kind `forbidden`; the scoped detail request was attempted once and no detail row is returned.
- `mapsAServerUnauthorizedResponseToUnauthenticated` (negative) — a synthetic HTTP 401 produces a `TransportError` of kind `unauthenticated` after one scoped detail request, with no detail returned.

Production code on the clause path — the first three are exercised directly by the test; the last two are related session-boundary guards that the test does not import:

- [transport.ts](../../../../apps/patient-mobile/src/api/transport.ts) — `createTransport` / `send` read the credential for every call, throw `TransportError('unauthenticated')` on a missing or blank token before any fetch, and `kindForStatus` maps 401 to `unauthenticated` and 403 to `forbidden`.
- [providers.ts](../../../../apps/patient-mobile/src/app/providers.ts) — `createPatientTransport` binds the OS credential loader to the patient API, and `loadAppointments` reads only the patient-scoped `/api/v1/patient/appointments` projection; `PATIENT_API_BASE_URL` is a synthetic HTTPS origin.
- [tokenStore.ts](../../../../apps/patient-mobile/src/api/tokenStore.ts) — `loadToken` is the OS-backed credential source whose OS boundary is mocked in the test; `saveToken` rejects blank credentials and `clearToken` ends the local session.
- [PatientResource.tsx](../../../../apps/patient-mobile/src/app/PatientResource.tsx) (related, not imported) — the screen container treats an `unauthenticated` failure by calling `onSessionExpired()`, ending the session rather than showing stale detail.
- [presentation.ts](../../../../apps/patient-mobile/src/features/flows/presentation.ts) (related, not imported) — `createSyntheticFlowClient` independently refuses a null subject (`UNAUTHENTICATED`) and a non-member (`FORBIDDEN`) at the presentation boundary.

Gate evidence (local, tool-executed): `bash apps/patient-mobile/check.sh` — 83 suites and 1161 tests passed with 100% statement/branch/function/line coverage; `npm run typecheck` (`tsc --noEmit`) clean; `python3 -I scripts/check-docs.py` and `python3 -I scripts/check-names.py` exit 0.

## Gap

None for this clause: the gate is implemented and the dedicated test above proves both the permitted read and the refusals. The proof uses synthetic fixtures and a mocked OS token store rather than a live identity provider or a real device keychain; exercising those live integrations is outside a repository test-only story and is not required to prove this clause. The server-side authorization for `/api/v1/patient/appointments` is verified by its own API tests and is unchanged here.
