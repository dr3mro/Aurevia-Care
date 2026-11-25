# Dependency boundaries (ORCA-053)

Directional call rules (enforced by check-ownership.py):

- `.opencode/scripts/` may call `scripts/` (project utilities) and `.github/scripts/` is forbidden.
- `.github/scripts/` may call `scripts/` (project utilities) and `.opencode/scripts/` is forbidden.
- `scripts/` (root) must not call `.opencode/scripts/` or `.github/scripts/`.
- No script may import another owner's internal helper directly; use documented CLI entry points only.
- Cycles between owners are forbidden.
- Duplicate basenames across owners are forbidden.
