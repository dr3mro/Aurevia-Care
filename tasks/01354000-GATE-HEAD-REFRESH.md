- [x] GATE-HEAD-REFRESH · Refresh a moved PR head in the Swarm gate before failing
  - agent: orca
  - attempts: 0
  - pr: #1817
  - requirements: OPS-06
  - scope: .github/workflows/swarm-gate.yml, scripts/swarm_gate.py, scripts/tests/test_swarm_gate.py, tasks/01354000-GATE-HEAD-REFRESH.md
  - evidence:
    Test evidence (tool): `python3 -I -m unittest discover -s scripts/tests -p test_swarm_gate.py` -> 94 tests OK; `actionlint` exit 0; full suite 2477 tests OK; `REQUIRE_PY_COVERAGE=1 COVERAGE_BASE=ff7c93d2 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`; check-constraints/docs/secrets/wiring exit 0. Tested revision: ff7c93d28c0262840d5ea8f092a65b3de8358eae.
    Coverage: event rewrite (valid/malformed/non-PR/missing event-name), refresh audit reason, second-move failure, and workflow text contracts each have named tests.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO, software-architect GO.
  - details:
    Owner decision: when the gate detects the PR head moved between the pull_request event
    and evaluation, it should fetch/refresh the live head and re-evaluate before declaring
    failure, instead of immediately failing `tested_revision`.
    Invariant: this is not a downgrade. The note's canonical `Tested revision:` must still
    be proven in the ancestry of the head actually evaluated; if it is not, the gate must
    fail closed. The verifier and task evidence still come only from the protected base.
    Required plan-review corrections (all adopted):
    - `Resolve live PR head` validates `headRefOid` itself with `^[0-9a-f]{40}$` and
      `exit 1` on empty/malformed output; the step sets `GH_TOKEN: ${{ github.token }}`.
      A workflow-text test asserts both. Otherwise an empty live SHA would set `moved=true`
      and could fail open.
    - "Stale note" means a note SHA that is NOT in the refreshed head's ancestry. Under the
      existing binding contract, a note naming the old head A still passes after a
      fast-forward A→B because B descends from A (`status ahead`, `behind_by 0`,
      `merge_base == note`); that is metadata consistency, not a downgrade, and a test pins
      it. A note naming a diverged/unrelated SHA fails `revision_binding` after refresh.
    - A workflow-text test asserts the verifier boundary: gate step still calls
      `python3 -I base/scripts/swarm_gate.py` with `--root base --head-root tested`, and no
      `run:` block executes code from the refreshed `tested` checkout.
    - The second-move test asserts the failure is `tested_revision`, with the note SHA and
      refreshed event head unchanged, so the refresh marker cannot suppress that check.
    - Risk order: `refresh-event` subcommand + tests first, then marker/evaluate wiring,
      then workflow steps and assertions. Proving command for workflow branches is the
      Python YAML-text unittest; local gates use `PATH=/opt/homebrew/bin:$PATH`.

    Fused design (three specialists; workflow-level refresh chosen over Python git subprocess):
    - swarm-gate.yml: after the tested checkout, `Resolve live PR head` (PR events only)
      compares event `.pull_request.head.sha` with `gh pr view --json headRefOid` and emits
      `moved=true|false`. No cancellation; per-run concurrency untouched.
    - If moved: `actions/checkout@v7` into `path: tested` using
      `ref: refs/pull/${{ github.event.pull_request.number }}/head`, `fetch-depth: 0`,
      `persist-credentials: false`. Trusted action, read-only token, no PR code executed.
    - If moved: `git -C tested rev-parse HEAD` yields the exact SHA actually checked out;
      new pure `swarm_gate.py refresh-event` subcommand validates 40-hex, copies the event,
      sets `.pull_request.head.sha` to that SHA, and adds top-level
      `swarm_gate_head_refresh = {"from": <event sha>, "to": <checked out sha>}`.
    - Gate step selects the refreshed event path only when moved; `evaluate` is unchanged
      except that `tested_revision`/refresh audit reason names the original head when the
      marker is present. A second head move still fails because evaluate re-queries the live
      headRefOid after the refresh.
    - Invariants: revision_binding still compares the note SHA to the refreshed event head
      with merge_base == note and behind_by == 0; classification, cross-repo, task/evidence
      and manifest ancestry are all recomputed from the refreshed tree/context; merge_group
      and workflow_dispatch skip refresh; missing/malformed live head fails closed; fetch or
      event-rewrite failure fails the job.
    - Tests first in scripts/tests/test_swarm_gate.py: valid event rewrite and sibling-field
      preservation, malformed SHA, missing pull_request, moved-but-valid ancestry (both
      tested_revision and revision_binding pass with refresh reason), moved-with-non-ancestor note
      (revision_binding fails), second move (tested_revision fails), missing live head,
      merge_group unchanged, and workflow text assertions for PR-only condition, PR-head ref,
      persist-credentials false and refreshed event-path selection.
    - Gates: python3 -I -m unittest discover -s scripts/tests -q; bash scripts/python-coverage.sh;
      actionlint; shellcheck on changed run blocks; check-constraints/check-docs/check-secrets;
      plan/code/coverage/security reviewers; exact-SHA CI; PR. No threshold or check weakened.
