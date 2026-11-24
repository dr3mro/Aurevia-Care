# GATE-DECISION-POLICY-PACK — Market policy pack decision pack

Task: `GATE-DECISION-POLICY-PACK` (TODO.md:2876), swarm worker `swarm-6`.
Human decision it prepares: `S-G5-01` (TODO.md:1483, still open).
Branch: `swarm/GATE-DECISION-POLICY-PACK-447` (HEAD `393b42b` at start).
Status of deliverable: **Not approved** — the pack decides nothing.

## Summary

Added `docs/launch/policy-pack.md`, a decision pack for a person to approve covering
launch jurisdiction, hosting region and pilot scope; applicable law; retention and
legal hold; patient release rules; incident notification; supplier obligations;
prescribing constraints; and the conditional U.S./HIPAA risk analysis and agreements.
It contains all eight required headings, neutral options with no selected option, no
invented jurisdiction/region/partner/name/date, an all-unchecked evidence checklist
and an empty approval record. No code, contract, migration, workflow, dependency or
tracker file was changed. The document explicitly records that it is not legal advice
and that synthetic repository evidence is not approval evidence.

## Affected requirements

- OPS-01 (primary) — hosting region, applicable law, retention/legal hold, patient
  release, incident notification, supplier obligations, prescribing constraints;
  U.S./HIPAA risk analysis, agreements and vendor scope; jurisdiction-specific
  requirements and owners.
- PRD-05 — one market per production deployment with a market-specific policy pack.
- Acceptance mapping (task step 2 heading → location in `docs/launch/policy-pack.md`):
  status "Not approved" → line 3; `## Decision needed` → all nine topics listed;
  `## Why it matters` → OPS-01/PRD-05 plus section 9 G5 row and section 10 roles;
  `## Options` → 11-row table (Option / What it commits the platform to / Risks /
  Evidence each needs); `## What the repository already supports` → 12 linked items
  with proof/boundary statements; `## What is missing` → 15 rows with task IDs or
  NO TASK; `## Questions for the owner` → 9 neutral questions with no answers;
  `## Evidence checklist` → 24 unchecked artifacts; `## Approval record` → 6 section 10
  rows with empty Name, Date and Decision cells.
  Acceptance constraint "decide nothing / invent nothing": the document uses only
  archetypes ("one named jurisdiction", "the named region"), never a real value.

## Risk class

Cross-boundary (docs/WORKFLOW.md): the pack spans privacy, operations, hosting and
clinical/prescribing boundaries. It is safety-adjacent but makes no change to a live
system and records no clinical or human decision. Human G5 acceptance is required.

## Review owner

Product owner and privacy lead (section 10, accountable for this decision). Related
roles from section 10 are listed in the approval record: clinical safety lead and site
medical director; privacy lead and clinical safety lead; integration lead and partner
owner; engineering and operations; product, clinical, engineering and privacy owners.

## Data changes

None. No migration, backfill, schema, lock or volume impact. Reserved migration
version untouched. No patient data, credential, real domain name or real identifier
appears in the document (verified by `scripts/check-secrets.py`, exit 0).

## Authorization boundaries and audit events

No authorization logic or audit event added. The pack describes, for a human to
approve, the tenant/site/region scope, named roles and production-access discipline
that the existing controls enforce (RLS isolation, OPS-07 retention/hold ledgers,
OPS-06 audited expiring access). No approval is implied or recorded.

## Migration and rollout considerations

Not applicable to a documentation-only change. The pack asks the owner to decide the
future hosting region, residency, retention and rollout evidence; it does not perform
or authorize a deployment.

## Test evidence

Documentation-only change; no executable tests added or changed. Commands run (all
from repository root, tool evidence):

- `python3 -I scripts/check-docs.py` → exit 0 (no output). Final line: `EXIT: 0`.
  This is the required step-6 check for a Markdown edit. It validated that every
  relative link in `docs/launch/policy-pack.md` resolves to an existing file and every
  requirement ID mentioned is defined in `PROJECT_V1_SPEC.md`.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-names.py` → exit 0.

Entry-point-to-test trace: `docs/launch/policy-pack.md` (entry point) →
`scripts/check-docs.py` `markdown_files()`/`check()` (relative-link and requirement-ID
validation) → exit 0; the linked specification, operation handoffs, decisions, infra
handoff and Java source files were each verified to exist via tool reads/globs before
linking. Final re-run of all three checks after the last edit: exit 0 / 0 / 0.

Deliberate omission: `scripts/check.sh` was NOT run. It chains `apps/api/check.sh`
(full Maven `mvn verify` with JaCoCo/OWASP/SpotBugs), the frontend checks and
`db/check.sh` (all integration tests). The task instruction forbids running the full
Maven verify or any command that runs every integration test (over ten minutes,
ends the run as a timeout). For a docs-only change the canonical fast checks above
are sufficient; the integrator and Delivery run the full gate. No frontend check was
needed: no TypeScript or frontend file was touched.

## Coverage

Not applicable — documentation-only change. No production line, branch, method or
class was added or changed, so there is no new branch to cover and no coverage measure
to run. No coverage threshold, exclusion or ignore comment was added or weakened; no
test was skipped. No fabricated coverage result is claimed.

## Clinical and architecture review

Not performed here and not implied. The pack explicitly routes clinical templates,
code sets, interpreting privileges and escalation to the clinical safety lead and site
medical director, result release and sensitive information to the privacy and clinical
safety leads, and interface contracts to the integration lead and partner owner. These
are open human G5 decisions (`S-G5-02`, `S-G5-03`, `S-G5-04`, `S-G5-12`), not evidence
supplied by this document.

## Findings

- `docs/spec/clauses` does not exist in the repository at the time of writing, although
  the task step 1 asks for clause notes under it and several `SPEC-...` tasks target it
  (for example `SPEC-OPS-01-C01` → `docs/spec/clauses/policy/...`). No clause note was
  available to read. This is recorded in the pack under `## What is missing`, and the
  affected tasks remain open. No value was invented to fill the gap.
- No approved market policy pack, data handling map, hosting region, jurisdiction,
  cloud account, infrastructure-as-code or supplier register exists in the repository.
  The only `policy` manifest is the synthetic OPS-06 declaration at
  `infra/delivery-safety/delivery-safety-policy.json`; the pack says explicitly that it
  is not a market pack. `docs/decisions/0002-ops06-migration-change-safety.md` confirms
  environment separation, signed builds, rollout and production access were deferred.
- The retention/legal-hold and export docs define enforcement *form* only; their
  "allowed by policy" document-authorization tuple is a code stand-in, not a live
  policy binding. The pack does not claim otherwise.
- `operational-runbooks.md` covers outage handling, not statutory breach-notification
  deadlines; the pack keeps those as separate open owner questions and checklist items.
- `S-G5-05` … `S-G5-11` appear only in a stale wave table in TODO.md, not as task
  entries, so they were not cited as real task IDs. The gap rows use verified IDs
  (`S-G5-01`, `S-G5-03`, `S-G5-04`, `S-G5-12`, `SPEC-OPS-01-C01/02/03`,
  `SPEC-PRD-05-C01/02`, `SPEC-RX-02-C01/02`, `SPEC-XCH-03-C05`, `BUILD-10`, `BUILD-16`,
  `BUILD-40`, `BUILD-42`, `START-04`, `START-15`, `START-91`, `READY-54`) or NO TASK.
- Evidence provenance: executed tool commands (checks, file reads, `git status`) for
  repository facts; bounded read-only specialist reviews from `aurevia-devsecops`
  and `aurevia-senior-cloud-iac` (simulated specialist review, not human evidence)
  for coverage of the questions/options/evidence checklist; no simulated-user or human
  participant evidence. No clinical, legal or deployment approval is claimed.

## Lessons for workers

- For a decision pack, keep every option a neutral archetype and leave the jurisdiction,
  region, partner, names and dates as owner questions. Adding any concrete value turns a
  decision pack into a decision and breaks the task's core constraint.
- Copy the required heading list verbatim into the file before drafting; `check-docs.py`
  validates links and IDs but not heading presence or the "Not approved"/empty-record
  rules, so those need a manual final read-through.
- `check-docs.py` counts every defined spec prefix, so an invented ID like `OPS-09`
  fails the build; verify task IDs with `grep` on TODO.md before citing them. Several
  IDs in the wave table are stale and are not real entries.
- For docs-only swarm tasks, run the fast Python checks (`check-docs`, `check-secrets`,
  `check-names`), not `scripts/check.sh`: the latter chains the full Maven verify and
  all integration tests and will time out the worker.
- A missing referenced directory (`docs/spec/clauses`) is a finding, not a reason to
  guess: record it under `## What is missing` in the deliverable and keep the tasks open.
