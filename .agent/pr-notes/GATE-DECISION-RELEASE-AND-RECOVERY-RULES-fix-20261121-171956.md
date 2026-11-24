# GATE-DECISION-RELEASE-AND-RECOVERY-RULES — fix-pass notes and pull request description

## Summary

Re-verification fix pass for the decision pack at `docs/launch/release-and-recovery-rules.md`. The prompt carried an integrator finding that "local checks failed", with the check log ending at a ShellCheck **SC2015** info warning in `scripts/agent-run.sh` line 51. I did not redo the deliverable: I re-verified the already-committed pack against every constraint in task `GATE-DECISION-RELEASE-AND-RECOVERY-RULES`, re-ran its authoritative check, and traced the reported check failure to its source.

Result: the in-scope deliverable is complete, correct and already committed on this branch; no in-scope file was changed in this pass. **The reported `scripts/check.sh` failure is a pre-existing ShellCheck warning in a protected `scripts/` file, unrelated to this docs-only task and outside its declared scope** (`docs/launch/release-and-recovery-rules.md`). I did not edit `scripts/` — swarm workers are forbidden from touching it (TODO.md "Instructions for every agent" rule 8; AGENTS.md "Lessons go in notes, not in instruction files"), and a PR touching it would not auto-merge. Evidence source for this pass: executed tool commands (file reads, `grep`, `shellcheck`, `python3 -I scripts/check-docs.py`) and the earlier simulated QA reviewer; no human or simulated-user evidence.

## Affected requirements

XCH-06 and IAM-05 (primary). Supporting IDs cited in the pack and confirmed defined in `PROJECT_V1_SPEC.md`: OPS-01, OPS-02, PAT-02, PAT-03, ADM-01, PRD-06, plus the G5 row of section 9 and section 10. Acceptance mapping: task steps 1–3 → the eight required headings; "Not approved" status line and empty approval cells → the "decide nothing" constraint (task step 3); `python3 -I scripts/check-docs.py` → the mandatory check (task step 3 / TODO.md step 6).

## Risk class

Safety-critical by subject matter (patient release, sensitive information, identity recovery), but documentation-only: the pack records no approval and changes no runtime behavior. Per `docs/WORKFLOW.md`, real acceptance remains a person's decision (G5 / section 10), which no simulated review supplies.

## Review owner

Privacy lead and clinical safety lead (section 10 of `PROJECT_V1_SPEC.md`), who own the human decision in `S-G5-03`. No human clinical or privacy feedback was supplied or claimed. The original run recorded a bounded independent `orevia-qa-reviewer` review (simulated reviewer evidence) with "no findings".

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. The only file added in this pass is this notes file; no source, contract, migration, workflow or script was touched.

## Authorization boundaries and audit events

No authorization boundary or audit event added or changed. The pack only describes existing enforcement (XCH-06 release authorization separate from transmission/acknowledgment; IAM-05 single-use invitations, re-verification, lockout, contact-change ownership and unlinking; OPS-02 sensitive-action audit), verified in the original run against the linked source files.

## Migration and rollout considerations

None. Documentation only; no runtime or deployment effect.

## Test evidence

Entry point to check trace: the Markdown entry point `docs/launch/release-and-recovery-rules.md` is consumed by `scripts/check-docs.py`, which validates every relative link and every requirement ID mentioned outside the specification.

Commands run in this pass (exact, with final lines):

1. Required documentation check (task step 3 / TODO.md step 6), run directly:

```
$ python3 -I scripts/check-docs.py
check-docs.py RC=0
```

The script emits no stdout on success and exits 0: no broken links and no unknown requirement IDs in any non-skipped Markdown file. (`.agent/` is a skip prefix in `scripts/check-docs.py:44`, so notes files do not affect the result.) Evidence source: executed tool command (`tool`).

2. Required headings, status line, approval cells and checklist state (executed `grep`):

```
$ grep -nE '^Status:|^## ' docs/launch/release-and-recovery-rules.md
3:Status: Not approved
7:## Decision needed
17:## Why it matters
30:## Options
47:## What the repository already supports
57:## What is missing
72:## Questions for the owner
82:## Evidence checklist
101:## Approval record
```

All eight required headings present in order; status line is `Not approved`. Both approval rows have empty `Name`, `Date` and `Decision` cells; `grep -c -- '- \[x\]'` returned `0` (every checklist artifact is unchecked).

3. "Decide nothing" safety scan (executed `grep -nEi`): no dates, jurisdiction names (e.g. HIPAA/GDPR/New York/California), partner/person names or invented values in the pack. All named roles are the generic section-10 roles.

4. Section 10 row read directly from `PROJECT_V1_SPEC.md:184` and matched against the pack: decision text "Result release, sensitive information handling and patient identity recovery", accountable roles "Privacy lead and clinical safety lead", evidence "Approved rules, enrollment rehearsal and access review" — exact match.

5. Reported check failure traced (executed `shellcheck` / `git log` / `git show`):

```
$ shellcheck scripts/*.sh 2>&1
In scripts/agent-run.sh line 51:
[[ -f scripts/taskstore.py ]] && python3 -I scripts/taskstore.py view || true
                              ^-- SC2015 (info): Note that A && B || C is not if-then-else. C may run when A is true.
$ shellcheck scripts/*.sh ; echo RC=$?
RC=1
```

This is the sole shellcheck finding and the direct cause of the `scripts/check.sh` failure quoted in the integrator log. It is **pre-existing and out of scope**: `git log -1 -- scripts/agent-run.sh` shows it came from commit `ca4f5aaf` (2026-11-21, "Let workers request tasks that do not exist, checked and added by the integrator"), unrelated to this docs task; the same warning is present when running shellcheck over `git show origin/main:scripts/agent-run.sh`; and the only in-scope file changed by this branch is `docs/launch/release-and-recovery-rules.md`. Evidence source: executed tool commands (`tool`).

No frontend file was touched, so `apps/patient-mobile/check.sh` and `apps/staff-web/check.sh` (and their `tsc --noEmit` typechecks) do not apply. No Java or `scripts/*.py` file was touched, so `bash scripts/worker-check.sh`, the JaCoCo report, `python3 -I -m unittest discover -s scripts/tests` and `bash scripts/python-coverage.sh` do not apply.

## Coverage

Not applicable: this pass added no production line, branch, method, class, script line or TypeScript file. There is no new branch to pair with a test and no coverage command that measures a touched file. The applicable gate is `python3 -I scripts/check-docs.py`, result `exit 0` (above). No coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed; no existing test was weakened, deleted or skipped.

## Clinical and architecture review

Not claimed. The pack was prepared for the privacy lead and clinical safety lead and attests to no clinical or human acceptance. The original run recorded a bounded independent `orevia-qa-reviewer` review (simulated reviewer evidence) with "no findings" covering the "decides nothing" constraints, link/heading accuracy and requirement-ID validity; this pass independently re-verified those points with tool evidence and reached the same conclusion. No human clinical or privacy feedback was supplied or claimed.

## Findings

- **The in-scope task is complete.** The decision pack satisfies every acceptance constraint (all eight headings, "Not approved" status, empty approval cells, unchecked artifacts, no invented values, valid requirement IDs and links, exact section-10 roles/evidence). `python3 -I scripts/check-docs.py` exits 0. No in-scope file was edited in this pass because the deliverable was already correct; a cosmetic edit would only risk contradicting the clean result. Evidence source: executed tool commands; earlier simulated QA reviewer.
- **Root cause of "local checks failed" is out of scope and pre-existing.** `scripts/check.sh` fails solely because of a ShellCheck SC2015 info warning at `scripts/agent-run.sh:51`. That file is protected (workers must not edit `scripts/`), the warning exists on `origin/main`, and it was introduced by unrelated commit `ca4f5aaf`. It is not caused by this docs-only change and cannot be repaired inside this task's scope.
- **Actionable fix for a maintainer/integrator (not applied here):** in `scripts/agent-run.sh:51`, replace `[[ -f scripts/taskstore.py ]] && python3 -I scripts/taskstore.py view || true` with an explicit `if` block (e.g. `if [[ -f scripts/taskstore.py ]]; then python3 -I scripts/taskstore.py view || true; fi`), then re-run `shellcheck scripts/*.sh` and `bash scripts/check.sh`. This is a protected-file pipeline repair for the maintainer, not a task-request (AGENTS.md forbids task-requests whose scope is a script).
- `docs/spec/clauses` still does not exist in this checkout. Task step 1 asks that clause notes under that path be read; the pack records this under "What is missing" and names the open clause tasks (`SPEC-XCH-06-C01/C02/C03`, `SPEC-IAM-05-C01`) rather than inventing note contents. The required section 10, G5 row, production-readiness docs and decisions were read in the original run, so this is not a blocker.

## Lessons for workers

- When a fix pass reports only that "local checks failed", trace the failure to its exact command and file before touching the deliverable. If the failing file is outside the task scope and protected (e.g. `scripts/`), record it as an out-of-scope, pre-existing finding with an actionable fix; do not widen the PR to "fix" it.
- A clean re-verification with no in-scope edit is a valid outcome for an already-correct deliverable; state it plainly rather than manufacturing a cosmetic change.
- For a "decide nothing" decision pack, the link/ID checker cannot detect an implied endorsement, an invented date/name/jurisdiction, a filled approval cell or a link whose target does not contain the claimed behavior — scan for those four things explicitly with `grep`.
- In this environment `python3 -I scripts/check-docs.py` prints nothing on success and exits 0; record the shell's exit code rather than waiting for a printed "exit=0" line. `.agent/pr-notes/` is a skip prefix, so notes files never affect the check.
