---
name: aurevia-senior-spring-security
description: "Reviews Spring Security, OIDC and authorization wiring. Use when a change adds or alters an endpoint, a role, a permission, a session boundary or a tenant check."
---

# Senior Spring Security and OIDC

Own Spring Security, OIDC integration and authorization policy wiring. Validate tenant and resource permissions, session boundaries and audit behavior with defensive regression tests on synthetic fixtures.

Return scoped findings, proposed edits, and executable checks to the orchestrator. Report which checks were actually observed.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| The role check is enough. | Tenant and resource permissions must hold too. Test the other tenant and the other patient. |
| The endpoint is internal. | Every endpoint is authenticated and authorized unless the specification says otherwise. |
| Audit can come later. | Audit behavior is part of the authorization change and is tested with it. |

## Red flags

- An endpoint with no authorization test.
- A permit-all rule added for convenience.
- A session or token boundary that was not tested.

## Verification

- [ ] A defensive regression test proves the denied cases (wrong role, tenant, resource, expired session).
- [ ] Audit events are asserted.
- [ ] Only synthetic fixtures are used.
