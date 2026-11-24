# ARC-03 clause 3 — use explicit POST commands for sign, correct, acknowledge, release and exchange

## Clause

Use explicit POST commands for sign, correct, acknowledge, release and exchange.

## Status

Partly proven

## Evidence

Test class `com.aurevia.web.clauses.UseExplicitPostCommandsSignCorrectAcknowledgeTest`
(`apps/api/src/test/java/com/aurevia/web/clauses/UseExplicitPostCommandsSignCorrectAcknowledgeTest.java`),
a real-PostgreSQL HTTP integration test under the `synthetic-auth` profile with a
fresh Flyway database, `RestClient` and the `X-Synthetic-Subject` /
`X-Active-Organization` / `If-Match` headers. Every HTTP request goes through the
real Spring MVC pipeline and the production controllers and services; only
synthetic tenants, subjects and revision identifiers are used.

- `syntheticCatalogRegistersAllFiveFamiliesAsPostCommands` — asserts that
  `CommandCatalog.synthetic()` registers `signInterpretation`, `correctRelease`,
  `acknowledgeCriticalResult`, `authorizeRelease`, `acceptOrder` and `rejectOrder`
  with method POST, their owning action keys and owning policies, and that every
  catalog command is a POST command. `CommandDefinition` rejects any other
  method at construction time.
- `unknownCommandIsRefusedWithUnknownCommand` — an unregistered command id is
  absent from `find` and `require` fails closed with `422 UNKNOWN_COMMAND`.
- `correctReleasePostedAtCatalogPathSupersedesOriginal` — `POST /api/v1/releases`
  creates an authorized release (201) and `POST /api/v1/releases/{id}/correct`
  at `If-Match: "1"` returns 200 with a replacement revision that names the
  original in `supersedesId`. The read-back proves that current chart access
  serves the replacement, no longer the original, that the original is stored
  as `CORRECTED`, and that its history records `RELEASE_CORRECT`.
- `releaseFamilyPostedAtLiveRouteChangesVisibility` — the live authorize route
  `POST /api/v1/releases` returns 201 and opens visibility of the immutable
  revision on the linked synthetic chart.
- `catalogPathsForSignAcknowledgeAndExchangeHaveNoLiveRoute` — five catalog
  paths (`/api/v1/radiology/interpretations/{id}/sign`,
  `/api/v1/critical/{id}/acknowledge`, `/api/v1/releases/{id}/authorize`,
  `/api/v1/orders/{id}/accept` and `/api/v1/orders/{id}/reject`) return 404; the
  gap is test-enforced, not only documented.
- `wrongRoleCannotCorrectReleaseAndStateIsUnchanged` — a `reception` subject is
  refused 403; the release stays `AUTHORIZED`, keeps its revision visible and
  records no correction event.
- `otherTenantCannotCorrectReleaseAndStateIsUnchanged` — a member of a second
  tenant with an active membership is refused 404 under forced row-level
  security; the owning tenant's release and visibility are unchanged and the
  other tenant cannot see the revision.
- `staleVersionCannotCorrectReleaseAndStateIsUnchanged` — `If-Match: "9"` is
  refused `412 STALE_VERSION`; the stored release and visibility are unchanged.
- `forbiddenStateCannotCorrectWithdrawnRelease` — a withdrawn release is
  terminal, so a later correction is refused `409 INVALID_TRANSITION` and the
  state stays `WITHDRAWN`.
- `missingInputCannotCorrectReleaseAndStateIsUnchanged` — an empty corrected
  revision id is refused `422 INVALID_REVISION_ID` before any transition and the
  stored release is unchanged.

Production classes and routes exercised by this entry point:

- `com.aurevia.api.authorization.CommandCatalog` (`synthetic`,
  `find`, `require`, `commands`) and `CommandDefinition`, the shared command
  registry consumed by `CommandAuthorization` and `CommandClientGenerator`
  (`apps/api/src/main/java/com/aurevia/api/authorization/`).
- `com.aurevia.exchange.release.PatientReleaseController`
  (`POST /api/v1/releases`, `POST /api/v1/releases/{id}/correct`,
  `POST /api/v1/releases/{id}/withdraw`, `GET /api/v1/releases/{id}`,
  `GET /api/v1/releases/{id}/history`) and
  `com.aurevia.exchange.release.ReleaseService`, backed by the
  tenant-scoped `ReleaseRepository` and the V24 release tables.
- `com.aurevia.patient.PatientController`
  (`GET /api/v1/patients/{id}/released-revisions`) for the visibility read-back.
- `com.aurevia.web.TenantContextInterceptor` binds the tenant from
  the synthetic headers; `com.aurevia.api.IfMatch` parses the
  version precondition used by the irreversible command.

## Gap

Five catalog paths across four families are declarations without a live
controller, so a POST to each is answered 404 (asserted by
`catalogPathsForSignAcknowledgeAndExchangeHaveNoLiveRoute`). This is catalog and
contract drift, not a missing domain workflow:

1. `signInterpretation` — `/api/v1/radiology/interpretations/{id}/sign` has no
   controller. Radiology signing is reached through
   `com.aurevia.radflow.worklist.WorklistController`
   (`POST /api/v1/radiology/worklist/orders/{orderId}/report`) and chart-note
   signing through `com.aurevia.clinical.notes.ClinicalNoteController`
   (`POST /api/v1/clinical-notes/{id}/sign`).
2. `acknowledgeCriticalResult` — `/api/v1/critical/{id}/acknowledge` has no
   controller. The live route is
   `com.aurevia.exchange.critical.CriticalResultController`
   (`POST /api/v1/critical-results/{id}/acknowledge`).
3. `authorizeRelease` — `/api/v1/releases/{id}/authorize` has no controller.
   Authorization is the create route
   `com.aurevia.exchange.release.PatientReleaseController`
   (`POST /api/v1/releases`), whose domain method is `ReleaseService.authorize`.
4. `acceptOrder` and `rejectOrder` — `/api/v1/orders/{id}/accept` and
   `/api/v1/orders/{id}/reject` have no controller. The live routes are
   `com.aurevia.exchange.response.ExchangeResponseController`
   (`POST /api/v1/xch03/orders/{id}/accept` and `/reject`).

`CommandClientGenerator` publishes the catalog paths into
`contracts/openapi/generated/openapi.yaml` (lines 311, 380, 426 and 495) and the
generated client, so the generated functions for these five commands currently
target 404 paths; the authoritative `contracts/openapi.yaml` does not list them. `CommandAuthorization.authorize` has no production
caller and no durable decision sink: the authorization gate is inert in running
controllers, so catalog registration alone does not enforce authorization at
runtime. These two facts are recorded in `contracts/openapi/README.md`,
`docs/reference/modules/api-api.md` and
`docs/decisions/0003-persistence-of-in-memory-stores.md`.

Five bounded tasks would close the gap: one per family to route or retarget the
catalog path onto the live controller with a passing HTTP proof (sign,
acknowledge, release, exchange), and one to wire `CommandAuthorization` into the
controllers with a durable, tenant-scoped decision sink. Closing the generated
client drift belongs with the four routing tasks. None of this work is in this
story's two-file scope. No `@Disabled` placeholder is added: adding one now
would fail `scripts/check-constraints.py` and the quality gate; the 404 test
records the missing behavior in a gate-compliant, enabled form.
