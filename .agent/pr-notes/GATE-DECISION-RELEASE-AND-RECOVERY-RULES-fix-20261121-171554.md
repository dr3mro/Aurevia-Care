# GATE-DECISION-RELEASE-AND-RECOVERY-RULES — fix-pass notes and pull request description

## Summary

Fix/re-verification pass for the decision pack at `docs/launch/release-and-recovery-rules.md`. The prompt named this notes file but carried no explicit findings from an earlier run, so I did not redo the work from scratch: I re-verified the already-committed deliverable against every constraint in task `GATE-DECISION-RELEASE-AND-RECOVERY-RULES` and reviewed it for defects the Markdown checker cannot detect (an implied option endorsement, an invented jurisdiction/region/partner/name/date, a filled approval cell, or a link whose target does not contain what the pack claims).

Result: the deliverable already satisfies every stated constraint and no reproducible defect was found. **No in-scope file was changed in this pass** — the in-scope document was already correct, and editing it again would risk contradicting the clean result recorded by the original worker and its independent reviewer. The committed previous work on this branch (`4f35ff7d` document + notes, `80c03ac9` review record) is the pull request's substantive change; this file is the run artifact and becomes the pull request description. Evidence source for this pass: executed tool commands (file reads and `grep`) and the earlier simulated reviewer; no human or simulated-user evidence.

## Affected requirements

XCH-06 and IAM-05 (primary). Supporting IDs cited in the pack and confirmed defined in `PROJECT_V1_SPEC.md`: OPS-01, OPS-02, PAT-02, PAT-03, ADM-01, PRD-06, plus the G5 row of section 9 and section 10. Acceptance mapping: task steps 1–3 → the eight required headings in the pack; "Not approved" status line and empty approval cells → the "decide nothing" constraint (task step 3).

## Risk class

Safety-critical by subject matter (patient release, sensitive information, identity recovery), but documentation-only: the pack records no approval and changes no runtime behavior. Per `docs/WORKFLOW.md`, real acceptance remains a person's decision (G5 / section 10), which no simulated review supplies.

## Review owner

Privacy lead and clinical safety lead (section 10 of `PROJECT_V1_SPEC.md`), who own the human decision in `S-G5-03`. No human clinical or privacy feedback was supplied or claimed; an independent simulated QA review was recorded by the original run as "no findings".

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. The only file added in this pass is this notes file; no source, contract, migration or workflow was touched.

## Authorization boundaries and audit events

No authorization boundary or audit event added or changed. The pack only describes existing enforcement (XCH-06 release authorization separate from transmission and acknowledgment; IAM-05 single-use invitations, re-verification, lockout, contact-change ownership and unlinking; OPS-02 sensitive-action audit), all verified against the linked source files below.

## Migration and rollout considerations

None. Documentation only; no runtime or deployment effect.

## Test evidence

Entry point to test trace: the Markdown entry point `docs/launch/release-and-recovery-rules.md` is consumed by `scripts/check-docs.py`, which validates every relative link and every requirement ID mentioned outside the specification, and by the structural/heading checks over the file.

Commands run in this pass (exact, with final lines):

1. Required documentation check (task step 3 / TODO.md step 6), run directly:

```
$ python3 -I scripts/check-docs.py
FINAL_RC=0
```

The script emits no stdout on success and exits 0: no broken links and no unknown requirement IDs in any non-skipped Markdown file. (`.agent/` is a skip prefix in `scripts/check-docs.py:44`, so this notes file does not affect the result.) Evidence source: executed tool command (`tool`), not a reviewer or human participant.

2. Required headings and status line (executed tool):

```
$ grep -nE '^## |^Status:' docs/launch/release-and-recovery-rules.md
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

All eight required headings present in order; status line is `Not approved`. The approval table was re-read: the `Name`, `Date` and `Decision` cells are empty on both accountable-role rows, and every checklist item is `- [ ]` (unchecked).

3. Referenced task IDs resolved against `TODO.md` (executed tool): `S-G5-03`, `S-G5-12`, `SPEC-OPS-02-C03`, `SPEC-XCH-06-C01`, `SPEC-XCH-06-C02`, `SPEC-XCH-06-C03`, `SPEC-IAM-05-C01` — all FOUND. `SPEC-OPS-02-C03` was opened (`tasks/00610000-SPEC-OPS-02-C03.md`) and confirmed to be the "Enforce least privilege and regular access review" clause task, matching the pack's "What is missing" note.

4. Section 10 and G5 row read directly from `PROJECT_V1_SPEC.md:174,178-189`: the decision text, accountable roles (`Privacy lead and clinical safety lead`) and evidence phrase (`Approved rules, enrollment rehearsal and access review`) match the pack exactly.

5. "What the repository already supports" link targets re-read for content accuracy (executed `grep`/`sed`, not just existence): `ReleaseKind.java` defines `IMMEDIATE`, `TIMED`, `HOLD` and states a hold never opens by elapsed time; `ReleaseService.java` requires a non-blank hold reason and a future expiry and implements lift/withdraw/correct; `PatientAccessService.java` documents and implements site-verified single-use invitations, recovery with re-verification, lockout, renewed authentication, contact-change ownership preservation and unlinking; `retention-and-offboarding.md` defines binding legal holds and suspended/re-reviewed/re-verified disputed access; `rollout-and-production-access.md` defines approved, attributable, audited, expiring access. All claims in the pack are accurate against these files.

No frontend file was touched, so `apps/patient-mobile/check.sh` and `apps/staff-web/check.sh` (and their `tsc --noEmit` typechecks) do not apply to this pass. No Java or `scripts/*.py` file was touched, so `bash scripts/worker-check.sh`, the JaCoCo report, `python3 -I -m unittest discover -s scripts/tests` and `bash scripts/python-coverage.sh` do not apply.

## Coverage

Not applicable: this pass added no production line, branch, method, class, script line or TypeScript file. There is no new branch to pair with a test and no coverage command that measures a touched file. The applicable gate is `python3 -I scripts/check-docs.py`, result `exit 0` (above). No coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed; no existing test was weakened, deleted or skipped.

## Clinical and architecture review

Not claimed. The pack was prepared for the privacy lead and clinical safety lead and attests to no clinical or human acceptance. The original run recorded a bounded independent `orevia-qa-reviewer` review (simulated reviewer evidence) with "no findings" covering the "decides nothing" constraints, link/heading accuracy and requirement-ID validity; this pass independently re-verified those points with tool evidence and reached the same conclusion. No human clinical or privacy feedback was supplied or claimed.

## Findings

- No reproducible defect was found in the in-scope change by tool or earlier reviewer evidence. Human/participant evidence: none.
- This fix pass had no explicit earlier-run findings in its prompt and found nothing to repair; the deliverable already met every acceptance constraint. That is the reason no in-scope file was edited (see Summary). This is a record of a clean re-verification, not a claim of a fix that was made.
- `docs/spec/clauses` still does not exist in this checkout. Task step 1 asks that clause notes under that path be read; the pack records this under "What is missing" and names the open clause tasks (`SPEC-XCH-06-C01/C02/C03`, `SPEC-IAM-05-C01`) rather than inventing note contents. The required section 10, G5 row, production-readiness docs and decisions were all read and used, so this is not a blocker. Re-checked with `ls` in this pass: path still absent.
- Observation only (outside scope, not acted on): `TODO.md` marks `BUILD-50` (staff-web release-policy admin screens) as done with PR #314, but this checkout has no `apps/staff-web/src/features/policies` directory. The pack therefore links only to release-policy files that exist (API release package, migrations and tests). Left to the integrator or a person to reconcile; no TODO/tracker file was edited.
- `check-docs.py` exits 0 with no stdout on success in this environment; the literal final line is the shell's `FINAL_RC=0` shown above, not a printed `exit=0`. Recorded verbatim to avoid the earlier notes' expectation of a printed line causing a false "missing evidence" finding.

## Lessons for workers

- When a fix pass names a notes file but carries no earlier-run findings, re-verify the existing deliverable against each explicit acceptance constraint and against the file contents behind each link before changing anything; a clean result with no in-scope edit is a valid outcome and should be stated plainly rather than manufacturing a cosmetic change.
- For a "decide nothing" decision pack, the link/ID checker cannot detect an implied endorsement, an invented date/name/jurisdiction, a filled approval cell or a link whose target does not contain the claimed behavior — re-read the file specifically for those four things and spot-check a few linked sources with `grep`.
- In this environment `python3 -I scripts/check-docs.py` prints nothing on success and exits 0; record the shell's exit code rather than waiting for a printed "exit=0" line. `.agent/pr-notes/` is a skip prefix, so notes files never affect the check.
