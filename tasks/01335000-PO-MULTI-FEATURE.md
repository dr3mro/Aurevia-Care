- [-] PO-MULTI-FEATURE · Let the Product Owner select up to two features and give the Scrum Master more options
  - agent: orca
  - scope: .github/workflows/swarm.yml, scripts/tests/test_swarm.py, tasks/01335000-PO-MULTI-FEATURE.md
  - details:
    The PO step currently selects exactly one feature and one task list; the SM then has only that feature's candidates. Owner wants PO to select two features when two real gaps exist (prefer different feature prefixes), or one feature with up to two tasks when only one gap exists, so SM can choose up to two non-overlapping tasks from different features. Keep the existing `feature=`/`tasks=` two-line contract but allow comma-separated feature prefixes; update the PO prompt and parser regex, and the SM prompt to prefer different prefixes and fall back to one task when a second eligible one cannot be found. Do not change worker caps, gates or trust boundaries.
  - evidence:
    Skills loaded by matched clause: aurevia-delivery (delivery loop), aurevia-product-owner (feature selection), aurevia-scrum-master (allocation), aurevia-github-workflow (workflow prompt change).

    Red first (tool): two structural tests added to `scripts/tests/test_swarm.py::PlanTests`; targeted run -> `FAILED (failures=2)` because the PO prompt and parser still described one feature.

    Fix (tool): PO prompt now requests up to two features, prefers different prefixes, and allows one prefix with up to two tasks; the `feature=` parser regex accepts a comma-separated list; the work-trail instruction says "up to two feature prefixes"; the SM prompt prefers different prefixes and falls back to one task. `docs/SWARM.md:225` documents the new selection contract with current agent file IDs. No worker cap, gate or secret path changed.

    Green (tool): targeted `test_swarm.py` 377 tests OK; actionlint (queue ignore), check-docs, check-secrets, check-constraints --require-base, check-pack, gen-opencode --check and `git diff --check` exit 0. Full suite pending.

    Files changed: `.github/workflows/swarm.yml`, `docs/SWARM.md`, `scripts/tests/test_swarm.py`, `tasks/01335000-PO-MULTI-FEATURE.md`.

  - review verdicts (read-only subagents, agent file IDs):
    - devsecops: GO (0 Critical, 0 Required, 1 Optional: pre-existing raw model stdout echo — no secret or untrusted shell interpolation, regexes bounded, retry logic and tokens untouched).
    - github-workflow: GO (0 Critical, 0 Required, 2 Optional: reviewer could not re-run commands, and no live two-feature wave observed — output compatibility and focus parsing verified statically).
    - code-reviewer: GO (0 Critical, 0 Required, 2 Optional: stale step name and WORKER_COUNT wording — both fixed in the final tree: the step is "Select features with OpenCode" and the SM prompt treats $WORKER_COUNT as authoritative).

    Concurrency note: one verification run captured `swarm.yml` mid-merge from another session (`fix/swarm-empty-allocation`) and reported transient conflict markers and stale assertions. The merge committed cleanly (`cf9be21c`); the current tree has no markers, retains the two-feature prompt and parser, and `test_swarm.py` is 377 tests OK again. No fix was needed beyond re-running on the merged tree.

    Unverified: no live swarm wave has produced a two-feature selection yet; model behavior is guided by prompt and structural tests only. No simulated-user or authorized-human feedback was used.
