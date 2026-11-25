# READY-41 · Durable authorization decision ledger (OPS-02)

PR #1706, tested branch tip 527e68d77e6c477e6453871836b0e18e83435fe7.

**Draft: the worker did not finish cleanly** (agent result: timeout; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (failing commands and final lines):

```text
AssertionError: 1 != 130 : cat: .agent/base-sha: No such file or directory
AssertionError: 1 != 143 : cat: .agent/base-sha: No such file or directory
```

Task: READY-41

Swarm worker 4. Local checks: failed. Agent result: timeout.

# READY-41 · Durable authorization decision ledger (OPS-02)

## Summary

Provide a production, durable sink for `CommandAuthorization.DecisionSink` so every
authorization decision is append-only evidence in PostgreSQL. Tests keep the synthetic
`InMemoryDecisionSink`; production receives `JdbcDecisionSink` from a Spring
`@Configuration`. An audit write failure denies the request with `AUDIT_UNAVAILABLE`.

## Affected requirements

- OPS-02 — durable, append-only audit of authorization decisions with actor, action,
  outcome, scope, correlation id and timestamp; no request bodies or patient fields.
- ARC-04 — the command gate `CommandAuthorization` records its decision before the
  caller is answered, including denial paths.

## Risk class

Cross-boundary and safety-critical: new tenant table with forced RLS, authorization
failure semantics and a production Spring bean. No public HTTP route is added in this
story; the HTTP gate is owned by the dependent AUTH-WIRE-01 task.

## Review owner

Senior backend, senior database, senior Spring security, senior Java build/test tooling
and DevSecOps (consulted as read-only specialists through the quality loop).

## Loaded skills (task evidence)

- `aurevia-delivery` — delivery loop applies to every story.
- `aurevia-senior-backend` — Java/Spring JDBC command-authorization change.
- `aurevia-senior-java-build` — JaCoCo, SpotBugs and Javadoc conventions.
- `aurevia-senior-spring-security` — authorization failure and audit semantics.
- `aurevia-senior-database` — new Flyway table, RLS and append-only policy.
- `aurevia-context-optimizer` — bounded context and requirement-ID mapping.
- `aurevia-test-engineer` — red tests first and 100% branch coverage.
- `aurevia-plan-reviewer` — plan gate before code.
- `aurevia-code-reviewer` — post-code review gate.
- `aurevia-coverage-auditor` — branch-to-test mapping gate.
- `aurevia-devsecops` — defensive security review gate.

## Brief disposition

The brief is accurate about the starting points, the reserved V65 and the dependent
task boundaries. Two corrections are recorded here:

1. The brief asks the worker to run `git merge`/commit. The durable directive in
   `.opencode/config/MEMORY.md` (session constraint: no Git or Git-backed operations)
   and the ORCA agent instruction "never run Git commands" take precedence over advisor
   guidance. No Git command, push, tag or `gh` call was made; workspace edits are handed
   to the trusted integrator.
2. The story text names `docs/pr/notes/<task-id>.md`; the prompt's notes path wins, so
   all evidence is written to this file only.

Evidence source for discoveries so far: executed file reads and greps (tool evidence),
plus read-only specialist reports (reviewer evidence). No simulated-user or human
feedback was used.

## Data changes

Pending: `db/migrations/V65__authorization_decisions.sql`, described in the migration
section below once reviewed.

## Authorization boundaries and audit events

Pending implementation evidence.

## Migration and rollout considerations

Pending implementation evidence.

## Test evidence

Pending. Exact commands and final lines will be pasted here.

## Coverage

Pending. Every new branch will be named with its test.

## Clinical and architecture review

No clinical data is handled: the ledger stores coded authorization facts only. The
architecture boundary is the `DecisionSink` port; the HTTP gate that calls it is a
separate dependent story.

## Review verdicts

Pending post-code reviews.

## Findings

Pending.

## Lessons for workers

Pending.
