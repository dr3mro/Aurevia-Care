# IAM-01 clause: users explicitly switch active organization

## Clause

Users explicitly switch active organization.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.authz.clauses.UsersExplicitlySwitchActiveOrganizationTest`
(under `apps/api/src/test/java/com/aurevia/authz/clauses/`).

- `sameUserExplicitlySwitchesActiveOrganizationBetweenRequests` — the rule being met. One synthetic
  subject entitled to two organizations sends a different `X-Active-Organization` header on two
  requests; the HTTP probe confirms the bound organization changes from the first to the second
  while the subject stays the same, and that the context is cleared after each request.
- `switchToOrganizationNotEntitledByTokenIsForbidden` — negative: selecting an organization outside
  the verified token's organization claims is refused with `403 ORGANIZATION_NOT_PERMITTED`.
- `switchWithoutActiveOrganizationHeaderIsRejected` — negative: omitting the explicit selection is
  refused with `422 INVALID_TENANT_SELECTION` (never defaulted to a first membership).
- `switchWithMalformedActiveOrganizationHeaderIsRejected` — negative: a non-UUID selection is
  refused with `422 INVALID_TENANT_SELECTION`.

Production classes exercised (read-only — none were changed by this task):

- `com.aurevia.web.TenantContextInterceptor` — binds the selected tenant on every
  `/api/v1/**` request and clears it at completion.
- `com.aurevia.web.ClaimsTenantResolver` — validates the explicit selection against the
  verified token's `orgs` claim.
- `com.aurevia.web.AuthenticatedPrincipal` — freezes the set of selectable organizations.
- `com.aurevia.tenant.TenantContext` — the bound request tenant observed by the probe.

The clause is realized as a stateless, per-request explicit selection rather than a persistent
"switch" action: the caller names the active organization on each protected request and the tenant
boundary revalidates it, so the choice is never inferred from the body, a resource identifier or a
first membership. This matches the recorded IAM-01 decision in `PROJECT.md`.

## Gap

None.
