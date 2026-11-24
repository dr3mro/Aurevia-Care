# GATE-DECISION-RELEASE-AND-RECOVERY-RULES — notes and pull request description

## Summary

Added `docs/launch/release-and-recovery-rules.md`, a decision pack for the human decision in `S-G5-03` (result release rules, sensitive-information handling and patient identity recovery). It presents options neutrally, records what the repository already enforces with links to existing files, lists missing items by task ID or NO TASK, gives the owner's questions, an unchecked site evidence checklist and an empty approval record. Status line is `Not approved`. No option was chosen; no jurisdiction, region, partner, name or date was invented; the approval record was not filled. No file outside the declared scope (`docs/launch/release-and-recovery-rules.md`) was changed. TODO.md and other trackers were left to the orchestrator/integrator as instructed.

## Affected requirements

XCH-06, IAM-05. Supporting references cited in the pack: OPS-01, OPS-02, PAT-02, PAT-03, ADM-01, PRD-06, and the G5 row of section 9 and section 10 of `PROJECT_V1_SPEC.md`.

## Risk class

Safety-critical (definition of patient release, sensitive-information and identity-recovery policy). The pack is documentation only and records no approval; section 10 names the privacy lead and clinical safety lead as accountable. Per `docs/WORKFLOW.md`, any real acceptance remains a person's decision.

## Review owner

Privacy lead and clinical safety lead (section 10 of `PROJECT_V1_SPEC.md`). Independent simulated review requested from `orevia-qa-reviewer`; no human clinical or privacy feedback was supplied or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix. The new file is a standalone Markdown document with no runtime effect.

## Authorization boundaries and audit events

No authorization boundary or audit event was added or changed. The pack only describes existing enforcement: XCH-06 release authorization (separate from transmission and clinical acknowledgment), IAM-05 single-use invitations and re-verification, OPS-02 sensitive-action audit, and the disputed-link suspension/re-verification rule.

## Migration and rollout considerations

None. No code, contract, migration or workflow changed. All links in the new document were checked against files present in this checkout.

## Test evidence

Entry point to test trace: the new Markdown entry point `docs/launch/release-and-recovery-rules.md` is consumed by `scripts/check-docs.py`, which validates every relative link and every requirement ID mentioned outside the specification across all non-skipped Markdown files.

Command (baseline, before the new file):

```
$ python3 -I scripts/check-docs.py
check-docs exit=0
```

Command (required check, after the new file):

```
$ python3 -I scripts/check-docs.py
exit=0
```

Final line: `exit=0` — no broken links and no unknown requirement IDs. Evidence source: executed tool command in this checkout (`tool` evidence), not a reviewer or human participant. No typecheck was run because no TypeScript file was touched; no coverage runner applies to a documentation-only change.

Structural verification (executed tool commands):

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

The approval table was inspected after writing: the `Name`, `Date` and `Decision` cells are empty on both accountable-role rows. All checkbox items use `- [ ]` (unchecked).

## Coverage

Not applicable: no production code, branch, method, class or configuration was added or changed, so there is no new branch to pair with a test and no coverage command that measures a touched file. The applicable gate for this change is `python3 -I scripts/check-docs.py`, result `exit=0` (above). No coverage threshold, exclusion or skip was changed; no test was weakened, deleted or skipped. No Java, Node or mobile check was run because no Java, TypeScript or mobile file was touched.

## Clinical and architecture review

Not claimed. The pack was prepared for review by the privacy lead and clinical safety lead; it does not attest to clinical or human acceptance. A bounded simulated QA review was requested of `orevia-qa-reviewer` to check the "decides nothing" constraints and link/heading accuracy; it returned "no findings" (simulated reviewer evidence). No human clinical or privacy feedback was supplied or claimed.

## Findings

- `docs/spec/clauses` does not exist in this checkout. The task text asks the pack to read clause notes there, and `TODO.md` lists open clause tasks (`SPEC-XCH-06-C01`, `SPEC-XCH-06-C02`, `SPEC-XCH-06-C03`, `SPEC-IAM-05-C01`). The pack records this under "What is missing" rather than inventing note contents. This is not a blocker: the required section 10, G5 row, production-readiness docs and decisions were all read and used.
- `docs/launch` did not exist before this task; it was created by writing the single in-scope file. A sibling worker is preparing `docs/launch/policy-pack.md`; no link to that not-yet-present file was added, which keeps `check-docs.py` green in this checkout.
- Observation only (not asserted in the pack and not acted on, because it is outside scope): `TODO.md` marks `BUILD-50` (staff-web release-policy admin screens) as `[x]` with PR #314, but this checkout has no `apps/staff-web/src/features/policies` directory. The pack therefore links only to release-policy files that exist (API release package, migrations and tests). The integrator or a person may reconcile that entry.
- Independent bounded review by `orevia-qa-reviewer` (simulated reviewer evidence) returned "no findings". It verified, read-only: no option selected or recommended and no invented date, name, jurisdiction or partner; no filled approval cell and no implied acceptance; every link target exists; all eight required headings and all coverage areas are present; all cited requirement IDs are defined in `PROJECT_V1_SPEC.md`. The reviewer made no edits.
- No reproducible defect was found in the in-scope change by tool or reviewer evidence. Human/participant evidence: none.

## Lessons for workers

- For a decision pack that must "decide nothing", run `python3 -I scripts/check-docs.py` both before and after writing, then re-read the file specifically for effective dates, named individuals, option endorsements and filled approval cells — the link/ID checker cannot detect those constraint violations.
- Only link to files verified present with `test -e` in the current checkout; sibling workers may be creating adjacent `docs/launch/` files that do not exist when your check runs.
