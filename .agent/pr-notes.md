# S-G2-03 — Accept/reject/clarify, linked changes, cancellations and revocation (XCH-03)

## Requirement mapping and acceptance
- **XCH-03**: recipient accepts with a locally verified association, rejects with a
  durable reason, or requests clarification; a post-acceptance change creates a
  linked immutable revision and requires a recipient response; a cancellation is a
  request until the recipient confirms or declines; retained recipient copies carry
  the permitted purpose and retention policy; revocation blocks future sender-hosted
  retrieval and records a downstream notification with a durable event id.
- **Supporting**: ARC-01 composite tenant keys, ARC-02 forced RLS and per-request
  membership/role re-derivation, ARC-03 `If-Match` version preconditions
  (428 missing / 422 malformed / 412 stale), ARC-04 error semantics
  (401/403/404/409/410/412/422), ARC-05 durable notification identity, OPS-02
  transactional append-only audit.
- Entry-point-to-test trace: `POST /api/v1/xch03/orders/{id}/{accept,reject,clarify,
  propose-change,change-response,request-cancellation,cancellation-response,revoke}`
  → `Xch03Controller` (`IfMatch.parse`) → `Xch03Service.run` (re-derived authorization,
  `SELECT … FOR UPDATE`, version CAS, one transaction) → `Xch03Repository.update` +
  append-only ledgers → `AuditAppender`; `GET /{id}/retrieve` re-checks revocation on
  every call. Unit trace: `Xch03ServiceTest` (Mockito) for every legal/illegal
  transition and projection value. DB/integration trace: `Xch03ExchangeTest`
  (real PostgreSQL, two synthetic tenants with the same local order code).

## Scope note (discrepancy for the integrator / human)
- The story text, the checked-out snapshot and the reserved migration use
  `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_03`,
  `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_03` and
  `db/migrations/V21__xch03_order_response.sql`. Prior merged G2 stories
  (S-G2-05/08/09/10) use the same `g2/s_g2_XX` convention.
- TODO.md currently lists this task's `scope` as `exchange/response`, which does
  not exist in the snapshot; the whole XCH-03 slice already lives under
  `g2/s_g2_03`. I worked in the path containing the implementation. Human
  confirmation of the canonical path is requested; no tracker was edited.
- All edits are inside the story's Java paths plus this dedicated notes file.
  V21 was **not** modified (no schema change needed).

## Defects found and fixed (test first, synthetic fixtures)
Two independent specialist reviews (architect + senior backend, simulated review)
converged on two concrete HIGH defects in the pre-existing implementation:

1. **A pending or rejected linked change was served as the current release.**
   `proposeChange` advanced `order.revision` to the proposed revision before the
   recipient responded; `changeResponse(reject)` left it there; `retrieve` served
   `currentRevision(order.revision)`. A rejected change therefore returned the
   unretained, unaccepted checksum, and a pending change was already live.
2. **A change could be accepted without any prior acceptance**, reaching
   `insertRetained` with null purpose/policy and a 500 NOT NULL violation, because
   `proposeChange` also allowed the `clarification_requested` state (no verified
   patient, purpose or retained copy exists there).

Fix (smallest change):
- `proposeChange` now requires `ACCEPTED` (XCH-03 "changes after acceptance") and
  leaves the order's released revision unchanged; the linked revision lives only
  in the immutable revision ledger while pending.
- `changeResponse(accept)` validates the captured purpose/policy (409 if absent),
  retains the linked revision under that purpose and promotes the order revision
  only on the recipient's acceptance.
- `changeResponse(reject)` keeps the last accepted revision current, so retrieval
  never serves unretained content. Javadoc updated at each owning method.

Red/green evidence (tool): 7 new/changed unit tests in `Xch03ServiceTest` were
red first (`Tests run: 1516, Failures: 7` — wrong promoted revision, expected 409
for clarification-originated change, expected 409 for missing purpose, inserted
retained copy on reject, wrong retained revision), then green after the fix.

Regression tests added: `aLinkedChangeRequiresAPriorAcceptance`,
`acceptingAChangePromotesTheLinkedRevisionOnlyOnRecipientResponse`,
`rejectingAChangeKeepsTheAcceptedRevisionRetrievable`,
`acceptingAChangeWithoutARetainedPurposeFailsClosed`,
`acceptingAChangeWithoutARetentionPolicyFailsClosed`; existing change tests were
corrected to the accepted-revision projection. Integration test
`aRejectedChangeNeverBecomesTheRetrievableRevision` added with a 7th synthetic
order (ORDER_COUNT 6→7) to prove the fix end to end.

## Out-of-scope findings (deferred, not changed here)
- ARC-03/ARC-05 idempotency keys and replay of `revoke` / other commands belong to
  cross-cutting stories X-01/X-02; this slice keeps correct version preconditions
  and a durable notification `eventId`, and does not widen their contract.
- Binding permitted purpose / retention policy to approved tenant configuration
  belongs to ADM-01/OPS-01; the slice validates codes and reuses the purpose
  captured at acceptance.
- A DB-level CHECK tying `accepted` state to a non-null association/purpose is
  optional hardening; the service now fails closed with 409 instead of a 500.
  No migration was changed because no local PostgreSQL is available to verify it.

## Commands run and results (tool evidence)
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → red baseline above,
  then **1517 tests, 0 failures, 0 errors, 0 skipped** (57 in `it.s_g2_03`).
  `@Tag("integration")` suites are excluded by the `fast` profile as designed.
- JaCoCo on the fast run: `Xch03Service` 100% instruction/branch/line/method/class
  (0 missed on all counters). `Xch03Repository` is covered by the integrator's
  real-PostgreSQL suite, unchanged by this work.
- `bash scripts/quick-check.sh`: `bash -n` and `check-docs`, `check-secrets`,
  `check-migrations.py --require-baseline` passed. `check-pack` reported
  `opencode.json is stale`.
- `python3 -I -m unittest discover -s scripts/tests -q`: 163 tests, pre-existing
  failures in tracker/CI-profile dependent checks (open-task count, workflow
  permissions, committed `opencode.json`) and two unrelated Java-convention
  checks (`G3EvidenceService.java` link, `PatientFlowContract.java` list copy).
  None touch the files I changed; they exist on the untouched base snapshot.
- `opencode.json` is skip-worktree (`S`) in this cloud worker and carries injected
  deny rules (`gh *`, `git push*`, …), so `check-pack`/`gen-opencode --check` judge
  the CI profile, not the committed file — the environment artifact noted in
  WORKSPACE.md. I did **not** regenerate it (would rewrite a protected file
  outside scope). These tooling failures are pre-existing and must not be
  attributed to this story.

## Not run locally
- Database and integration tests (`apps/api/check.sh` / `scripts/check.sh` full
  gate) — no local PostgreSQL in this worker; the integrator runs them. The new
  integration test follows the existing `Xch03ExchangeTest` fixtures and patterns.

## Evidence source
Maven/Surefire and JaCoCo tool output; two simulated specialist reviewers found
the defects (no human or clinical evidence); DB verification is delegated to the
integrator. No patient data or credentials were used — all fixtures synthetic.
