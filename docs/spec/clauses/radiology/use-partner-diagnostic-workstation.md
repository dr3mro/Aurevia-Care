# RAD-01 clause 2 — use the partner diagnostic workstation

## Clause

Use the partner diagnostic workstation.

## Status

Partly proven

The code-side rule is implemented and proven by active HTTP-level tests: the
study-link route refuses an association without a partner diagnostic workstation
reference and records the reference on the new order revision. The remaining
half of the clause — an actual approved partner workstation/PACS interface and
site sign-off — is external evidence that a repository test cannot produce.

## Evidence

Requirement: RAD-01, row at [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md)
line 77. All paths below are repository files; no external or invented artifact
is cited.

Test class `com.aurevia.radiology.clauses.UsePartnerDiagnosticWorkstationTest`
(`apps/api/src/test/java/com/aurevia/radiology/clauses/UsePartnerDiagnosticWorkstationTest.java`),
a synthetic, database-free standalone MockMvc test using the same harness as
`ImagingControllerHttpTest`: `ApiErrorHandler` as controller advice,
`TenantContextInterceptor` under the `synthetic-auth` profile, the
`X-Synthetic-Subject` and `X-Active-Organization` headers, a mocked
`StaffRepository` for fresh active roles and a mocked `TransactionTemplate` that
runs the guarded callback. A fixed `Clock` and a synthetic CT order seeded
through intake, scheduling and accession (version 3, `ACCESSIONED`) make the
result deterministic. Only the study-link command crosses the HTTP boundary.
The entry-point-to-assertion trace is
`POST /api/v1/imaging/orders/{orderId}/studies` →
`ImagingController.linkStudy` → `ImagingService.associateStudy` →
`requireText(workstationRef, "INVALID_WORKSTATION")` and the stored
`ImagingOrder.workstationRef`; `ApiErrorHandler` renders the asserted status and
code, and `service.read` / `service.events` prove the refusal appended nothing.

- `linksAStudyOnlyWithAPartnerWorkstationReference` (rule met) — links a study
  with a synthetic workstation reference and asserts HTTP 200,
  `state=STUDY_ASSOCIATED`, `version=4`, the supplied Study Instance UID and
  `workstationRef` on the response, the same values on the stored revision and
  exactly one `STUDY_ASSOCIATED` event.
- `refusesToLinkWhenWorkstationReferenceIsMissing` — a blank reference is
  refused with 422 `INVALID_WORKSTATION`; the order stays `ACCESSIONED` at
  version 3 with no UID or workstation reference and no association event.
- `refusesToLinkWithoutAssociationRole` — a `reception` subject is refused with
  403 `FORBIDDEN` and appends nothing.
- `refusesToLinkForAnotherTenant` — a peer tenant with the required
  `radiology_staff` role is refused with 404 `NOT_FOUND`, so tenant concealment
  cannot be bypassed by supplying a valid workstation reference.
- `refusesToLinkWithStaleOrderVersion` — a version behind the current revision
  is refused with 412 `STALE_VERSION` and appends nothing.

Production classes exercised by that path:

- `com.aurevia.radiology.ImagingController.linkStudy` binds
  `StudyLinkRequest.workstationRef` from the wire into the domain command;
  identity and active roles come from the request context, never the body.
- `com.aurevia.radiology.ImagingService.associateStudy` requires a
  non-blank workstation reference, enforces the `ACCESSIONED` to
  `STUDY_ASSOCIATED` transition and records the reference on the new revision;
  `copyOrder` carries it into later revisions.
- `com.aurevia.radiology.ImagingOrder` stores `workstationRef`
  beside the Study Instance UID; `RadCommands.AssociateStudyCommand` and
  `RadRules.mayAssociate` carry the value and the role gate.
- `com.aurevia.web.TenantContextInterceptor` and
  `com.aurevia.api.ApiErrorHandler` provide the tenant boundary and
  the machine-readable error bodies the refusals assert.

"No image pixels are fetched" is structural evidence, not an executable
assertion: the class Javadoc of `ImagingService` (lines 40-43 and 197-198) and
`ImagingOrder` (lines 16-18) states that only the Study Instance UID and a
partner workstation reference are recorded and that pixels stay under partner
control; there is no image-retrieval route or DICOMweb proxy in the imaging
package. Label-mismatch and duplicate-review behavior is already covered by
`StudyLinkReviewTest` and `ImagingControllerHttpTest` and is not repeated here.
The neighbour `radflow.worklist.WorklistService.linkStudy` and its controller,
command and order record enforce the same rule but are a separate worklist
slice, outside this clause test.

Contract survey: there is no imaging or DICOM operation in
`contracts/openapi.yaml`. The imaging routes, including the study-link route
used here, are baselined in `contracts/openapi.yaml` (lines 49-50
and 220-222). That is separate contract-publication debt, not a gap in this
clause's behavior. The imaging kernel is also in-memory, so this clause is
proven at the HTTP/domain boundary and does not claim restart durability.

## Gap

No code behavior is missing. What is absent is external partner and site
evidence. The PACS / imaging partner row of
[partner-interfaces.md](../../../launch/partner-interfaces.md) is **Not
approved** and its five columns are unchecked: a signed interface contract that
fixes the study-association/reporting channel and the partner diagnostic
workstation boundary, a recorded conformance run, approved scoped credentials,
reconciliation evidence and a timed outage drill. The section 9 and section 10
site acceptance recorded by task `S-G5-04` is also outstanding; section 10 keeps
partner traffic disabled until that evidence exists.

Files that would change are `docs/launch/partner-interfaces.md` and the site
sign-off artifacts it links; no API source, contract, migration or test file
needs to change for this clause. That is **1 human/partner decision task**
(`S-G5-04`, Integration lead and partner owner with the Product Owner for site
acceptance), which a code worker cannot complete. No test was disabled and no
`@Disabled("GAP: ...")` case was added, because the mandatory-and-recorded rule
is built; the external evidence belongs here rather than in a disabled test.
