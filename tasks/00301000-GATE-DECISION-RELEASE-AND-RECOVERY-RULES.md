- [x] GATE-DECISION-RELEASE-AND-RECOVERY-RULES · Decision pack for a person to approve: Result release rules, sensitive information handling and identity recovery approvals
  - pr: #368
  - attempts: 1
  - requirements: XCH-06, IAM-05
  - scope: docs/launch/release-and-recovery-rules.md
  - details:
    1. Read section 10 and the G5 row of section 9 of `SMARTCLINIC_V1_SPEC.md`, `docs/operations/production-readiness`, `docs/decisions` and the clause notes under `docs/spec/clauses` that relate to this decision. The decision in `TODO.md` task `S-G5-03` needs people; this task prepares everything they need and decides nothing.
    2. Write `docs/launch/release-and-recovery-rules.md` covering: release policies (immediate, timed, holds), sensitive-information handling, patient identity recovery and re-verification, with an enrollment rehearsal and an access review. Use these headings: `## Decision needed`, `## Why it matters` (cite the requirement IDs), `## Options` (a table with the option, what it commits the platform to, risks and the evidence each needs), `## What the repository already supports` (links to files that exist), `## What is missing` (task IDs or NO TASK), `## Questions for the owner`, `## Evidence checklist` (an unchecked checkbox per artifact a site must hold) and `## Approval record` (accountable roles from section 10; Name, Date and Decision columns left empty).
    3. Do not choose an option, do not invent a jurisdiction, region, partner, name or date, and never fill the approval record. Mark the status line "Not approved". Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

