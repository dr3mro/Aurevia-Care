# GATE-DECISION-POLICY-PACK — fix pass

Task: `GATE-DECISION-POLICY-PACK` (`tasks/00299000-GATE-DECISION-POLICY-PACK.md`),
swarm worker `swarm-6`. Human decision it prepares: `S-G5-01` (still open).
Branch: `swarm/GATE-DECISION-POLICY-PACK-447`; prior attempt commit `10d947b8`
(notes `docs/pr/notes/GATE-DECISION-POLICY-PACK-20261121-152819.md`).
Deliverable status: **Not approved** — this pack decides nothing.

## Summary

Fix pass over the decision pack created by the first attempt. The first attempt's
`docs/launch/policy-pack.md` was already complete against the eight required headings
and the "decide nothing / invent nothing" constraint, so no content was added, removed
or re-selected. One demonstrably stale statement was corrected: the first attempt
recorded that `docs/spec/clauses` did not exist. On current `origin/main` that directory
does exist (three product clause notes), so the sentence was updated to say the
directory exists while the OPS-01, PRD-05, RX-02 and XCH-03 clause notes the pack cites
are still absent. No market value was inferred from the product notes that now exist.
All options remain neutral archetypes, the status line remains "Not approved", the
evidence checklist stays fully unchecked and the approval record stays empty.

## Affected requirements

- **OPS-01** (primary) — hosting region, applicable law, retention/legal hold, patient
  release, incident notification, supplier obligations, prescribing constraints, and
  the conditional U.S./HIPAA risk analysis, agreements and vendor scope.
- **PRD-05** — one market per production deployment with a market-specific policy pack.

Acceptance mapping (task step 2 → `docs/launch/policy-pack.md`):

| Task requirement | Location in the deliverable |
|---|---|
| Status "Not approved" | line 3 |
| `## Decision needed` | all nine named topics, lines 11–29 |
| `## Why it matters` (cite requirement IDs) | OPS-01, PRD-05, PRD-06, RX-02, XCH-06; section 9 G5 row and section 10 roles, lines 31–60 |
| `## Options` (option / commitment / risks / evidence) | 11-row neutral table, lines 62–81 |
| `## What the repository already supports` | linked existing files with proof/boundary statements, lines 83–143 |
| `## What is missing` (task IDs or NO TASK) | 15 rows, lines 145–163; clause-directory statement fixed at lines 165–168 |
| `## Questions for the owner` | nine neutral, unanswered questions, lines 168–204 |
| `## Evidence checklist` | 24 unchecked artifacts, lines 206–234 |
| `## Approval record` (section 10 roles; Name/Date/Decision empty) | six section 10 rows, lines 236–249 |
| Decide nothing / invent nothing | archetypes only ("one named jurisdiction", "the named region"); no real jurisdiction, region, partner, name or date; record empty |

Verified section 10 (six decision rows and accountable roles) and the section 9 G5 row
of `PROJECT_V1_SPEC.md` against the document; they match verbatim in substance.

## Risk class

Cross-boundary (`docs/WORKFLOW.md`): the pack spans privacy, operations, hosting and
clinical/prescribing boundaries. It changes no live system and records no clinical or
human decision; G5 human acceptance remains required.

## Review owner

Product owner and privacy lead (section 10, accountable for this decision). Related
section 10 roles are listed in the approval record: clinical safety lead and site
medical director; privacy lead and clinical safety lead; integration lead and partner
owner; engineering and operations; product, clinical, engineering and privacy owners.

## Data changes

None. No migration, backfill, schema, lock or volume impact; the reserved migration
version is untouched. No patient data, credential, real domain name or real identifier
is present (verified by `scripts/check-secrets.py`, exit 0).

## Authorization boundaries and audit events

No authorization logic or audit event added. The pack describes, for a person to
approve, the tenant/site/region scope, named roles and production-access discipline
already enforced by the existing controls. No approval is implied or recorded.

## Migration and rollout considerations

Not applicable to a documentation-only change. The pack asks the owner to decide the
future hosting region, residency, retention and rollout evidence; it performs and
authorizes no deployment.

## Test evidence

Documentation-only change; no executable test added or changed. All commands run from
the repository root; results are tool evidence (commands executed in this session):

- `python3 -I scripts/check-docs.py` → exit 0 (no output). Final line: `EXIT: 0`.
  This is the task's required step-6 check for a Markdown edit. It resolved every
  relative link in `docs/launch/policy-pack.md` against files present on
  `origin/main` (all 21 link targets verified OK by a separate `git ls-tree` scan) and
  confirmed every requirement ID mentioned is defined in `PROJECT_V1_SPEC.md`.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-names.py` → exit 0.
- `git ls-tree -r --name-only origin/main docs/spec/clauses/` → three product clause
  notes (`ambulatory-care.md`, `closed-loop-orders-diagnostic-results-specialty-referrals.md`,
  `partner-specific-interfaces-require-approval.md`); no OPS-01/PRD-05/RX-02/XCH-03
  note. This is the evidence for the one-line correction.
- Generator cross-check (importlib of `scripts/gen-opencode.py`, compared with
  `git show HEAD:opencode.json` and `git show HEAD:.github/opencode.ci.json`):
  `HEAD opencode.json == generator local profile: True`,
  `HEAD .github/opencode.ci.json == generator CI profile: True`,
  `worktree opencode.json == generator CI profile: True`. This proves the committed
  file is current and that the local `check-pack.py` red result is the known
  skip-worktree CI-profile masking artifact (OPS-CW-003-01), not a stale deliverable:
  `git ls-files -v opencode.json` shows flag `S`, so the worktree holds the CI profile
  while CI checks out the committed local profile from HEAD.

Entry-point-to-test trace: `docs/launch/policy-pack.md` (entry point) →
`scripts/check-docs.py` `markdown_files()`/`check()` (`ROOT` scan, relative-link and
requirement-ID validation) → exit 0; every linked file was additionally checked to
exist on current `origin/main`, so the merge commit used by the fix PR gate resolves
the same links.

Deliberate omission: `scripts/check.sh` was not run. It chains the full Maven
`mvn verify` (JaCoCo/OWASP/SpotBugs), the frontend checks and `db/check.sh` (all
integration tests). The task instruction forbids running the full Maven verify or any
command that runs every integration test (over ten minutes, ends the worker run as a
timeout). No TypeScript or frontend file was touched, so no frontend check applies.
The fast checks above are the canonical gate for a docs-only change.

## Coverage

Not applicable — documentation-only change. No production line, branch, method or
class was added or changed, so there is no new branch to cover and no coverage command
to run. No threshold, exclusion or ignore comment was added or weakened; no test was
skipped. No fabricated coverage result is claimed.

## Clinical and architecture review

Not performed here and not implied. The pack routes clinical templates, code sets,
interpreting privileges and escalation to the clinical safety lead and site medical
director, result release and sensitive information to the privacy and clinical safety
leads, and interface contracts to the integration lead and partner owner. These are
open human G5 decisions (`S-G5-03`, `S-G5-04`, `S-G5-12`), not evidence supplied by
this document. No simulated-user or human-participant evidence was used.

## Findings

- The original fix-pass trigger could not be observed from local evidence: this worker
  has no GitHub token and does not call `gh`, and no failure log or issue hand-off is
  present in the repository. All fast gates pass on the branch and all linked files
  resolve on `origin/main`; the only local red (`check-pack.py`) is the documented
  skip-worktree artifact (see Test evidence). The one change made is the only
  repository-state delta found between the first attempt and current main that
  affects the pack's accuracy — the emergence of `docs/spec/clauses`. If the intended
  repair is something else, that reason must be supplied in a hand-off note before a
  further fix pass can target it.
- My branch is 28 commits behind `origin/main`; the first attempt was written before
  the product clause notes were merged. No rebase or merge was performed: that is an
  integrator action and would pull unrelated changes into this scoped diff.
- Evidence provenance: executed tool commands (checks, git reads, generator comparison)
  for every repository fact. No reviewer, simulated-user or human evidence was relied
  on for the correction. No clinical, legal or deployment approval is claimed.

## Lessons for workers

- A fix pass may have no hand-off text. Before changing a completed deliverable, diff
  its factual claims against current `origin/main` (not the stale branch base): a
  "directory does not exist" statement can become false while the worker is queued.
- For a docs-only fix, run `check-docs`, `check-secrets` and `check-names`, not
  `scripts/check.sh`: the latter chains the full Maven verify and every integration
  test and will time out the worker run.
- In a cloud worker, `git ls-files -v opencode.json` flag `S` means the worktree holds
  the CI profile, so a local `check-pack.py` red is a measurement artifact. Compare
  `git show HEAD:opencode.json` with `gen-opencode.py`'s local render before reporting
  a stale generated file — never edit `opencode.json` or `scripts/` to "fix" it.
- Never add a link to a file that exists only on `origin/main` while working on a
  branch behind main: `check-docs.py` runs on the branch worktree and would fail even
  though the merge commit resolves it.
