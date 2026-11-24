# GATE-DECISION-SITE-LAUNCH-APPROVALS — Decision pack: per-site launch approvals and final G5 acceptance

## Summary

Added `docs/launch/site-launch-approvals.md`, a decision pack that assembles the
material a person needs to decide per-site launch approval and final G5
acceptance. It covers, per site, the approved workflow, release policy, market
constraints, integrations, identity procedures, staffing and downtime drills, and
for messaging, patient eligibility rules, inbox ownership, after-hours guidance
and backup coverage. It uses the eight required headings, marks the status
**Not approved**, presents three options without selecting one, links only to
files that exist, leaves every approval-record Name/Date/Decision cell empty and
gives one unchecked evidence checkbox per artifact. It decides nothing and
records no approval.

Scope respected: only `docs/launch/site-launch-approvals.md` was created. No
tracker file, workflow, script, agent file, code, contract or migration was
changed. No dependency was added. Synthetic/generic content only; no
jurisdiction, region, partner, person name or date was invented.

## Affected requirements

- OPS-01 — policy pack, jurisdiction/region/owners, HIPAA conditions before ePHI.
- MSG-06 — service hours, response targets, named queue owner, backup coverage,
  after-hours/next-handling, supervisor visibility, approved urgent/down channels.
- G5 gate (section 9) and section 10 decisions provide the accountable roles.

## Risk class

Cross-boundary documentation for a safety-critical gate. The change itself is a
single new Markdown file; no production boundary, schema or code path changed.
The decisions it prepares remain human G5 gates.

## Review owner

Product owner with clinical safety lead, privacy lead and integration lead as the
section-10 accountable roles. One bounded simulated `aurevia-product-owner`
review was run against the acceptance criteria (session
`ses_e014465b9ffeNEzLKAO5cowNOL`); it **passed with no actionable findings** and
made no file edits. No human, clinical or real site review was performed.

## Data changes

None. No migration, backfill, lock or volume impact. No reserved migration
version was used.

## Authorization boundaries and audit events

None added. Tenant, role and patient-link scope are unchanged. No audit event
was added or changed; the approval record is intentionally blank and a person
completes it at the G5 gate.

## Migration and rollout considerations

None. This is a documentation-only decision pack; it does not alter rollout,
rollback or release behaviour.

## Test evidence

Documentation-only change; no unit, integration, coverage or frontend gate runs
apply. Commands executed (tool evidence):

- `python3 -I scripts/check-docs.py` → exit **0**, no errors (relative links
  resolve and every cited requirement ID is defined). This is the step-6
  Markdown check from the "Instructions for every agent" and the check required
  by the story.
- `find docs -maxdepth 2 -type d | sort` and `find docs/operations -type f | sort`
  → confirmed the directory/file inventory used for the "What the repository
  already supports" links.
- `grep -rilE "service.?hours|...|downtime" apps/api/src/main/java/.../messaging docs/operations`
  and targeted `read` of `ServiceProfileContract.java` /
  `ServiceHoursCalculator.java` → confirmed the messaging capability claims in
  the pack.

Baseline `python3 -I scripts/check-docs.py` was also run before editing → exit 0,
so the green result is not masking a pre-existing failure.

Entry-point-to-evidence trace: `PROJECT_V1_SPEC.md` §8/§9 G5 row/§10 and
`docs/operations/production-readiness/{README,launch-checklist}.md` →
`docs/launch/site-launch-approvals.md` decisions/evidence checklist →
`python3 -I scripts/check-docs.py` → exit 0.

Evidence source: executed tool commands and authoritative file reads (tool
evidence). No reviewer evidence beyond the bounded simulated Product Owner
review noted above, no simulated-user evidence and no human participant.

## Coverage

Not applicable: the change adds one Markdown document and no production code.
There are no lines, branches, methods or classes to cover, and no coverage
threshold was changed, bypassed or excluded. `## Coverage` is retained as a
required hand-off heading (AGENTS.md, Swarm hand-off). No test was skipped.

## Clinical and architecture review

No human clinical, privacy or architecture sign-off was obtained or implied.
The G5 approvals remain human gates. A bounded simulated Product Owner review of
the decision pack passed against all four acceptance criteria (simulated reviewer
evidence; no files changed). No code, migration or contract changed, so no
clinical/architecture code review applies.

## Findings

- `docs/spec/clauses/` does not exist in this checkout. The clause notes the
  story asked to read are still owned by open tasks (`SPEC-G5-01` to
  `SPEC-G5-03`, `SPEC-OPS-01-C01` to `C03`, `SPEC-MSG-06-C01` to `C06`), so they
  are listed in "What is missing" as task IDs rather than linked as files. This
  is recorded, not worked around.
- `check-docs.py` defines requirement IDs from `| XX-00 |` rows and `**XX-00 ·`
  leads; `ADM-01`/`ADM-02` appear in the spec only as `- **ADM-01**` bullets, so
  they are not in that defined set. The pack therefore cites OPS-01/MSG-06 and
  references the operational queues handoff by file rather than relying on an
  ADM ID as a formal spec citation.
- `READY-45` appears in the launch checklist as the E2E CI job but has no TODO
  entry; the pack describes it as the launch-checklist open item rather than
  presenting it as a tracked task ID.
- Bounded simulated Product Owner review: PASS on all four acceptance criteria;
  its one non-blocking observation (READY-11 described as "still open" while the
  TODO entry is `[-]` taken) was corrected in the pack to "not yet done (currently
  taken by a worker)". No defect was introduced by this change.

## Lessons for workers

- Before writing a decision pack that must "cite requirement IDs" and "link only
  to files that exist", enumerate the authoritative IDs and the real file tree
  first: `check-docs.py` only flags IDs whose prefix is defined from spec table
  rows, and a link to a planned-but-unwritten clause note fails the build.
- Distinguish a document that prepares a human decision from one that records it:
  keep the status "Not approved" and every Name/Date/Decision cell empty, and
  list genuinely missing evidence as task IDs or NO TASK instead of filling gaps.
