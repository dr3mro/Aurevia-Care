# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | Working branch is now `copilot/cw-001-fix-github-actions-job`; origin `github.com/dr3mro/Aurevia-Care.git`. The delivery branch user directive remains `main`; this branch is the scoped CW-001 fix |
| Active story | S-G0-02 staff lifecycle and immutable membership history (IAM-02): delivered and merged; S-G0-03 queued |
| Approved delivery branch | `main` (all scoped work commits there from now on; PRs and required checks still apply) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py, bash apps/api/check.sh, bash db/check.sh |
| Local environment | Java 17.0.21 Temurin (`java -version`), Maven 3.10 (unfortunately below the project Java 21 baseline), Node 26, Python 3.15, shellcheck, Doxygen, PostgreSQL 18.6 on 5432; Docker MISSING locally — image smoke runs in GitHub Actions only |
| Project layout | apps/api, apps/staff-web (empty), apps/patient-mobile (empty), db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — policy → java/database + docs → per-arch image smoke → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Checkpoint | PROGRESS.md, S-G0-02 CI and merge; next S-G0-03 story definition. PLAN.md is the canonical all-story index; `TODO.md` owns WIP one. CI evidence rows are uncommitted and belong in the next scoped commit |
| Next action | Define S-G0-03 failing tests through the tenant configuration entry point and commit scoped work on `main` |

Keep this card short and current. PROGRESS.md owns chronological stage evidence. MEMORY.md owns durable decisions.
