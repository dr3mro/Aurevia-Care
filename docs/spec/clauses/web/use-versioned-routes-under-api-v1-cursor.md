# ARC-03 clause 1 — Versioned routes, cursor pagination, correlation IDs and machine-readable errors

## Clause

Use versioned routes under `/api/v1`, cursor pagination with stable tenant-scoped ordering, request/correlation IDs and machine-readable errors.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.web.clauses.UseVersionedRoutesUnderApiV1CursorTest`
(`apps/api/src/test/java/com/aurevia/web/clauses/UseVersionedRoutesUnderApiV1CursorTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the
`synthetic-auth` profile and two synthetic organizations (`ORG_A`, `ORG_B`) with
the fixtures and database setup of the neighbouring
`it.staff.StaffListIntegrationTest`. A dedicated database
`orevia_arc03_clause1_it` is migrated from `db/migrations` and dropped after
the suite. Every method sends synthetic headers (`X-Synthetic-Subject`,
`X-Active-Organization`, and a fixed UUID `X-Correlation-Id`) through a real
`RestClient` and asserts an HTTP status, a response header or a JSON body field.

Test methods:

- `versionedRoutesCursorPagingAndMachineReadableErrorsAreProven` — `GET
  /api/v1/staff?limit=2` with a valid UUID correlation header returns 200 with
  the first two subjects in stable subject order, a non-null opaque `nextCursor`
  and the correlation id echoed in `X-Correlation-Id`. Following the cursor once
  returns the next two rows with no duplicates. A malformed cursor returns 422
  with `code` `INVALID_CURSOR` and the same `correlationId` in the error body and
  response header. This proves the versioned prefix, stable tenant-scoped cursor
  paging, correlation-id round trip and the machine-readable error contract
  together through one entry point.
- `unversionedRouteIsNotFound` — `GET /staff` is not routed and returns 404,
  proving the `/api/v1` prefix branch.
- `otherTenantsRowsAreNeverReturned` — an `ORG_B` list contains only `-b`
  subjects, and `admin-a` selecting `ORG_B` is refused with 403, proving that
  paging is tenant-scoped and fails closed.
- `wrongRoleIsRefusedMachineReadably` — `reception-a` is refused with 403 and a
  `{code, message, correlationId}` body carrying `FORBIDDEN` and the caller's
  correlation id; the body exposes no staff subject or organization identifier.
- `unsupportedVersionIsNotEnforcedGap` — an `X-Api-Version: v2` header is
  accepted today (200, no error code). This enabled characterization records the
  known gap instead of asserting behavior that does not exist; see Gap.

Production artifacts exercised (all pre-existing and unchanged):

- Routing: `com.aurevia.web.StaffController` (`GET /api/v1/staff`,
  `@RequestMapping("/api/v1/staff")`), registered under `/api/v1/**` by
  `com.aurevia.web.TenantContextInterceptor.addInterceptors`.
- Paging: `com.aurevia.staff.StaffService` (`parseCursor`,
  `parseLimit`, `parseStatus`), `com.aurevia.staff.StaffRepository`
  (keyset predicate with `ORDER BY subject, id` under forced row-level security)
  and `com.aurevia.staff.StaffPage` (`items`, `nextCursor`).
- Correlation: `com.aurevia.observability.CorrelationIdFilter`
  (`CORRELATION_HEADER`, valid-UUID reuse and response echo) and
  `com.aurevia.web.TenantContextInterceptor` (allowlist validation,
  echo and `TenantContext` binding).
- Errors: `com.aurevia.api.ApiErrorHandler` (`ErrorBody` with
  `code`, `message`, `correlationId`), `com.aurevia.api.ApiException`,
  and `com.aurevia.api.contract.ApiErrorCode` (`INVALID_CURSOR`,
  `FORBIDDEN` is raised by the staff domain service).
- Version contract only: `com.aurevia.api.contract.ApiVersion`
  (`from`) and `UNSUPPORTED_API_VERSION` exist but are not reached over HTTP.

## Gap

Version negotiation is not enforced over HTTP. `ApiVersion.from` has no
production caller; no filter or interceptor reads `X-Api-Version`, so a stale
`v2` header is silently ignored and `UNSUPPORTED_API_VERSION` is unreachable from
any request. Versioning today is prefix-only: an unknown path such as `/api/v2`
404s through Spring routing rather than returning the machine-readable error.
The complete desired test (`unsupportedVersionIsRefused`, expecting 422 and
`UNSUPPORTED_API_VERSION`) was written and observed red before being converted
to the enabled characterization above. It was not left `@Disabled`: AGENTS.md
forbids a skipped test and `scripts/check-constraints.py` fails any added
`@Disabled` line in the quality gate.

One separate cross-boundary task is needed: wire a filter or interceptor that
calls `ApiVersion.from(X-Api-Version)` before the handler (likely under
`apps/api/src/main/java/com/aurevia/web/`, with the existing
`TenantContextInterceptor` as the natural owner), add the HTTP negative test for
`X-Api-Version: v2`, and verify the regenerated client against
`contracts/openapi.yaml`, which already declares the `X-Api-Version` parameter.
No production code, contract or migration is changed by this test-only story.
