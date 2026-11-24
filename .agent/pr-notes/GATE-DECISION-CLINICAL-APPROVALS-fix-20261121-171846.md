# GATE-DECISION-CLINICAL-APPROVALS — fix pass (clinical approvals decision pack)

Base SHA: `472b614e` (the earlier attempt that added `docs/launch/clinical-approvals.md`).
Fix-pass notes stamp: `20261121-171846`. Working branch: `swarm/GATE-DECISION-CLINICAL-APPROVALS-447`.
No findings from an earlier run were supplied in this pass's prompt, so the deliverable was audited independently from the specification and the repository rather than re-guessed.

## Summary

Documentation-only fix pass. The existing decision pack already met every acceptance criterion; the one defect found was a misleading `NO TASK` label in `## What is missing` for the clause notes that relate to this decision. Real tasks already cover those clause notes, so the entry was corrected to name them (`SPEC-DECISIONS`, `SPEC-ADM-01-C01`, `SPEC-CLN-04-C02`) while keeping the honest statement that `docs/spec/clauses/` is absent from this checkout. No clinical content, option, name, date, jurisdiction or approval value was added or changed. No production code, migration, dependency or threshold was touched.

## Affected requirements

- **ADM-01** — tenant admins configure templates, catalogs, coverage rosters and critical-result escalation rules; clinical/market policies carry named approval, effective dates, audit and version history.
- **CLN-04** — clinical governance approves field dictionaries and requiredness; each saved version preserves historical field meanings.
- Referenced but not decided: **RAD-01** (imaging interpretation privileges), **XCH-05** (critical-result escalation and human acknowledgment).
- Gate/authority: section 9 G5 row (each site approves workflow, staffing and downtime drills; no unresolved critical-escalation defect) and section 10 ("Clinical templates, code sets, interpreting privileges, results escalation and coverage" — clinical safety lead and site medical director; approved dictionaries and timed workflow drills).

Requirement IDs come from `PROJECT_V1_SPEC.md` (ADM-01 line 88, CLN-04 line 54, XCH-05 line 67, RAD-01 line 77, section 9 G5 row line 174, section 10 line 183) and story `GATE-DECISION-CLINICAL-APPROVALS` in `TODO.md`/`tasks/00300000-GATE-DECISION-CLINICAL-APPROVALS.md`.

## Risk class

Safety-critical (evidence for patient-safety-relevant clinical configuration). The fix only corrects which tasks produce supporting clause notes; it sets no configuration value and enables no capability. Human clinical and site sign-off remains a G5 gate and is explicitly not satisfied by this document.

## Review owner

Accountable roles are the clinical safety lead and the site medical director (section 10), recorded in the document's intentionally empty approval record. The earlier pass obtained a simulated physician review (`orevia-physician-reviewer`) with no actionable findings; this pass changed no clinical content. A bounded independent QA review of this fix pass was requested (outcome recorded below). No human clinical or participant evidence was available or claimed.

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix. The document links to existing migrations (V5, V6, V15, V22, V23) as evidence only; none was edited.

## Authorization boundaries and audit events

Unchanged. The document states that approval is data recorded by a named person with effective dates and that the platform's controller, policy and audit layers enforce authorization. No audit event was added or changed.

## Migration and rollout considerations

None in this change. The document records that durable approval evidence does not yet exist for the in-memory template/dictionary and diagnosis/procedure stores (`BUILD-26`, NO TASK items) and that a site must hold signed approvals before enabling the related capability.

## Test evidence

Executed from the repository root on the working branch. Evidence source: executed tool commands (not a CI run, simulator or human participant).

- `python3 -I scripts/check-docs.py`
  Result: exit code `0`, no diagnostics (the authoritative gate for this documentation-only story).
- Structural audit (Python tool, reading `docs/launch/clinical-approvals.md`):
  - `Status:` line → exactly `Status: Not approved`.
  - `^## ` headings → exactly, in order: `## Decision needed`, `## Why it matters`, `## Options`, `## What the repository already supports`, `## What is missing`, `## Questions for the owner`, `## Evidence checklist`, `## Approval record`.
  - Unchecked checkboxes → `12`; checked checkboxes → `[]`.
  - Relative-link resolution over every Markdown link → `Broken links: []`.
  - Referenced task IDs → `BUILD-14`, `BUILD-17`, `BUILD-26`, `S-G5-02`, `S-G5-12`, `SPEC-DECISIONS`, `SPEC-ADM-01-C01`, `SPEC-CLN-04-C02` — all `FOUND` in `tasks/`.
  - Approval record tail → `| Clinical safety lead |  |  |  |` and `| Site medical director |  |  |  |` (all Name, Date and Decision cells empty).
- Repo-wide gate: `timeout 295 bash scripts/check.sh`
  Result: exit code `1`, failing in the shellcheck step before any Markdown or module check:
  `In scripts/agent-run.sh line 51: [[ -f scripts/taskstore.py ]] && python3 -I scripts/taskstore.py view || true` — `SC2015 (info): Note that A && B || C is not if-then-else. C may run when A is true.` This is a pre-existing defect in a protected script (`scripts/`), outside this story's scope (`docs/launch/clinical-approvals.md`) and present at the base SHA before any edit by this pass. It was not fixed here (protected file; see Findings and Why this is not finished).
- No entry-point-to-test trace applies: this is a human decision document, not a runtime path. It is validated by the repository Markdown gate and the structural audit above.
- Frontend checks (`bash apps/staff-web/check.sh`, `bash apps/patient-mobile/check.sh`) are not applicable: this story's scope is a Markdown file only, with no frontend file changed. Their typecheck lines are therefore not pasted (no `error TS…` to fix).

## Coverage

Not applicable. Documentation-only change: no production line, branch, method or class was added or changed, so no coverage command applies and no threshold, exclusion or skip was touched. `python3 -I scripts/check-docs.py` is the applicable gate and is green (exit 0). Java, frontend and script coverage commands were not run because no Java, frontend or `scripts/*.py` file changed.

## Clinical and architecture review

Earlier pass (unchanged content): simulated review by `orevia-physician-reviewer` returned no actionable findings; it verified all five approval categories, the eight exact headings, the four-column options table, resolving links, real task IDs, no invented jurisdiction/region/partner/name/date, `Not approved` status, 12 unchecked boxes and empty approval cells. This pass changed only one tracker-reference sentence (which task produces clause notes); no clinical claim, approval category, option or evidence checklist item was altered, so that review remains applicable. A bounded independent QA review of the new reference was requested as a background advisory check and returned **no actionable findings** (simulated structural/repository review; it could not execute `check-docs.py` itself and corroborated the primary agent's exit-0 result by file reads). No human clinical sign-off was available or claimed.

## Acceptance mapping

| Story requirement | Where the pack addresses it |
|---|---|
| Approved templates and field dictionaries, each with a named approver and effective dates | `## Decision needed` item 1; `## Options`; `## Evidence checklist` |
| Diagnosis and procedure code sets, each with a named approver and effective dates | `## Decision needed` item 2; `## Options`; `## Evidence checklist` |
| Imaging interpretation privileges | `## Decision needed` item 3; `## Options`; `## Evidence checklist` |
| Critical-result escalation rules | `## Decision needed` item 4; `## Options`; `## Evidence checklist` |
| Coverage rosters | `## Decision needed` item 5; `## Options`; `## Evidence checklist` |
| Cite requirement IDs | `## Why it matters` (ADM-01, CLN-04; RAD-01, XCH-05 referenced) |
| Options table with option / commitment / risks / evidence | `## Options` (four columns, five option rows) |
| Links to files that exist | `## What the repository already supports` (0 broken links, proven by `check-docs.py` exit 0 and link audit) |
| Task IDs or NO TASK | `## What is missing` (S-G5-02, S-G5-12, BUILD-26, BUILD-17, BUILD-14, NO TASK code-set store and imaging registry, and the clause-note tasks `SPEC-DECISIONS`, `SPEC-ADM-01-C01`, `SPEC-CLN-04-C02` corrected in this fix) |
| Questions for the owner | `## Questions for the owner` |
| Unchecked checkbox per artifact | `## Evidence checklist` (12 unchecked boxes) |
| Section 10 accountable roles, empty Name/Date/Decision | `## Approval record` |
| Choose no option, invent nothing, no filled approval record, "Not approved" | Status line and throughout; verified by structural audit |

## Findings

- **Fixed in this pass:** the earlier `## What is missing` entry labelled clause notes `NO TASK`, implying no task covers them. Task files show `SPEC-DECISIONS` (scope `docs/spec/clauses/policy/decisions-section-decisions-required-before-production.md`), `SPEC-ADM-01-C01` (ADM-01 clause note) and `SPEC-CLN-04-C02` (CLN-04 clause note) already produce such notes. Corrected the entry to name those tasks and retained the honest statement that the `docs/spec/clauses/` directory is absent from this checkout. No other content changed.
- **Out-of-scope pre-existing gate failure (defect):** `bash scripts/check.sh` exits 1 at shellcheck `SC2015` in `scripts/agent-run.sh:51`. It reproduces at base `472b614e` with an untouched tree, so it is not caused by this documentation change. `scripts/` is protected by AGENTS.md/TODO.md and outside this story's scope (`docs/launch/clinical-approvals.md`), so it was not edited here. Suggested fix for the owning maintainer: replace `[[ -f scripts/taskstore.py ]] && python3 -I scripts/taskstore.py view || true` with an explicit `if … then … fi` (or drop the trailing `|| true`), then re-run `bash scripts/check.sh`.
- **Independent QA review (simulated, no actionable findings):** confirmed the three corrected task IDs exist as open task files (`tasks/00781000-SPEC-DECISIONS.md`, `tasks/00499000-SPEC-ADM-01-C01.md`, `tasks/00402000-SPEC-CLN-04-C02.md`), that each one's `scope` writes clause notes under `docs/spec/clauses/`, that the directory is indeed absent, and that each ID's subject matches the doc's paraphrase with no misattribution. Re-confirmed the full acceptance matrix (8 headings, `Not approved`, 12/0 checkboxes, empty approval cells, section 10 roles, four-column/five-row options table, no invented identifiers). The reviewer could not execute `check-docs.py` and relied on the primary agent's exit-0 result plus manual validation — simulated review, not an independent tool execution or human evidence.
- **No code defect was found in the deliverable itself;** no BUGS.md entry is warranted for the document.

## Lessons for workers

- A decision pack decides nothing: present options and evidence, leave the approval record cells empty, and never supply a name, date, jurisdiction or partner even when surrounding docs use placeholders.
- Before writing `NO TASK` for a gap, search `tasks/` for an existing evidence-note task (for example `SPEC-DECISIONS`, `SPEC-ADM-01-C01`, `SPEC-CLN-04-C02`) — a wrong "no task" label is a reviewable factual defect even in a decision document.
- `scripts/check-docs.py` resolves every relative Markdown link against the file's own directory and validates requirement IDs, but not headings, checkbox state or status text; verify those structurally as well.
- Section 10 accountable roles are the approval-record rows; G5 evidence is a human signature, so simulated review is evidence of review activity only.
- When a fix pass's prompt carries no earlier findings, audit the base SHA independently before changing anything; a correct deliverable needs an honest verification note, not a fabricated defect.

## Why this is not finished

The story's own deliverable (`docs/launch/clinical-approvals.md`) is complete and its applicable gate is green (`python3 -I scripts/check-docs.py`, exit 0). The repository-wide `bash scripts/check.sh` gate is nevertheless red, for an unrelated pre-existing reason outside this story's scope: exit 1, shellcheck `SC2015` at `scripts/agent-run.sh:51` (`[[ -f scripts/taskstore.py ]] && python3 -I scripts/taskstore.py view || true`). Most likely cause: an `A && B || C` idiom intended as if-then-else, which shellcheck flags because `C` can run when `A` succeeds. What was tried: reproduced the failure at the untouched base SHA and confirmed the file is not in this story's scope and is protected, so no edit was made. Exact next step: the owning maintainer (or a scoped script-repair task) replaces the idiom with an explicit `if` block in `scripts/agent-run.sh` and re-runs `bash scripts/check.sh`; this documentation pull request should not be blocked by that separate fix.
