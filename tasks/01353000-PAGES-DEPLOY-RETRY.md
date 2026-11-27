- [-] PAGES-DEPLOY-RETRY · Harden Pages deploy against artifact-metadata propagation lag
  - agent: orca
  - attempts: 0
  - requirements: OPS-06
  - scope: .github/workflows/pages.yml, scripts/tests/test_pages_workflow.py, tasks/01353000-PAGES-DEPLOY-RETRY.md
  - evidence:
    Test evidence (tool): `python3 -I -m unittest discover -s scripts/tests -p test_pages_workflow.py -v` -> 4 tests OK; `actionlint .github/workflows/pages.yml` exit 0; `REQUIRE_PY_COVERAGE=1 COVERAGE_BASE=20028724 bash scripts/python-coverage.sh` -> `Ran 2462 tests ... OK`, `python coverage: every changed script line is covered`. Tested revision: 200287246f0a54862f37e2ebae6948231abfeb9d.
    Coverage: first-fails/retry-succeeds, first-succeeds/retry-skipped, both-fail, ordering and coalesced URL asserted by `test_pages_deploy_retries_once_then_fails_closed` and `test_pages_environment_url_uses_the_coalesced_output`.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO.
  - details:
    Reliability hardening for a transient GitHub-side race: `actions/upload-pages-artifact@v3`
    registers `github-pages-<attempt>` successfully and the REST artifact listing sees it, but
    `actions/deploy-pages@v4` shortly after reports `Found 0 artifact(s)`. Observed on runs
    45933603710 and 46039411004; later runs succeeded with the same name/permissions.
    Exact design (all Required plan-review corrections adopted):
    - First deploy step `id: deploy_first`, `continue-on-error: true` (so its `outcome` is
      `failure` while `conclusion` stays success).
    - Bounded delay step `if: steps.deploy_first.outcome == 'failure'`, `run: sleep 20`;
      YAML `if` cannot itself sleep, and this gives metadata replicas time to converge.
    - Retry deploy step `id: deploy_retry`, `if: steps.deploy_first.outcome == 'failure'`,
      same artifact_name, and NO `continue-on-error` so its outcome is authoritative.
    - Final guard `id: deployment`, `if: always()`, shell reads FIRST_OUTCOME and
      RETRY_OUTCOME from env, exits 0 only when either outcome is `success`, otherwise
      emits an `::error::` and exits 1. Without `always()` a failed retry would skip the guard.
    - Guard coalesces the URL from env `FIRST_URL`/`RETRY_URL` (first non-empty) into
      `$GITHUB_OUTPUT` as `page_url`; job `environment.url` remains
      `${{ steps.deployment.outputs.page_url }}` so a retry-only success gets a URL.
    - Why-comment: the REST wait step proved one view of artifact metadata; run 46039411004
      proved deploy-pages can still see zero moments later. Assume the lag is transient;
      if the assumption is wrong the guard fails closed, never a false green.
    - Tests (static YAML text assertions are the coverage mechanism for workflow branches):
      first-fails/retry-succeeds, first-succeeds/retry-skipped, both-fail-guard-exits-1,
      first→delay→retry→guard ordering, coalesced URL wiring, unchanged name/permissions.
      Red: `python3 -I -m unittest discover -s scripts/tests -p test_pages_workflow.py -v`.
      Green: same command plus `actionlint .github/workflows/pages.yml` and
      `REQUIRE_PY_COVERAGE=1 COVERAGE_BASE=<HEAD> bash scripts/python-coverage.sh`.
      Live verification: dispatch/rerun and confirm published page returns 200 with URL.
