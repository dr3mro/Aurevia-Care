- [-] CI-ROOT-CAUSES · Repair reconcile truncation and Pages rerun artifact visibility
  - agent: orca
  - attempts: 1
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, scripts/tests/test_swarm_schedule_coverage.py, .github/workflows/pages.yml, scripts/tests/test_pages_workflow.py, tasks/01343000-CI-ROOT-CAUSE-REPAIRS.md
  - evidence:
    Test evidence (tool, Python 3.14 venv with PyYAML/coverage in /tmp):
    `python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2432 tests ... OK`.
    `COVERAGE_BASE=878458b9 REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> exit 0,
    `python coverage: every changed script line is covered`.
    `actionlint .github/workflows/pages.yml` -> only the known pre-existing `queue: max` warning.
    Integration trace: `scripts/tests/test_swarm_schedule_coverage.py::ReconcileTasksCoverageTests::test_paginated_fetch_reconciles_a_task_beyond_the_first_window`
    exercises the real `reconcile_tasks` -> `paginated_merged_pull_requests` -> `gh api --paginate` argv;
    `scripts/tests/test_pages_workflow.py::test_pages_waits_for_artifact_metadata_before_deploy`
    asserts the production Pages step order and token wiring.
    Tested revision: 878458b9ea9d427471309fa338d71024f4c32e62 (HEAD when checks ran).
    Review verdicts (read-only, after Test evidence and Coverage were filled):
    - code-reviewer: GO
    - coverage-auditor: GO
    - devsecops: GO
    Optional findings addressed: removed the now-dead downstream non-dict guard in
    scripts/swarm.py; replaced the Pages poll's `grep -q .` with a captured `gh api`
    result and explicit non-empty check. Re-ran the targeted suite (31 tests OK),
    coverage gate (exit 0) and actionlint (clean) after those edits.
    Coverage: reconcile pagination command shape, two-page JSON-lines stream with whitespace
    skip, non-dict record, trailing garbage, empty stream; Pages wait step ordering,
    actions: read, GH_TOKEN, bounded retries and fail-closed error.
    Local worktree branch fix/ci-root-causes; no commit or push yet.
  - details:
    Worktree `.workspaces/ci-root-causes`, branch `fix/ci-root-causes`, base HEAD 878458b9.
    Evidence: run 45945053333 raised "merged pull-request evidence is malformed or incomplete"
    from scripts/swarm.py:1482-1485 because scripts/swarm.py:1469 caps `gh pr list` at 500;
    `gh pr list --state merged --base main --limit 500 --json number | jq length` returns 500.
    Run 45933603710 (attempt 2) uploaded artifact github-pages-2 (id 15169383565) but
    deploy-pages@v4 found 0 artifacts; later attempt-1 runs with github-pages-N succeeded.

    Plan:
    1. Red test: replace test_full_500_record_window_fails_before_write with a test that a
       full first window is completed by a paginated authoritative fetch and reconciles a
       task from the far end of that list (deliberate behaviour change: 500 is no longer
       evidence of truncation). Add a test that the fetch command uses
       `gh api --paginate` with a merged_at jq filter and normalized shape across two
       pages, a test for a non-dict record, trailing garbage after a valid record, an
       empty stream, and a malformed stream that fails before any task write. The command
       shape test asserts `gh api --paginate` with no `--slurp`, the
       `repos/{repo}/pulls?state=closed&base=<branch>&per_page=100` URL, the jq projection
       of `.base.ref` to `baseRefName` and a literal `"MERGED"` state, and an argv list
       (no shell string). The two-page fixture uses `\n` separators to exercise the
       whitespace-skip branch. Test helper emits JSON-lines like the real command.
    2. Production: in scripts/swarm.py reconcile_tasks, fetch all merged PRs with
       gh api --paginate on repos/{repo}/pulls?state=closed&base=<branch>&per_page=100,
       with a jq filter selecting merged_at != null and projecting number, title,
       baseRefName and state (`--slurp` is rejected by gh when combined with `--jq`).
       Parse the stream with json.JSONDecoder.raw_decode and fail closed on a non-dict
       record. Keep all existing identity/base/duplicate validation and fail-closed
       errors. Add a docstring to the new helper explaining why pagination is
       authoritative and why malformed evidence must fail before writes.
    3. Red test: extend scripts/tests/test_pages_workflow.py to assert a bounded artifact
       visibility wait between upload and deploy, that it queries this run's artifacts by
       the same github.run_attempt-derived name, that it fails closed if the artifact never
       appears, and that the job has actions: read permission.
    4. Workflow: add the bounded poll step to .github/workflows/pages.yml, set
       `GH_TOKEN: ${{ github.token }}` on that step (following delivery.yml:221-228), keep
       the attempt-scoped names from aa070bbe, and grant actions: read. Do not revert to
       the canonical name: that reintroduces the duplicate-artifact failure of task 01328000.
       The test asserts the token env and the actions: read permission. Live attempt-2
       rerun evidence is recorded separately from local tests.
    5. Gates: PATH=/opt/homebrew/bin:$PATH python3 -I -m unittest discover -s scripts/tests -q;
       python-coverage gate for scripts/swarm.py; actionlint on pages.yml; shellcheck if a
       shell script changes; scripts/check-constraints.py and check-docs.py; plan, code,
       coverage and security reviewer verdicts; commit and push to fix/ci-root-causes;
       observe Delivery on the exact SHA; open a PR. No threshold, exclusion, skip or
       suppression changes.
