<!-- PROJECT_V1_SPEC.md section 11: fix pass for GATE-DECISION-SITE-LAUNCH-APPROVALS. Synthetic data only. -->

## Summary

Fix-pass verification of `docs/launch/site-launch-approvals.md`, the G5 decision
pack added in the base commit (`9d221b54`). No earlier-run findings were included
in this worker's prompt, so I independently re-checked the committed document
against every acceptance criterion in
`tasks/00303000-GATE-DECISION-SITE-LAUNCH-APPROVALS.md`, the G5 row of section 9,
section 10 and OPS-01/MSG-06 of `PROJECT_V1_SPEC.md`, and the real repository
file tree.

Result: the document already satisfies all criteria. No acceptance defect was
found and no change to the decision pack was necessary. This fix pass therefore
adds only this notes file (the committed pull-request description); it does not
alter, weaken or reword the decision pack. The decision pack still decides
nothing, invents no jurisdiction/region/partner/name/date, marks the status
**Not approved** and leaves every approval-record cell blank.

## Affected requirements

- OPS-01 — policy pack, jurisdiction/region and owners, HIPAA conditions before ePHI.
- MSG-06 — service hours, response targets, named queue owner, backup coverage,
  after-hours/next-handling, supervisor visibility, approved urgent/downtime channels.
- G5 gate row (section 9) and the six section-10 accountable-role decisions.

## Acceptance mapping

| # | Criterion (task step 2/3) | Where satisfied | Verdict |
|---|---|---|---|
| 1 | Per site: workflow, release policy, market constraints, integrations, identity procedures, staffing, downtime drills | `## Decision needed` lines 14–19; `## Evidence checklist` "Per site" lines 186–203 (one unchecked box for each of the seven artifacts) | Pass |
| 2 | Messaging: patient eligibility rules, inbox ownership, after-hours guidance, backup coverage | `## Decision needed` lines 20–22; `## Evidence checklist` "Per messaging unit" lines 207–215 | Pass |
| 3 | Exact heading `## Decision needed` | line 12 | Pass |
| 4 | Exact heading `## Why it matters` (cites requirement IDs) | line 30; OPS-01 lines 32–38, MSG-06 lines 39–46, G5/section 10 lines 47–50 | Pass |
| 5 | Exact heading `## Options`, table with option / commitment / risks / evidence | line 52; table lines 57–61 with all four columns and three unselected options | Pass |
| 6 | Exact heading `## What the repository already supports` (links to files that exist) | line 63; all 37 file links resolved to existing paths (tool check below) | Pass |
| 7 | Exact heading `## What is missing` (task IDs or NO TASK) | line 134; S-G5-01/02/03/04/12, SPEC-* clause IDs, READY-11, the E2E job and NO TASK | Pass |
| 8 | Exact heading `## Questions for the owner` | line 164; seven owner questions | Pass |
| 9 | Exact heading `## Evidence checklist` (unchecked box per artifact) | line 180; 31 unchecked boxes, 0 checked | Pass |
| 10 | Exact heading `## Approval record` (section-10 roles; Name/Date/Decision empty) | line 224; table lines 231–238, six rows, all Name/Date/Decision cells empty | Pass |
| 11 | Status line "Not approved" | line 3 `Status: **Not approved**` | Pass |
| 12 | No option chosen, no invented jurisdiction/region/partner/name/date | whole file; the only jurisdiction mention ("a U.S. launch where HIPAA applies", line 34) quotes OPS-01 verbatim from the spec | Pass |
| 13 | `python3 -I scripts/check-docs.py` green | exit 0 (twice, before and after verification) | Pass |

## Risk class

Cross-boundary documentation for a safety-critical gate. The fix pass changed no
production boundary, schema, code path or approval state. The G5 decisions remain
human gates.

## Review owner

Product owner with clinical safety lead, privacy lead and integration lead as the
section-10 accountable roles. An independent bounded simulated `orevia-qa-reviewer`
review of this decision pack was run for this fix pass (session
`ses_e00dfc690ffeP6VQu8q4Q3y4vo`); its verdict, **PASS with no blocking, major or
minor acceptance defects**, is recorded under Findings. No human, clinical or
real-site review was performed or implied.

## Data changes

None. No migration, backfill, lock or volume impact. No reserved migration version
was used.

## Authorization boundaries and audit events

None added. Tenant, role and patient-link scope are unchanged. No audit event was
added or changed; the approval record is intentionally blank and a person
completes it at the G5 gate.

## Migration and rollout considerations

None. This is a documentation-only fix pass; it does not alter rollout, rollback
or release behaviour.

## Test evidence

Documentation-only change. No unit, integration, coverage or frontend gate applies
(the `apps/patient-mobile/check.sh` and `apps/staff-web/check.sh` frontend
definitions of done are not applicable — no frontend file was touched). Commands
actually executed, with final lines (tool evidence):

- `python3 -I scripts/check-docs.py` → exit **0**, no output. Run twice (baseline
  before review and authoritative gate at the end).
- `python3 - <<'PY' …` link-resolution check of every Markdown link in
  `docs/launch/site-launch-approvals.md` → 37 file links, all `OK` (0 `MISSING`).
- `grep -n '^## ' docs/launch/site-launch-approvals.md` → exactly the eight
  required headings, in the required order, at lines 12/30/52/63/134/164/180/224.
- `grep -c '^- \[ \]' …` → 31; `grep -c '^- \[x\]' …` → 0; approval-record
  lines 231–238 → every Name/Date/Decision cell empty.
- `git show --stat --oneline HEAD` → base commit `9d221b54` added only
  `docs/launch/site-launch-approvals.md` and its original notes file.
- `python3 -I scripts/check-names.py` → exit **0**.
- `python3 -I scripts/check-wiring.py` → exit **0**.
- `python3 -I scripts/check-secrets.py` → exit **0**.
- `python3 -I scripts/check-pack.py` → exit **1**, `check-pack: opencode.json is
  stale: run scripts/gen-opencode.py`. This is the known cloud-worker artifact,
  not a change defect: `git ls-files -v` reports `S opencode.json` (skip-worktree
  CI profile, 1829 lines) while HEAD holds the local profile (1769 lines). Per
  the delivery skill and MEMORY.md, the worktree file is the CI profile copied by
  `agent-run.sh`; the committed HEAD profile is the checked-in truth and was left
  byte-for-byte untouched. Evidence source: executed tool command.
- `test -d docs/spec/clauses` → missing, confirming the pack's "What is missing"
  statement that clause notes are owned by open tasks rather than linked files.

Entry-point-to-evidence trace: `PROJECT_V1_SPEC.md` §8 OPS-01/MSG-06, §9 G5
row and §10 → `docs/launch/site-launch-approvals.md` decisions/checklist/approval
record → `python3 -I scripts/check-docs.py` → exit 0.

Evidence source: executed tool commands and authoritative file reads (tool
evidence), plus the independent reviewer noted under Findings. No simulated-user
or human-participant evidence.

## Coverage

Not applicable: this fix pass adds one Markdown notes file and changes no
production code. There are no lines, branches, methods or classes to cover, and
no coverage threshold was changed, bypassed or excluded. No test was skipped.
`## Coverage` is retained as a required hand-off heading (AGENTS.md, Swarm
hand-off).

## Clinical and architecture review

No human clinical, privacy or architecture sign-off was obtained or implied.
The G5 approvals remain human gates. No code, migration or contract changed, so
no clinical/architecture code review applies.

## Findings

- No acceptance defect was found in `docs/launch/site-launch-approvals.md`; every
  criterion in the task is met and the authoritative docs check is green. No
  decision-pack edit was needed, so none was made.
- Independent simulated QA review (`ses_e00dfc690ffeP6VQu8q4Q3y4vo`): PASS on all
  five review checks — coverage of all seven site and four messaging artifacts,
  the eight exact headings and four-column options table, blank approval record
  with status "Not approved", defined requirement IDs matching spec §§8/9/10, and
  static link/ID validity. Suggested minimal fix: none. The reviewer had no
  execute tool, so it could not run `check-docs.py` itself; I ran the authoritative
  command (exit 0) as recorded under Test evidence. Its one non-defect note was
  that the "a U.S. launch where HIPAA applies" wording (line 34) is a conditional
  restatement of OPS-01, not an invented jurisdiction — a view I share.
  Evidence source: simulated reviewer (file reads), corroborated by my tool runs.
- Environment artifact (not a change defect): the worktree `opencode.json` is the
  skip-worktree CI profile, so `check-pack.py` reports it stale. HEAD's local
  profile was not regenerated or modified.
- Source-of-truth discrepancy (recorded, not changed): `tasks/00303000-GATE-DECISION-SITE-LAUNCH-APPROVALS.md`
  step 1 names `S-G5-12` as the human decision, while the generated `TODO.md`
  view of the same entry names `S-G5-04`. The pack references `S-G5-12`, which is
  the real open task in TODO.md (line 2294) and the task file's own wording; this
  is consistent with the authoritative task file. No file outside scope was
  edited to reconcile it.
- `check-docs.py` derives defined requirement-ID prefixes from spec table rows;
  OPS-01 and MSG-06 are both defined table rows and are cited correctly in
  `## Why it matters`.

## Lessons for workers

- When a fix pass arrives with no findings in the prompt, re-verify the committed
  file against each acceptance criterion and the real file tree before editing;
  a correct decision pack should not be reworded merely to produce a diff.
- `opencode.json` marked `S` by `git ls-files -v` is the CI profile: a stale
  `check-pack.py` result there is an environment artifact, not a reason to
  regenerate the committed local profile.
- A decision pack that prepares a human gate must keep status "Not approved",
  every Name/Date/Decision cell empty, and list genuinely missing evidence as
  task IDs or NO TASK; a passing synthetic check is never site approval.

## Why this is not finished

Not applicable — the fix pass is complete. The decision pack satisfied all
acceptance criteria at the base commit, the authoritative docs gate is green,
and this notes file is the required deliverable for the pass.
