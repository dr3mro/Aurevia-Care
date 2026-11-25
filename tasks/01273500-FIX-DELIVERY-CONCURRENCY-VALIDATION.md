- [-] FIX-DELIVERY-CONCURRENCY-VALIDATION · Restore Delivery runs rejected by GitHub concurrency validation
  - agent: swarm-9
  - attempts: 20
  - requirements: OPS-06
  - scope: .github/workflows/delivery.yml, scripts/tests/test_workflow_concurrency.py, scripts/tests/test_swarm_delivery_state.py, .opencode/skills/aurevia-github-workflow/SKILL.md, .opencode/config/MEMORY.md, .opencode/config/BUGS.md, tasks/01273000-FIX-DELIVERY-CONCURRENCY-VALIDATION.md
  - depends: none
  - details:
    User-supplied run 45326930357 (PR #1611 head 342eae01, 2026-11-24T04:30:59Z)
    failed at creation with zero jobs and the GitHub banner "This run likely
    failed because of a workflow file issue". Tool evidence (gh api): every
    Delivery pull_request run since about 04:20Z failed the same way, while
    push and workflow_dispatch runs were pending behind dispatch run
    45322770885; the last green Delivery run was 45321478117 at 03:43Z.
    GitHub's current workflow syntax reference says `queue: max` combined with
    `cancel-in-progress: true` is a validation error. delivery.yml used
    `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`, which
    evaluates to true for every PR. actionlint 1.7.12 and yaml.safe_load both
    accept the file locally, so the policy job did not catch the server-side
    validation change. Owner chose the per-SHA PR group: keep queue: max, set
    cancel-in-progress: false everywhere, and scope PR runs by head SHA so a
    new head still verifies immediately while stale runs finish in their own
    group. Write the failing workflow tests first, then the smallest workflow
    change, update the stale skill and durable trackers, run the gates, and
    verify a fresh PR run creates jobs and reports Delivery gate.
  - evidence:
    Tool (diagnosis): `gh api repos/dr3mro/Aurevia-Care/actions/runs/45326930357`
    → `conclusion: failure`, created and updated 2026-11-24T04:30:59Z;
    `.../jobs?per_page=100` → `total_count: 0`. `gh api .../commits/342eae01.../check-runs`
    → no `Delivery gate` check. All Delivery PR runs after about 04:20Z had
    zero jobs; push/dispatch runs were pending behind dispatch 45322770885;
    last green Delivery run 45321478117 at 03:43Z. GitHub workflow syntax
    reference: "The combination of `queue: max` and `cancel-in-progress: true`
    is not allowed and will result in a workflow validation error."
    Tool (red): `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
    → `Ran 7 tests ... FAILED (failures=4)` on the old workflow.
    Tool (green): same command → `Ran 7 tests ... OK`.
    Tool gates at base 492d44b5: `actionlint -no-color -shellcheck= -pyflakes= -ignore 'unexpected key "queue" for "concurrency" section' .github/workflows/*.yml`
    exit 0; `python3 -I scripts/check-docs.py`, `check-constraints.py --base origin/main --require-base`,
    `check-secrets.py`, `check-names.py`, `gen-opencode.py --check` all exit 0.
    Tool baseline: full `python3 -I -m unittest discover -s scripts/tests -q`
    and `bash scripts/python-coverage.sh` exit 1 on six tests in untouched
    `test_swarm.py` / `test_swarm_integrator_coverage.py`
    (`UnboundLocalError: cannot access local variable 'text'` at
    `scripts/swarm.py:2163`, plus two assertion failures); no changed file in
    this story is involved. Re-check after refreshing to current origin/main.
    Reviewers (read-only): aurevia-github-workflow GO, aurevia-code-reviewer GO,
    aurevia-coverage-auditor GO (initial NO-GO on a vacuous pull_request trigger
    assertion fixed with `assertIsNone`), aurevia-devsecops GO. No Critical or
    Required finding remains. Evidence sources: executed tool commands, read-only
    reviewer reports and GitHub Actions API reads; no simulated-user or human
    participant evidence.
  - coverage:
    No production Python or application code changed; only workflow config,
    tests and tracker/documentation text. Every new assertion is executed by
    `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
    → 7 tests, OK: exact per-SHA/per-ref group expression; literal
    `cancel-in-progress: false` in delivery and every queueing workflow;
    `queue: max` on every workflow; bare pull_request trigger; bare push
    trigger; swarm per-run group. No suppression, skip, exclusion or threshold
    change was introduced.
  - delivery:
    Branch `fix/delivery-concurrency-validation`, commit 611c2d9f, PR
    https://github.com/dr3mro/Aurevia-Care/pull/1613 (open, mergeable).
    Tool: Delivery run 45330751996 on PR head 611c2d9f has 1 job
    `Policy and tooling` (queued) and a `Policy and tooling` check run:
    the workflow now passes GitHub validation and creates jobs, unlike the
    zero-job runs before the fix. The full `Delivery gate` verdict is still
    pending behind the existing runner backlog. The later task
    01274000-FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS repaired the baseline suite;
    after that fix `python3 -I -m unittest discover -s scripts/tests -q` →
    `Ran 1579 tests in 245.816s / OK` and `bash scripts/python-coverage.sh` →
    `python coverage: every changed script line is covered`. No gate was
    weakened in either task.
  - reviews: aurevia-github-workflow GO, aurevia-code-reviewer GO,
    aurevia-coverage-auditor GO, aurevia-devsecops GO (read-only reviewers,
    no Critical or Required finding remained at the final re-check).
  - ci-status: PR 1613 merged to main as 098354af. Tool: Delivery run
    45337601399 on head 190cf7dc had Policy, tooling, frontend and canonical
    frontend gates green; Java verification failed only on the upstream test
    compile error now fixed on main. Tool: Delivery run 45339545141 for the
    later main SHA 5a86e60f remained `pending` with zero jobs for over 40
    minutes behind 30+ queued main runs and one in_progress run on another
    branch (45342528638); the exact-SHA verdict is unavailable from this
    environment, not a failure of these changes. No check was bypassed.
