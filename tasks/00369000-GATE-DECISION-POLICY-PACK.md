- [x] GATE-DECISION-POLICY-PACK · Decision pack for a person to approve: Market policy pack, hosting region, jurisdiction and data handling map
  - pr: #373
  - attempts: 1
  - requirements: OPS-01, PRD-05
  - scope: docs/launch/policy-pack.md
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/launch/policy-pack.md`, `scripts/check-docs.py`.

    1. Read section 10 and the G5 row of section 9 of `SMARTCLINIC_V1_SPEC.md`, `docs/operations/production-readiness`, `docs/decisions` and the clause notes under `docs/spec/clauses` that relate to this decision. The decision in `TODO.md` task `S-G5-01` needs people; this task prepares everything they need and decides nothing.
    2. Write `docs/launch/policy-pack.md` covering: launch jurisdiction, hosting region and pilot scope; applicable law; retention and legal hold rules; patient release rules; incident notification; supplier obligations; prescribing constraints; and for a U.S. launch the HIPAA risk analysis and agreements. Use these headings: `## Decision needed`, `## Why it matters` (cite the requirement IDs), `## Options` (a table with the option, what it commits the platform to, risks and the evidence each needs), `## What the repository already supports` (links to files that exist), `## What is missing` (task IDs or NO TASK), `## Questions for the owner`, `## Evidence checklist` (an unchecked checkbox per artifact a site must hold) and `## Approval record` (accountable roles from section 10; Name, Date and Decision columns left empty).
    3. Do not choose an option, do not invent a jurisdiction, region, partner, name or date, and never fill the approval record. Mark the status line "Not approved". Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

