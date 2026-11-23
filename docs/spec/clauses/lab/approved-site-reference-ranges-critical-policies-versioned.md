# LAB-02 clause 4 — approved site reference ranges and critical policies are versioned

## Clause

Approved site reference ranges and critical policies are versioned.

## Status

Partly proven

## Evidence

Active domain-level proof (synthetic fixtures only; the report kernel is reached
through its `CaptureCommand`/`VerifyCommand` entry points):

- Test class: `com.aurevia.smartclinic.lab.clauses.ApprovedSiteReferenceRangesCriticalPoliciesVersionedTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/ApprovedSiteReferenceRangesCriticalPoliciesVersionedTest.java`).
- `highestApprovedEffectiveVersionIsSelectedAndStampedOnTheIssuedRevision` — positive proof. Two
  approved, effective versions of a reference range and of a critical policy are supplied. On
  verification the issued revision selects version 2 for both, applies version 2's bounds (a value
  of 95 is high against a range ceiling of 90 and critical against a policy ceiling of 92), and
  stamps `rangeVersion = 2` and `criticalPolicyVersion = 2` on the immutable revision.
- `futureDatedAndUnapprovedVersionsAreNeverApplied` — negative/stale proof. Higher versions that are
  future-dated, unapproved or already expired are ignored; the newest approved effective version
  (2) is still applied and stamped.
- `unapprovedCriticalPolicyDoesNotClassifyAResult` — negative proof. An unapproved critical policy
  never classifies a result: the observation keeps its normal range flag, `criticalPolicyVersion`
  is null and no XCH-05 escalation event is emitted.
- `emptyCatalogLeavesTheSubmittedFlagAndNullVersion` — negative proof. An empty catalog, matching
  the one wired into the HTTP result-entry service, preserves the caller-supplied flag and stamps
  no version: there is no implicit default threshold.

Production classes exercised by these tests:

- `com.aurevia.smartclinic.lab.result.LabPolicyCatalog` — version resolution; `effectiveRange` and
  `effectiveCritical` choose the highest version among entries that are approved and inside their
  effective window; an empty or unapproved catalog yields no policy.
- `com.aurevia.smartclinic.lab.result.LabPolicyCatalog.ReferenceRange` and
  `com.aurevia.smartclinic.lab.result.LabPolicyCatalog.CriticalPolicy` — the versioned, approved,
  effective-dated values and their `effectiveAt` / `flag` / `isCritical` rules.
- `com.aurevia.smartclinic.lab.result.LabResultService` — `capture` and `verify` commands; `evaluate`
  applies the newest effective range and critical policy and stamps their versions on the issued
  revision; it also emits the XCH-04/05 trigger facts.
- `com.aurevia.smartclinic.lab.result.ReportRevision` — the immutable revision that owns
  `rangeVersion` and `criticalPolicyVersion`.
- `com.aurevia.smartclinic.lab.result.LabObservation`, `LabCommands`, `LabTypes`, `LabRules` and
  `LabConversionCatalog` — the reused observation, command, vocabulary and role types.

The in-module style precedent for this domain-level proof is
`apps/api/src/test/java/com/aurevia/smartclinic/lab/result/DocumentedUnitConversionTest.java`,
which exercises the LAB-02 result kernel directly rather than through HTTP.

The versioning mechanism is therefore real and proven in the domain kernel. It is not, however,
reachable through the HTTP API, which is the gap below. This survey found no controller, workflow
or Flyway migration that lets a tenant approve or version a site reference range or critical
policy and have an issued HTTP report record those versions.

## Gap

**The versioning is not configurable or observable through the HTTP API.** The proof above drives
the domain kernel directly with a synthetic catalog. The wired result-entry path does not use a
populated catalog:

- `com.aurevia.smartclinic.labflow.results.ResultConfiguration` builds
  `new LabPolicyCatalog(List.of(), List.of())`, so through HTTP the issued report's `rangeVersion`
  and `criticalPolicyVersion` are always null.
- `com.aurevia.smartclinic.labflow.results.ResultEntryService.flag` derives each flag from the
  caller-supplied `referenceLow`, `referenceHigh`, `criticalLow` and `criticalHigh` on the
  observation and hard-codes `DEFAULT_POLICY_VERSION = 1`; it never consults an approved,
  versioned site catalog.
- There is no endpoint to define, approve, version or effective-date site reference ranges or
  critical policies for a tenant, and no migration persists such approved versions. The existing
  `db/migrations/V23__critical_results.sql` stores an approved escalation-policy **snapshot** on a
  critical-result alert, and `db/migrations/V25__observation_trending.sql`
  (`trend_comparability`) picks the latest effective date with version as a tie-break; both are
  adjacent mechanism, but neither exposes site reference ranges or critical policies through an
  API.

The complete HTTP proof that would close this clause is therefore **not added as a skipped test**.
`scripts/check-constraints.py` rejects any newly added `@Disabled`/`@Ignore` line in a Java file
and AGENTS.md forbids skipped tests, so this pull request stays green with only passing tests. The
test to enable in a later task is:

- Method `siteApprovedVersionedRangesAndCriticalPoliciesAreServedThroughTheApi` — a real-PostgreSQL
  fixture (`@Tag("integration")`, `synthetic-auth`, `RANDOM_PORT`, Flyway from `db/migrations`,
  synthetic tenants and users) that POSTs two approved versions of a site range and a critical
  policy through a new tenant-admin configuration endpoint, captures and verifies a report through
  the result-entry HTTP routes, and asserts that the returned report records version 2 and the
  version-2 interpretation. Negative cases in the same fixture: a future-dated or unapproved
  version is ignored, another tenant cannot see the approved versions, and a wrong-role actor
  cannot approve them.

Files that would change to close the gap: a new Flyway migration (reserved version owned by the
implementation task) with `ENABLE` and `FORCE ROW LEVEL SECURITY` plus tenant policies and an
approval/version-history table for site ranges and critical policies; a `Jdbc*` store behind a
`*Store` interface; a tenant-admin approval service and a thin controller with HTTP endpoints;
wiring that supplies a populated `LabPolicyCatalog` from that store instead of the empty one;
stamped `rangeVersion`/`criticalPolicyVersion` returned by `ReportView`; the OpenAPI contract entry
with regenerated staff-web client types; and the staff-web configuration form. This is one
cross-boundary backend/database/contract task plus one staff-web task: **at least two separate
tasks**, with a QA/review pass advisable before clinical use.

This evidence task added only one test class and this note. It changed no production code, no
existing test, no migration and no contract.
