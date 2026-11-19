# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | Branch `main`, local HEAD `4869b62` (merge of tracker sync with swarm claims at `2dc6a24`); origin `github.com/dr3mro/Aurevia-Care.git`. `delivery/g0` retired; all scoped work commits to `main`. External auto-merge and swarm workers (CW-006/007/008) are active on this repo — reconcile `git fetch origin main` and keep `TODO.md` synced at least every 10 minutes before editing trackers or pushing. |
| Active story | IMP-01/IMP-02 completing (worker CLI pinned to 2.3.2, digest recorded; skill notes CI-profile trap). S-G0-03d accepted: code `6b9d730`, tracker `4869b62`, Delivery run 44477163896 green. S-G0-04 blocked pending decomposition (search/files/workers/exports surfaces do not exist); next feasible stories S-G0-06/07/08. |
| Approved delivery branch | `main` (all scoped work commits there from now on; PRs and required checks still apply) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py, bash apps/api/check.sh, bash db/check.sh |
| Local environment | Java 27 (baseline is Java 21 bytecode: `--release 21` locally, CI JDK 21), Maven 3.10, Node 26, Python 3.15, shellcheck, Doxygen, PostgreSQL 18.6 on 5432; Docker MISSING locally — image smoke runs in GitHub Actions only |
| Project layout | apps/api, apps/staff-web (empty), apps/patient-mobile (empty), db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — policy → java/database + docs → per-arch image smoke → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Checkpoint | TODO.md (history in docs/archive/PROGRESS.md); dispatch-3 run 3 verified the cloud worker itself: workflow `OpenCode team` run 44457533830, image `ghcr.io/dr3mro/aurevia-care-agent:latest`, `opencode v2.3.2`, active CI profile parses with 33 `smartclinic-*` agents. OPS-CW-003-01 closed as a false positive (committed `opencode.json` is the local profile; the red was worker `activate_ci_config` masking the worktree file). OPS-CW-003-02 records the floating CLI/image version. Earlier: S-G0-03 green on `1084ed4` (run 44455300465). `PLAN.md` is the canonical all-story index; `TODO.md` owns WIP one |
| Next action | Resume the S-G0-03 catalog slice (`TODO.md` S-G0-03c is done; next open G0 entries are S-G0-03d and S-G0-04) with failing tests on `main`; address the two TODO.md improvement ideas (IMP-01, IMP-02) (worker gate-profile note, CLI pinning) in the next authorized change to those files |

Keep this card short and current. TODO.md owns task state and evidence; docs/archive/PROGRESS.md holds the earlier chronological log. MEMORY.md owns durable decisions.
