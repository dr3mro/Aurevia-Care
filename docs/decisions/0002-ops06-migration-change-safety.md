# 0002 — OPS-06 migration change-safety gate

- Status: accepted
- Date: 2026-11-19
- Requirement IDs: OPS-06

## Context

OPS-06 requires migration checks beyond the CI migration gate and support for reversible deployments with expand/contract schema changes. `db/check.sh` applies Flyway migrations and asserts tenant policy on a real PostgreSQL, but it cannot express version sequencing, change intent or the relationship between a destructive migration and the earlier migration that made it safe. There is no infrastructure-as-code or deployment workflow in the repository yet, so environment-account separation, signed builds, monitored progressive rollout and audited production access cannot be enforced offline without becoming vacuous self-attestation; those belong to the cloud/human-gated S-G5-07.

## Decision

Add one deterministic, synthetic checker, `scripts/check-migrations.py`, invoked with `--require-baseline` from `scripts/check.sh`, `scripts/quick-check.sh` and the Delivery `policy` job:

- migration filenames must be `V<n>__name.sql` with unique, contiguous versions from `V1`; an empty or missing migration directory fails under `--require-baseline`;
- statement-start destructive DDL requires `Change: contract`. Before matching, a single left-to-right pass blanks comments, strings, quoted identifiers and dollar-quoted bodies, then statements are split on semicolons and whitespace collapsed. This catches same-line, split-line and multi-statement forms while ignoring the TRUNCATE/DELETE tokens in V4/V5/V7 triggers, `REVOKE` grants and literals;
- `Change` is restricted to `expand` or `contract` when present;
- a contract requires `Forward-fix: V<n>__name.sql` (format-checked; the file may not exist yet, since it is a future commitment) and `Requires-expand: V<n>` pointing at an existing, earlier migration whose header declares `Change: expand`;
- `Forward-fix:` without `Change: contract` is rejected.

The header is a developer declaration: the checker proves its format and the `Requires-expand` reference, not that SQL matches the label. `Forward-fix` is deliberately not checked for existence — only its format is proven. A contract migration still requires human review, and `db/check.sh` remains the authoritative apply-and-assert gate.

## Alternatives considered

- Broad token search for destructive keywords — rejected: it flags the append-only triggers and `REVOKE ... TRUNCATE` statements in existing migrations and would fail the build on correct code.
- Successive regex passes that remove line comments before strings — rejected after review: a `--` inside a string literal eats the closing quote and can blank a later destructive statement. A single-pass scanner is required to keep the gate fail-closed.
- Line-anchored keyword matching — rejected after review: `DROP TABLE` split across two lines, or sharing a line with another statement, evaded detection. Statement splitting fixes this.
- Enforcing a declared environment manifest (accounts, rollout, signed builds) — rejected for now: with no deployment workflow or cloud account to bind to, such a manifest asserts values it cannot verify and would pass vacuously. Deferred until a real deployment workflow exists.
- Expanding `db/check.sh` — rejected: that gate needs a live PostgreSQL and is already the runtime policy authority; sequencing and intent checks are offline and belong in a separate tool.

## Consequences and evidence

Positive: a bad migration now fails PR and push policy jobs before any database is used; a contract must point to a real prior expand; deleting the migrations cannot silently disable the gate. Negative: SQL outside the enumerated statement-start forms can still evade detection, and header values are self-declared — accepted risks with human review as the compensating control. Forward-fix existence is not enforced; that belongs to the future migration and to review.

`coverage.py` is not installed, so line coverage was measured with the stdlib tool: `python3 -m trace --count --coverdir <dir> <runner>` reported 100% line coverage of both `scripts/check-migrations.py` and `scripts/tests/test_check_migrations.py`. Branch coverage has no mechanical measure here; `scripts/tests/test_check_migrations.py` (34 cases) exhaustively enumerates every control-flow branch plus the regex interaction regressions found in review. Evidence: `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q` → 34 tests OK; `python3 -I scripts/check-migrations.py --require-baseline` on the V1–V7 repository → exit 0; policy wiring in `scripts/check.sh`, `scripts/quick-check.sh`, `.github/workflows/delivery.yml`. Review findings QA-G0-03d-01/02/03/04 and DS-OPS06-01/02/03 were resolved by these changes.
