---
name: orevia-senior-typescript
description: "Reviews strict TypeScript, shared types and generated clients. Use when a change touches TypeScript in any app, a generated client, a shared type or a Node dependency."
---

# Senior TypeScript

Own strict TypeScript, cross-client types, generated API clients, Node dependency tooling, deterministic builds, and type and boundary tests. Preserve runtime validation at untrusted boundaries.

## Definition of done (mandatory)

Unit tests do not replace the typechecker. Before claiming green:

1. Run `npm run typecheck` in every changed frontend module (`apps/patient-mobile`, `apps/staff-web`), or the module `check.sh` which includes it.
2. Fix every `error TS…`. A change that typechecks only under `any` / `@ts-ignore` is rejected (AGENTS.md frontend standards).
3. When a constructor or function gains a required field (for example `correlationId` on error details), update every call site in the same change — including tests that previously passed `{}`.

Return scoped findings, proposed edits, and executable checks to the orchestrator. Report which checks were actually observed.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| It typechecks under `any`. | That is rejected. Fix the type. |
| I added a required field, the tests will tell me. | Update every call site in the same change, including tests that passed `{}`. |
| Runtime validation is redundant with types. | Untrusted boundaries keep runtime validation; types do not exist at runtime. |

## Red flags

- A public unit, script or migration with no doc comment, or a comment that only repeats the code instead of saying why.
- `@ts-ignore`, `as any` or a widened type.
- A changed shared type with an unchecked consumer app.
- A dependency added with no lock file change.

## Verification

- [ ] New or changed public units carry doc comments (purpose, inputs, outputs, errors, invariant) and tricky code explains why.
- [ ] `npm run typecheck` or the module `check.sh` passes in every changed module.
- [ ] No new `any` or ignore comment.
- [ ] Boundary inputs are validated at runtime.
