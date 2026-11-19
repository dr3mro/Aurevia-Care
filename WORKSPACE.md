# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | `main` at `782ac03`; origin `github.com/dr3mro/Aurevia-Care.git`; gh authenticated as `dr3mro` (ssh, repo scope) |
| Active story | G0 bootstrap — inventory complete; defining first IAM-01/IAM-02/ARC-02 slice |
| Approved delivery branch | `delivery/g0` (scoped commits, PR to `main`) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py |
| Local environment | Java 27, Maven 3.10, Node 26, Python 3.15, shellcheck, gh available; Docker MISSING locally — image builds and smoke run in GitHub Actions only |
| Project layout | apps/api, apps/staff-web, apps/patient-mobile, db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — small dependent jobs: Policy and tooling → Java and database verification / Developer reference → Verify and smoke image (amd64/arm64) → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Next action | Observe CI on exact SHA, merge ready agent PRs, then open delivery PR to main |

Keep this card short and current. PROGRESS.md owns chronological stage evidence. MEMORY.md owns durable decisions.
