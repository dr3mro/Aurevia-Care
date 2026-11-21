- [x] GATE-DECISION-SITE-LAUNCH-APPROVALS · Decision pack for a person to approve: Per-site launch approvals and final G5 acceptance
  - pr: #372
  - attempts: 1
  - requirements: OPS-01, MSG-06
  - scope: docs/launch/site-launch-approvals.md
  - details:
    1. Read section 10 and the G5 row of section 9 of `SMARTCLINIC_V1_SPEC.md`, `docs/operations/production-readiness`, `docs/decisions` and the clause notes under `docs/spec/clauses` that relate to this decision. The decision in `TODO.md` task `S-G5-12` needs people; this task prepares everything they need and decides nothing.
    2. Write `docs/launch/site-launch-approvals.md` covering: per site: approved workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills; for messaging: patient eligibility rules, inbox ownership, after-hours guidance and backup coverage. Use these headings: `## Decision needed`, `## Why it matters` (cite the requirement IDs), `## Options` (a table with the option, what it commits the platform to, risks and the evidence each needs), `## What the repository already supports` (links to files that exist), `## What is missing` (task IDs or NO TASK), `## Questions for the owner`, `## Evidence checklist` (an unchecked checkbox per artifact a site must hold) and `## Approval record` (accountable roles from section 10; Name, Date and Decision columns left empty).
    3. Do not choose an option, do not invent a jurisdiction, region, partner, name or date, and never fill the approval record. Mark the status line "Not approved". Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

## Swarm pipeline audit work, split into tasks (script changes are protected; workflow changes are delivered as patches for a person)

The open remainder of `OPS-WORKFLOW-AUDIT` and `OPS-WORKER-QUALITY`. Script changes merge through the integrator (the kill switch opens an oversight issue). Workflow changes cannot be pushed by the swarm token, so those tasks write a proposed patch and a verification checklist under `docs/operations/workflow-changes`; a person applies and verifies them on GitHub. The two parent tasks stay open until then.

