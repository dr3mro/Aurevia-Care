# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | Working branch is `main` at `origin/main` (`1084ed4`); origin `github.com/dr3mro/Aurevia-Care.git`. `delivery/g0` was deleted per user directive; `ci/agent-swarm` merged in PR #11 and the CW-001 fix in PR #12 |
| Active story | S-G0-03 configuration foundation: delivered and green on `main`; remaining S-G0-03 catalog, broader-audit and OPS-06 slices queued |
| Approved delivery branch | `main` (all scoped work commits there from now on; PRs and required checks still apply) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py, bash apps/api/check.sh, bash db/check.sh |
| Local environment | Java 27 (baseline is Java 21 bytecode: `--release 21` locally, CI JDK 21), Maven 3.10, Node 26, Python 3.15, shellcheck, Doxygen, PostgreSQL 18.6 on 5432; Docker MISSING locally — image smoke runs in GitHub Actions only |
| Project layout | apps/api, apps/staff-web (empty), apps/patient-mobile (empty), db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — policy → java/database + docs → per-arch image smoke → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Checkpoint | PROGRESS.md, S-G0-03 verified in GitHub Actions on `1084ed4` (run 44455300465 green); next S-G0-03 catalog slice. PLAN.md is the canonical all-story index; `TODO.md` owns WIP one |
| Next action | Define the next S-G0-03 slice (catalogs or broader audit) with failing tests and commit scoped work on `main` |

Keep this card short and current. PROGRESS.md owns chronological stage evidence. MEMORY.md owns durable decisions.
