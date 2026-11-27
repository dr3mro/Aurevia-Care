- [!] ORCA-REVIEWER-PROVENANCE · Bind independent review verdicts to reviewer-run evidence
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-040, AG-19, AG-22
  - scope: scripts/check-verdicts.py, scripts/tests/test_check_verdicts.py, scripts/quality-gate.sh, scripts/agent-run.sh, .opencode/skills/aurevia-code-reviewer/SKILL.md, .opencode/skills/aurevia-coverage-auditor/SKILL.md, .opencode/skills/aurevia-devsecops/SKILL.md, tasks/01301000-ORCA-REVIEWER-PROVENANCE.md
  - depends: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - details: Incomplete: plan reviewer NO-GO; needs a trusted runner with Git/GitHub execution that this session does not have. Next action: supply a trusted reviewer-artifact producer outside worker write authority, then reopen to `[ ]` and implement the evidence binding. Defect ORCA-REVIEWER-PROVENANCE. `check-verdicts.py` currently accepts GO lines typed into the worker's own notes file, so an implementation agent can manufacture all three independent approvals. Add a reviewer-run evidence artifact requirement, bind each artifact to reviewer identity, session and reviewed revision, and fail closed when it is absent or stale. Keep the existing notes parsing intact; do not weaken any gate.

## Loaded skills
- aurevia-delivery — mandatory delivery loop and definition of done.
- aurevia-senior-python — production Python change under `scripts/`.
- aurevia-test-engineer — red tests first, every branch mapped to a test.
- aurevia-plan-reviewer — review this plan before coding.
- aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops — independent verdicts after code and green tests.

## Plan (smallest first)
1. Add to `scripts/check-verdicts.py`: `EVIDENCE_DIR` under `.agents/reviews/`, a loader for one JSON artifact per required reviewer, and `evidence_problems()` that fails closed on missing artifact, wrong reviewer identity, verdict mismatch with the notes line, empty session, malformed JSON and revision that does not match `--base`. Call it from `main()` only after notes parsing; keep the docs-only early return.
2. Write red tests in `scripts/tests/test_check_verdicts.py` for each branch above plus a happy path and the CLI status. Do not rewrite existing tests; add new ones.
3. Update the three reviewer skills so a reviewer writes its artifact (reviewer id, verdict, session id, reviewed revision, timestamp) after its verdict. The artifact is evidence, not a second source of truth.
4. Update `scripts/quality-gate.sh` comments to name the evidence requirement; no threshold or switch change.
5. Verify: targeted `python3 -I -m unittest discover -s scripts/tests -p 'test_check_verdicts.py' -q`, `bash scripts/python-coverage.sh`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-constraints.py`, `python3 -I scripts/gen-opencode.py --check`; record final lines and branch-to-test map.

## Known residual risk
A worker with blanket shell permission can still write an artifact file; this change removes the bare-notes shortcut and binds evidence to reviewer identity, session and revision, but full cryptographic separation needs a trusted runner that produces the artifact outside the worker. Record this openly in the task file and BUGS.md rather than claiming perfect independence.

## Evidence log
- (tool) `rg -n 'check-verdicts|SWARM_REVIEW_GATE' scripts .github .opencode` → only `scripts/quality-gate.sh:14-15` calls the checker; `SWARM_REVIEW_GATE=off` remains documented in `docs/WORKFLOW.md` and is not removed by this story.
- (tool) `scripts/tests/test_check_verdicts.py` has 14 tests; `MainTests` uses disposable Git repositories. No production change was made.
- (tool) `python3 -I -m unittest discover -s scripts/tests -p 'test_check_verdicts.py' -q` → `Ran 14 tests`, `OK` (unchanged baseline).
- (reviewer) `aurevia-plan-reviewer`: VERDICT NO-GO. Required gaps: (1) reviewers hold only read/glob/grep/skill permissions (`scripts/gen-opencode.py:91-98`), so the planned reviewer-written artifact has no writer; (2) `orca` holds blanket shell allow (`gen-opencode.py:81`), so every artifact field would be self-asserted and no independence is achieved; (3) `--base` is the pre-change base SHA (`scripts/agent-run.sh:503`), not the reviewed HEAD; (4) an always-on evidence requirement would break existing GO-notes tests; (5) artifact lifecycle (tracked vs gitignored) was undefined. The senior-python proposal was to use `--require-evidence`, a runner-issued session and a reviewed-HEAD revision, but the authorship gap remains.

## Why this is not finished
No code was changed. The plan cannot satisfy ORCA-040/AG-22 without a trusted producer that is outside the worker's write authority. `scripts/agent-run.sh:1191-1216` has a `review` command that captures reviewer output into `$out/review-<role>.md`, but no workflow invokes it (`rg` found no workflow caller), so promoting it to an evidence producer requires workflow and Git/GitHub changes that this session's runtime denies. The defect stays open in BUGS.md as ORCA-REVIEWER-PROVENANCE; hardening the notes checker alone would be cosmetic and would normalise fabricated evidence.

## Next bounded action
A trusted runner must produce the evidence artifact from a review it invokes itself, bind it to the reviewed HEAD revision and a runner-issued session, and expose the artifact path to `scripts/check-verdicts.py`. Requires Git/GitHub execution and workflow changes; tracked as a blocked follow-up task.
