# AUD-20261120-04 — Database gate: forced RLS on every tenant table

## Requirement mapping

The TODO entry has no explicit `requirements:` field. Relevant specification IDs for the
invariant being enforced:

| ID | How this change serves it |
|---|---|
| ARC-02 | Tenant isolation via forced RLS + `organization_id = public.app_org()` policy on every tenant table; this gate now proves the whole catalog, not a hard-coded list. |
| IAM-01 | Deny access without active membership/tenant scope — relies on RLS actually being forced on each tenant table. |
| OPS-02 | Tenant policy enforcement and audit; `db/check.sh` is the runtime apply-and-assert gate. |

Task scope: `db/check.sh` only. No migration, no Java, no other file changed.

## Acceptance mapping

| Done-when criterion | Result |
|---|---|
| `bash db/check.sh` passes | PASS — exit 0, `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` |
| New assertion 1a runs and fails (non-zero, prints names) when an `organization_id` table lacks FORCE RLS | PASS — negative test below |
| New assertion 1b runs and fails (non-zero, prints names) when an `organization_id` table has RLS but no policy | PASS — negative test below |
| Exceptions only with a documented global-table reason | PASS — zero exceptions required; no `EXCEPTIONS` array added |
| No migration edited | PASS — `git diff --stat` shows only `db/check.sh` |

## Changes made (only `db/check.sh`)

1. **Enabling fix — apply migrations in Flyway version order.** The loop used a bare glob
   (`for migration in db/migrations/V*.sql`), which bash sorts lexically: with two-digit
   versions `V10…` runs before `V1__…`, and `bash db/check.sh` fails at
   `db/migrations/V10__patient_identity_proofing.sql:17: ERROR: relation "public.organization" does not exist`
   (exit 3). Replaced with `printf … | sort -V`. This is the pre-existing tooling defect
   documented in `.agent/pr-notes/DLV-02.md`, `DLV-03.md` and `DLV-04.md`, deferred there
   to "a tooling story with a human reviewer" — this is that story. Without this fix the
   gate never reaches the new assertions.
2. **Assertion 1a (exact query from the story)** captures the catalog query output and
   fails with the offending table names when any `public` table with a live
   `organization_id` column lacks `relrowsecurity AND relforcerowsecurity`.
3. **Assertion 1b** LEFT JOINs `pg_policies` (`schemaname='public' AND tablename=c.relname`)
   and fails with the table names when an `organization_id` table has RLS enabled but zero
   policies. `GROUP BY c.relname` deduplicates tables with multiple policies.

Both assertions follow the file's existing helper style (`"${mig[@]}" <<'SQL'` capture +
`[[ -z … ]] || { printf 'fail: …' >&2; exit 1; }`).

## Exact commands run and results (tool evidence, synthetic data only)

| Command | Result |
|---|---|
| `bash -n db/check.sh` | exit 0 |
| `bash db/check.sh` (before change) | exit 3 — lexical glob applied V10 before V1 (pre-existing defect) |
| Raw catalog probe on a scratch DB with `sort -V` ordering | Q1 (no forced RLS) → 0 rows; Q2 (RLS, no policy) → 0 rows; 79 tables with `organization_id` |
| RED probe: scratch DB + `synthetic_no_rls` and `synthetic_rls_no_policy` | Q1 → `synthetic_no_rls`; Q2 → `synthetic_rls_no_policy` (queries detect both defects) |
| `bash db/check.sh` after change | exit 0, success line above |
| NEGATIVE 1a: temp migration `V999__temp_no_rls.sql` with `CREATE TABLE public.temp_no_rls (organization_id uuid);` | exit 1, `fail: tables with organization_id lacking FORCE RLS: temp_no_rls` (temp file removed) |
| NEGATIVE 1b: temp migration creating `temp_rls_no_policy` with ENABLE+FORCE RLS but no policy | exit 1, `fail: tables with organization_id and RLS but no policy: temp_rls_no_policy` (temp file removed) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q` | 35 tests, OK |
| `python3 -I -m unittest discover -s scripts/tests -q` with change | 268 tests, 10 failures, 10 errors |
| Same suite with `db/check.sh` reverted to HEAD (byte-restored after) | 268 tests, 10 failures, 10 errors — identical failing set → all pre-existing, unrelated to this change |
| `bash scripts/worker-check.sh` (committed `opencode.json` temporarily restored via trap) | fails at the Python tooling suite only (same pre-existing set); the shell/policy/db steps all pass |

No temp migrations remain; scratch databases are dropped by each script's `EXIT` trap.

## Exceptions

None. With migrations applied in correct version order, all 79 `public` tables carrying an
`organization_id` column have `relrowsecurity = true`, `relforcerowsecurity = true` and at
least one policy. No table needed an `EXCEPTIONS` entry, so none was added.

## Defects found (not fixed here — outside scope or pre-existing baseline)

1. **`db/check.sh` lexical migration ordering** — described above; fixed in this story
   because it is the same file and the same tooling story the notes deferred it to.
2. **Pre-existing red Python tooling suite makes `bash scripts/worker-check.sh` red on this
   worker regardless of this change.** With the committed `opencode.json` temporarily
   restored, the remaining failures are: `test_agent_run` (5 failures, 2 errors — fake-repo
   git/gh state), `test_swarm` (7 errors) and two `_FailedTest` import errors, all caused by
   `ModuleNotFoundError: No module named 'yaml'` (PyYAML not installed in this worker), plus
   `test_java_conventions.test_javadoc_links_are_fully_qualified` for `AccessRepository.java`
   targets `OffsetDateTime` and `Instant` (2 real pre-existing Javadoc defects on main).
   The identical failure set was reproduced with `db/check.sh` reverted to HEAD. None of
   these tests reads `db/check.sh`. Owners: pipeline/integrator (environment), and the owner
   of `AccessRepository.java` for the Javadoc links.
3. **Cloud-worker `opencode.json` artifact** — the worktree file is the CI profile copied
   under skip-worktree (`git ls-files -v` shows `S`), so `check-pack.py` sees it as stale
   (`opencode.json is out of date`). `git diff HEAD -- opencode.json` is empty: the committed
   profile is correct (documented false positive OPS-CW-003-01). Not edited; a temporary
   trap-restored swap to the HEAD file was used only to run the gates, then the CI profile
   was restored byte-for-byte (sha256 `b40d443d…`).
4. `shellcheck` is not installed in this worker (exit 127); `scripts/quick-check.sh` guards it
   with `command -v shellcheck`, so it is skipped there. `bash -n` passes.

## Decisions

- Fixing the version-order bug here is in scope: it is the deferred tooling fix for this
  exact file, and the gate cannot run its new assertions otherwise.
- No `EXCEPTIONS` array: the catalog has no non-compliant table; adding one would be
  unwarranted.
- Pending: integrator runs the full `scripts/check.sh` / integration tier. No release tag,
  push or `gh` call was made by this worker.

## Evidence sources

- Tool: all commands above executed by the primary agent against PostgreSQL 17.12 and this
  checkout; synthetic table names only, no patient data.
- Reviewer: `smartclinic-senior-database` bounded read-only review (simulated reviewer, static
  read only — it did not execute the queries and did not independently reproduce the tool
  evidence). Verdict: **no findings**; it checked query 1a, query 1b (policy multiplicity),
  the `sort -V` loop quoting/fail-closed behavior, global/reference tables and helper-style
  consistency. Non-blocking observation: both queries use `relkind='r'`, so a *future*
  partitioned (`relkind='p'`) or foreign tenant table with `organization_id` would be skipped;
  none exists today. Not actioned here because the story supplies the query with
  `relkind = 'r'` verbatim and says to adjust only the helper call around it — widening it
  belongs to a follow-up if partitioned tenant tables are introduced.
- Simulated user / human participant: none (database gate, no clinical or patient surface).
