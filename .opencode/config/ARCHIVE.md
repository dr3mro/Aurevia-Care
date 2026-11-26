# Archived memory

Closed decisions and findings moved out of `MEMORY.md`. Active decisions stay in
`MEMORY.md`; this file preserves the history without keeping stale guidance in
the active checkpoint. Do not treat an archived entry as current policy.

## OPS-CW-003-01 — stale `opencode.json` gate failure (closed 2026-11-19, fixed 2026-11-26)

The following text was moved verbatim in substance from `MEMORY.md` lines 3, 42
and 45. It recorded a *diagnosis*, not a fix, which is why the same failure kept
recurring in cloud workers.

- From the checkpoint: "OPS-CW-003-01 closed as a false positive — the committed
  `opencode.json` is the correct local profile and the pack gate is green at HEAD;
  the earlier red came from `activate_ci_config` masking the worktree file during
  the agent step."
- From IMP-01/IMP-02: "The delivery skill now warns that cloud workers see the CI
  `opencode.json` under `skip-worktree`; policy results there are not HEAD truth
  (`git show HEAD:opencode.json`)." Evidence then cited BUGS OPS-CW-003-01/02.
- From the dispatch-3 finding: "the reported `opencode.json` staleness was a
  measurement artifact … `git show HEAD:opencode.json` equals the generator's local
  profile, so `gen-opencode.py --check` and `check-pack.py` are green at HEAD and no
  regeneration is needed (OPS-CW-003-01 closed as false positive); `agent-run.sh`
  `activate_ci_config` explains the earlier red. … Rule for later turns: inside a
  worker, treat worktree config checks as CI-profile results, not HEAD truth."

**Why it is closed and archived:** task 01323000 removed the measurement defect.
`gen-opencode.py.read_generated()` compares a skip-worktree generated file against
its committed blob with a sanitized read-only Git command, and `check-pack.py`
uses the same helper. Both profiles are still generated and validated; a renewed
`opencode.json is stale` failure is now real drift, not an environmental artifact.
The trusted `validate` job in `swarm-worker.yml` supplies the authoritative check
state for publication classification, so a worker-image artifact can no longer
turn a validated change into a draft, a handoff issue and a redundant re-dispatch.
Evidence: task 01323000, PR and exact-SHA CI verdict recorded there.

**Still open (not archived):** OPS-CW-003-02 — `ARG OPENCODE_VERSION=latest` and the
`:latest` agent image make the CLI version unreproducible. It remains in `MEMORY.md`.
