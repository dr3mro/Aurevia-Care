# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | `delivery/g0` at local housekeeping commits (not pushed); `origin/main` at `2111691`; origin `github.com/dr3mro/Aurevia-Care.git`. Local `main` was behind `origin/main`; fast-forward local `main` (`git merge --ff-only origin/main`) before branching from it |
| Active story | S-G0-02 staff lifecycle and immutable membership history (IAM-02): local gates green, CI pending; S-G0-03 queued |
| Approved delivery branch | `delivery/g0` (scoped local commits; the workflow publishes the PR to `main`) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py, bash apps/api/check.sh, bash db/check.sh |
| Local environment | Java 27 (baseline is Java 21 bytecode: `--release 21` locally, CI JDK 21), Maven 3.10, Node 26, Python 3.15, shellcheck, Doxygen, PostgreSQL 18.6 on 5432; Docker MISSING locally — image smoke runs in GitHub Actions only |
| Project layout | apps/api, apps/staff-web (empty), apps/patient-mobile (empty), db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — policy → java/database + docs → per-arch image smoke → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Checkpoint | PROGRESS.md, S-G0-02 implementation and specialist/gate review; next commit, push and exact-SHA CI. PLAN.md is the canonical all-story index; `TODO.md` owns WIP one |
| Next action | Commit the scoped S-G0-02 change, push `delivery/g0`, observe required CI on that SHA, then start S-G0-03 |

Keep this card short and current. PROGRESS.md owns chronological stage evidence. MEMORY.md owns durable decisions.
