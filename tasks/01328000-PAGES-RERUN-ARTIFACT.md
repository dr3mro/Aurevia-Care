- [-] PAGES-RERUN-ARTIFACT · Give the Pages artifact a per-attempt name so reruns do not duplicate it
  - agent: orca
  - scope: .github/workflows/pages.yml, scripts/tests/test_pages_workflow.py
  - details:
    Publish developer reference run 45823955725 was attempt 2 and contained two artifacts named `github-pages` (IDs 15120160233 from attempt 1 and 15119813101 from attempt 2). `actions/upload-pages-artifact@v3` uploads with `overwrite: false`, and `actions/deploy-pages@v4` defaults to `artifact_name: github-pages`, so the rerun made `deploy-pages` fail with `Multiple artifacts named "github-pages" were unexpectedly found`. Set the upload `name` and the deploy `artifact_name` to `github-pages-${{ github.run_attempt }}` and add a regression test that the two expressions match. The Node 20 / punycode warnings are unrelated deprecations.
    Evidence source: tool reads of the Actions run, its artifacts and job log; action.yml for both pinned actions.
  - evidence:
    Skills loaded by matched clause: aurevia-delivery (delivery loop), aurevia-github-workflow (Pages workflow change).

    Red first (tool): run 45823955725 attempt 2 reported `Multiple artifacts named "github-pages" were unexpectedly found for this workflow run. Artifact count is 2`; API listed artifact IDs 15120160233 and 15119813101 both named `github-pages`; upload step logged `overwrite: false`.

    Green (tool): `/opt/homebrew/bin/python3.15 -I -m unittest discover -s scripts/tests -p test_pages_workflow.py -q` -> `Ran 1 test ... OK`. The test first failed on the `environment: name: github-pages` line; it was narrowed to the upload/deploy blocks and re-run green.

    Policy (tool, each exit 0): `actionlint -ignore 'unexpected key "queue" for "concurrency" section' .github/workflows/pages.yml`; `check-pack.py`, `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-constraints.py --require-base`, `check-wiring.py`, `gen-opencode.py --check`, `git diff --check`.

    Unverified: no new live Pages deployment has run with the per-attempt name yet. The deprecation warnings in the failed run are unrelated Node 20 / punycode notices. No simulated-user or human feedback was used.
  - review verdicts (read-only subagent, agent file ID):
    - github-workflow: GO (0 Critical, 0 Required, 2 Optional: textual rather than YAML-aware test, and old attempt artifacts remain until retention expires — both accepted, no gate weakened).
