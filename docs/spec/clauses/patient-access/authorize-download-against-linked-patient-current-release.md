# PAT-02 clause 2 — authorize every download against the linked patient and current release

## Clause

Authorize every download against the linked patient and current release.

## Status

Proven by tests

## Evidence

Test file: `apps/patient-mobile/src/spec/patient-access/authorize-download-against-linked-patient-current-release.test.ts`.
It follows the fixture style of `apps/patient-mobile/src/features/reports/report-core.test.ts` and
redefines the synthetic `recorder`, `transport`, `tokens`, `report` and `authorization` fixtures
locally, so importing a test file cannot re-register its `describe` blocks. All tests are active
(`it`, not `it.skip`), use synthetic identifiers only and drive the typed client directly; no React
render, network, credential store or clinical data is involved.

Test methods (one positive rule proof and four negative refusals):

- `authorizes the tapped revision only when the server authorization is linked to the patient and current` —
  positive proof. A current-release authorization for `patient-1` resolves through the normal
  client entry point, and the protected request is asserted as
  `POST /api/v1/released-reports/report-1/download` with the stored token and body `{ revision: 2 }`.
- `refuses an authorization linked to another patient even when every other field matches` — negative
  proof. The only changed field against the valid fixture is `patientId: patient-2`; the client
  rejects with `WRONG_PATIENT` and no document reference resolves, so the linked-patient check is
  the cause of refusal.
- `refuses a withdrawn release even when an older authorization exists` — negative proof. The only
  changed field is `releaseState: withdrawn`; the client rejects with `RELEASE_WITHDRAWN` and no
  document reference resolves, so a stale release cannot grant access after it is withdrawn.
- `refuses an authorization for another report or revision` — negative proof. A wrong `reportId`
  and a wrong `revision` each reject with `AUTHORIZATION_MISMATCH`, so a document reference cannot
  be substituted for the revision the patient tapped.
- `refuses to authorize without a stored credential` — negative proof. A missing token rejects
  with `NOT_AUTHENTICATED` before any download request is posted.

Production code exercised through the client entry point:

- `ReleasedReportClient.authorizeDownload` in
  `apps/patient-mobile/src/features/reports/reportClient.ts` — validates the tapped `reportId` and
  `revision`, reads the OS-backed credential immediately before the request, posts for a fresh
  server authorization and never keeps clinical state between calls.
- The module-local `parseAuthorization` in the same file — the client trust boundary that binds
  the server authorization to the exact report, revision, the authenticated patient and a current
  release, throwing `AUTHORIZATION_MISMATCH`, `WRONG_PATIENT` or `RELEASE_WITHDRAWN`.
- The contracts `DownloadAuthorization`, `ReleasedReport`, `ReportTransport` and `TokenStore` in
  `apps/patient-mobile/src/features/reports/types.ts` — a document reference is released only as a
  parsed, typed authorization, never rendered bytes or a credential.
- The screen path `ReleasedReportsScreen` → `confirmAndAuthorizeDownload` in
  `apps/patient-mobile/src/features/reports/downloadFlow.ts` calls this same authorization command
  after explicit patient intent; the confirmation prompt is not itself an authorization decision.

Where the clause is implemented today (source survey result): `parseAuthorization` and
`ReleasedReportClient.authorizeDownload` in `reportClient.ts` hold the linked-patient and
current-release checks on every download; `confirmAndAuthorizeDownload` in `downloadFlow.ts` wires
the screen's download action to that command. The neighbouring client acceptance tests in
`report-core.test.ts` and `reports-screen.test.tsx` already cover the malformed-authorization,
input-validation, cancelled-confirmation and screen-to-authorization paths. This task adds proof
only.

Proof boundary: the acceptance test drives the typed client through a synthetic transport, so it
proves the authorization decision, not a clickable end-to-end download. Server-side enforcement of
the patient link and release state is a separate boundary; the download-button wiring defect
tracked as QA-SOURCE-02 in `BUGS.md` is separately owned and does not change this clause's status.

## Gap

None.
