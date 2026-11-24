# RAD-01 clause 4 — Link addenda and critical communications

## Clause

Link addenda and critical communications.

## Status

Proven by tests

## Evidence

Test class:
`com.aurevia.radiology.clauses.LinkAddendaCriticalCommunicationsTest`
(`apps/api/src/test/java/com/aurevia/radiology/clauses/LinkAddendaCriticalCommunicationsTest.java`),
a deterministic service-layer test over the tenant-scoped domain kernel with a fixed clock and an
effective dated interpreter privilege. It drives the synthetic lifecycle
intake → schedule → accession → associateStudy → sign → addAddendum / recordCriticalCommunication
and asserts outcomes: revision numbers, superseded links, digests, emitted facts, HTTP status and
stable error codes.

- `addendaLinkAsNewRevisionsWithoutEditingTheSignedReport` (rule met) — signing then adding an
  addendum yields an `ADDENDUM` kind at version 2 that supersedes version 1 and keeps its reason;
  the prior signed checksum is unchanged in the append-only interpretation history and
  `interpretation()` returns the new addendum. The order enters `ADDENDED`. `ADDENDUM_LINKED`
  carries the addendum checksum, the referrer as destination and the interpreter as actor, while
  the following `REFERRER_NOTIFIED` fact carries the referrer and no payload digest.
- `criticalCommunicationKeepsFindingAndReadBackAsSeparateDigestedFacts` (rule met) — recording a
  critical communication appends `CRITICAL_COMMUNICATION` followed by `READ_BACK`, each with its
  own `payloadDigest` equal to the SHA-256 of the respective narrative and the recipient as
  destination; neither raw narrative appears in any event and the order version and state are
  unchanged.
- `addendumWithoutReasonIsRefused` (negative) — a blank reason is refused with 422
  `REASON_REQUIRED` and appends no addendum or order revision.
- `addendumByActorWithoutInterpretationRoleIsForbidden` (negative) — a staff-only actor is refused
  with 403 `FORBIDDEN` and leaves the order `SIGNED`.
- `staleVersionCannotLinkAnAddendum` (negative) — a stale version precondition is refused with 412
  `STALE_VERSION` and appends no addendum.
- `criticalCommunicationBeforeStudyAssociationIsRefused` (negative) — recording before study
  association is refused with 409 `STUDY_NOT_ASSOCIATED` and emits no communication or read-back.
- `criticalCommunicationWithoutReadBackIsRefused` (negative) — a blank read-back is refused with
  422 `READ_BACK_REQUIRED` and emits no communication or read-back.
- `wrongTenantCannotReadTheInterpretation` (negative) — reading the signed interpretation through
  another tenant is concealed with 404 `NOT_FOUND`, while the owning tenant still sees it.
- `wrongPatientOrStudyIsRefused` (negative) — a mismatched patient or study is refused with 422
  `LABEL_MISMATCH` for both addendum and critical communication and appends no fact.

Production classes exercised by these entry points:

- `com.aurevia.radiology.ImagingService` — `addAddendum`, `recordCriticalCommunication`,
  `sign`, `interpretation`, `interpretations`, `read`, `events`, and the version, state, label and
  tenant guards.
- `com.aurevia.radiology.Interpretation` — the immutable revision carrying kind,
  superseded version, reason and deterministic content checksum.
- `com.aurevia.radiology.RadEvent` — the immutable facts and their dedup identities.
- `com.aurevia.radiology.RadCommands` — the addendum and critical communication
  command builders and immutable role sets.
- `com.aurevia.radiology.RadRules`, `RadPolicyCatalog`, `RadTypes` — role gates,
  effective dated imaging privilege and the state/event/kind enums.
- `com.aurevia.clinical.notes.NoteChecksum` — the shared SHA-256 digest used to assert
  the event payloads.

Verified locally with the command and final Surefire line:

```
env -u CHECK_SCRIPT -u BRANCH_PREFIX -u BASE_BRANCH mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=LinkAddendaCriticalCommunicationsTest test
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.143 s -- in com.aurevia.radiology.clauses.LinkAddendaCriticalCommunicationsTest
```

## Gap

The proof above is **service-layer only**: it exercises the domain kernel directly, not an HTTP
entry point. `ImagingController` exposes intake, read, study-link and review routes but has no
route for signing, linking an addendum or recording a critical communication. There is no JDBC
imaging store, no radiology table or migration with forced row-level security, and no imaging
routes in `contracts/openapi.yaml`, so these facts are process-local and do not survive a restart.
No HTTP-level integration test can prove this clause end to end today.

Two separate pieces of work are needed for end-to-end reachability: (1) a forced-RLS migration plus
a `Jdbc*` imaging store and an injected service bean, and (2) the addendum and critical
communication routes with their OpenAPI entries, regenerated clients and an HTTP-level integration
test. Both are already represented by the existing follow-up `AUD-20261121-05`, so no new task
request is made here. This test-only story changes no production code, existing test, contract or
migration.
