# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | `delivery/g0` merged to `main` at `e1dc7cd`; origin `github.com/dr3mro/Aurevia-Care.git`; gh authenticated as `dr3mro` (ssh, repo scope) |
| Active story | S-G0-02 staff lifecycle and immutable membership history (IAM-02) — ready to define |
| Approved delivery branch | `delivery/g0` (scoped commits, PR to `main`) |
| Stable commands | scripts/check.sh, scripts/smoke-image.sh, scripts/verify.sh, scripts/gen-opencode.py |
| Local environment | Java 27, Maven 3.10, Node 26, Python 3.15, shellcheck, gh available; Docker MISSING locally — image builds and smoke run in GitHub Actions only |
| Project layout | apps/api, apps/staff-web, apps/patient-mobile, db, infra, contracts |
| CI and release | .github/workflows/delivery.yml — small dependent jobs: Policy and tooling → Java and database verification / Developer reference → Verify and smoke image (amd64/arm64) → release; reusable actions in .github/actions; Maven + OWASP NVD caches |
| Next action | Define S-G0-02 with acceptance scenarios, write failing tests through the staff lifecycle entry point |

Keep this card short and current. PROGRESS.md owns chronological stage evidence. MEMORY.md owns durable decisions.
