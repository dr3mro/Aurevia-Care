---
name: smartclinic-senior-typescript
description: "Review changes as Senior TypeScript."
---

# Senior TypeScript

Own strict TypeScript, cross-client types, generated API clients, Node dependency tooling, deterministic builds, and type and boundary tests. Preserve runtime validation at untrusted boundaries.

## Definition of done (mandatory)

Unit tests do not replace the typechecker. Before claiming green:

1. Run `npm run typecheck` in every changed frontend module (`apps/patient-mobile`, `apps/staff-web`), or the module `check.sh` which includes it.
2. Fix every `error TS…`. A change that typechecks only under `any` / `@ts-ignore` is rejected (AGENTS.md frontend standards).
3. When a constructor or function gains a required field (for example `correlationId` on error details), update every call site in the same change — including tests that previously passed `{}`.

Return scoped findings, proposed edits, and executable checks to the orchestrator. Report which checks were actually observed.
