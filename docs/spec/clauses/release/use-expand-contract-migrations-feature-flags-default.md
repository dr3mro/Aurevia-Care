# WEB-11 clause 4 — Use expand and contract migrations and feature flags that default off

## Clause

Use expand and contract migrations and feature flags that default off.

## Status

Partly built

## Evidence

The clause has two halves and they do not have the same status. The
expand/contract migration discipline is implemented and verified offline; the
feature flags that default off are not implemented, and the evidence that would
prove them is infrastructure-owner and human-gated. Section 8.1 of
[PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) says WEB-11 evidence
is produced with the infrastructure owner. The WEB-11 row of the
[production readiness register](../../../operations/production-readiness/README.md)
still reads "not started", owner "unassigned", and the corresponding row of the
[launch checklist](../../../operations/production-readiness/launch-checklist.md)
is `open`, with a runbook link but no evidence artifact attached.

Built and verified — expand and contract migrations:

- [scripts/check-migrations.py](../../../../scripts/check-migrations.py) is a
  deterministic offline gate. It requires unique migration versions, restricts
  the optional `Change` header to `expand` or `contract`, requires
  statement-start destructive DDL to declare `Change: contract`, and requires
  every contract to carry a `Forward-fix: V<n>__name.sql` reference plus a
  `Requires-expand: V<n>` pointing at an existing, earlier expand migration.
  Its header states that the declaration is the developer's and that human
  review of each contract remains required.
- The gate is wired where changes are actually checked:
  [scripts/check.sh](../../../../scripts/check.sh),
  [scripts/quick-check.sh](../../../../scripts/quick-check.sh) and the Delivery
  `policy` job in
  [.github/workflows/delivery.yml](../../../../.github/workflows/delivery.yml)
  each call `python3 -I scripts/check-migrations.py --require-baseline`. The
  baseline flag makes an empty or missing migration directory fail rather than
  silently disabling the check.
- The enforced rules are documented in
  [db/migrations/README.md](../../../../db/migrations/README.md) and decided in
  [decision 0002 — OPS-06 migration change-safety gate](../../../../docs/decisions/0002-ops06-migration-change-safety.md).
  Real migrations carry the declared headers; for example
  [V13__longitudinal_chart.sql](../../../../db/migrations/V13__longitudinal_chart.sql)
  declares `-- Change: expand`.
- [scripts/tests/test_check_migrations.py](../../../../scripts/tests/test_check_migrations.py)
  covers the gate, including the destructive-DDL forms, malformed headers, the
  missing expand reference and the comment/string/dollar-quote regressions.
  `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q`
  reported `Ran 35 tests in 0.087s` and `OK` (tool evidence, this checkout).
- Limit of this proof: the gate checks the *declaration* and its references,
  not that the SQL matches the label. No migration in the repository currently
  declares `Change: contract`; the first contract and its forward-fix are
  future work and still require human review.

Built as an evaluator only — declaration checks, not deployment evidence:

- The [delivery safety handoff](../../../operations/delivery-safety/README.md)
  defines three evidence tiers: contract, synthetic rehearsal and measured
  deployment. A passing synthetic artifact is labelled `final_evidence: false`
  and a `measured_deployment` claim is checked only for the shape of its run
  provenance, not for its truth. The
  [evidence contract](../../../operations/delivery-safety/evidence-contract.md)
  and
  [build and migration gates](../../../operations/delivery-safety/build-and-migration-gates.md)
  require an artifact to declare `migrations.expand_contract: true` and
  `migrations.forward_fix_required: true`.
- [infra/delivery-safety/delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py)
  evaluates those declarations and the rollout/rollback contract, and
  [infra/delivery-safety/check.sh](../../../../infra/delivery-safety/check.sh)
  runs the real migration checker instead of trusting the artifact. This is a
  manually run gate: no workflow or shared script invokes it (decision D8 in the
  handoff), and no conforming `measured_deployment` artifact exists in the
  repository. It proves bad declarations are rejected, not that a release used
  default-off flags or rolled back. Run here: `Ran 71 tests in 0.009s`, `OK`,
  `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed` (tool
  evidence, this checkout).

Not built — feature flags that default off:

- No flag framework, flag registry, default-off flag configuration or component
  that *performs* progressive rollout or automatic rollback exists under
  `apps/`, `.github/workflows/` or `infra/`. The delivery-safety evaluator
  checks rollout and rollback *declarations* only, as described above. No
  deployment step gates a release on a flag that is off by default.
- The only adjacent in-repository switches are
  [RejectionNotificationPolicy.java](../../../../apps/api/src/main/java/com/aurevia/labflow/rejection/RejectionNotificationPolicy.java),
  which injects `@Value("${orevia.lab.rejection.notify-patient:false}")`,
  and [DocsEnabled.java](../../../../apps/api/src/main/java/com/aurevia/web/DocsEnabled.java),
  which reads `orevia.docs.enabled` and keeps production docs off unless a
  profile or property enables them. Both are single-purpose switches, not
  WEB-11 rollout flags: neither stages a release, records a rollout or rollback,
  or is wired to deployment.
- The progressive rollout with automatic rollback is listed as `open` in the
  [launch checklist](../../../operations/production-readiness/launch-checklist.md),
  with a runbook link but no evidence artifact attached.
  [Release and recovery rules](../../../launch/release-and-recovery-rules.md)
  records per-site signed release policy as a human decision
  (`S-G5-12; otherwise NO TASK`).
  [Rollout and production access](../../../operations/delivery-safety/rollout-and-production-access.md)
  states explicitly that its evaluator cannot observe live health signals,
  confirm a rehearsed rollback or verify the approver's identity.

## Gap

Missing for the whole clause: (1) a feature-flag mechanism whose flags default
off and can only be flipped through a recorded, attributable change, with its
configuration and automated tests; (2) a progressive rollout at 5%, 25% and
100% with health-gated automatic rollback tied to the flag state; (3) the first
real `Change: contract` migration with its forward-fix and human review when a
destructive change is needed; (4) a rollback rehearsal record and a release
checklist with named approvers for the enabled site; and (5) a gate that runs
the delivery-safety evaluator on real deployment evidence, since it is
currently manual-only (decision D8 in the handoff).

Files that would change: `docs/operations/production-readiness/README.md` and
`docs/operations/production-readiness/launch-checklist.md` (status, evidence
link and owner, once the evidence exists), a new record under
`docs/operations/delivery-safety/`, and a future infrastructure workflow plus
application configuration for the flag framework. No task currently covers the
flag and rollout code, and a worker must not edit the workflow files here.

Number of separate tasks: at least two. First a code and infrastructure task
adds default-off flags and the rollout/rollback mechanism with automated tests
(none exists today). Only then can a separate human task produce the monitored
rollout record, the rollback rehearsal and the signed approver checklist.
Section 8.1 names the infrastructure owner for that evidence; a passing
repository check is not a substitute for it.

## How to verify

Repository checks a reviewer can run now. They prove only the expand/contract
half, not the default-off flag half:

- `python3 -I scripts/check-migrations.py --require-baseline` — exit 0 on the
  current migration set.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q`
  — `Ran 35 tests`, `OK`.
- `bash infra/delivery-safety/check.sh` — the manual evaluator gate: the
  synthetic fixture passes, both negative fixtures exit 1 and the real
  migration gate runs — observed here as `Ran 71 tests in 0.009s`, `OK`,
  `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`. It declares
  `final_evidence: false`; it is not wired into a workflow and proves no real
  rollout.
- `python3 -I scripts/check-docs.py` — exit 0 (no output on success); this
  note's links and requirement IDs are valid.
- `grep -rn "Change: contract" db/migrations/*.sql` — currently no production
  migration matches; a future destructive migration must declare it with
  `Forward-fix` and `Requires-expand`, then pass the gate and human review.
- `grep -rniE "feature[ _-]?flag|default[ _-]?off" apps/ .github/workflows/ infra/`
  — no rollout-flag implementation; the default-off wording belongs to the
  adjacent switches above, not to a rollout mechanism.

Human evidence a site must supply before G5, produced with the infrastructure
owner (no command or workflow run in this repository substitutes for it):

- a monitored rollout record showing 5%, 25% and 100% with health gates and
  automatic rollback on error-rate or latency breach, each flag's default-off
  state and the approval for every flip;
- a rollback rehearsal record and a release checklist with named approvers and
  effective dates, attached to the WEB-11 rows of the
  [production readiness register](../../../operations/production-readiness/README.md)
  and [launch checklist](../../../operations/production-readiness/launch-checklist.md),
  with the accountable individual recorded on the G5 acceptance record.
