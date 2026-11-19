# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | Working branch is `main` at `origin/main` (`1084ed4`); origin `github.com/dr3mro/Aurevia-Care.git`. `delivery/g0` was deleted per user directive; `ci/agent-swarm` merged in PR #11 and the CW-001 fix in PR #12. The current dispatch branch is `opencode/dispatch-3-3`: report-only verification, local commits, no push |
| Active story | S-G0-03 configuration foundation: delivered and green on `main`; remaining S-G0-03 catalog, broader-audit and OPS-06 slices queued |
| Approved delivery branch | `main` (all scoped work commits there from now on; PRs and required checks still apply) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py, bash apps/api/check.sh, bash db/check.sh |
| Local environment | Java 27 (baseline is Java 21 bytecode: `--release 21` locally, CI JDK 21), Maven 3.10, Node 26, Python 3.15, shellcheck, Doxygen, PostgreSQL 18.6 on 5432; Docker MISSING locally — image smoke runs in GitHub Actions only |
| Project layout | apps/api, apps/staff-web (empty), apps/patient-mobile (empty), db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — policy → java/database + docs → per-arch image smoke → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Checkpoint | TODO.md (history in docs/archive/PROGRESS.md); dispatch-3 run 3 verified the cloud worker itself: workflow `OpenCode team` run 44457533830, image `ghcr.io/dr3mro/aurevia-care-agent:latest`, `opencode v2.3.2`, active CI profile parses with 33 `smartclinic-*` agents. OPS-CW-003-01 closed as a false positive (committed `opencode.json` is the local profile; the red was worker `activate_ci_config` masking the worktree file). OPS-CW-003-02 records the floating CLI/image version. Earlier: S-G0-03 green on `1084ed4` (run 44455300465). `PLAN.md` is the canonical all-story index; `TODO.md` owns WIP one |
| Next action | Resume the S-G0-03 catalog slice (`TODO.md` S-G0-03c is done; next open G0 entries are S-G0-03d and S-G0-04) with failing tests on `main`; address the two TODO.md improvement ideas (IMP-01, IMP-02) (worker gate-profile note, CLI pinning) in the next authorized change to those files |

Keep this card short and current. TODO.md owns task state and evidence; docs/archive/PROGRESS.md holds the earlier chronological log. MEMORY.md owns durable decisions.
