# READY-08 — Staff web route code-splitting and a bundle-size budget

- Branch: `swarm/READY-08-260` (base `2b184f6`)
- Requirement: **WEB-08** — "each route chunk at most 250 KB gzip" (implemented as a hard
  256000-byte gzipped ceiling per emitted `dist/assets/*.js`).
- Status: all gates pass locally. No database or API changes; no new dependency; no third-party
  or external script added; synthetic fixtures only.

## Acceptance mapping

| Story step | Change | Test evidence |
|---|---|---|
| 1. Lazy-load scheduling, chart and admin screens in one `Suspense` | `src/App.tsx`: three `React.lazy(() => import('./features/...'))` components, each with a thin adapter that supplies the screen's existing synthetic props; all three are children of a single `<Suspense>`. `AppShell` now accepts `children` and renders them inside `<main>`. | `src/app/screenLoading.test.tsx` asserts the fallback is a `<p role="status">Loading</p>` and that it disappears once all three lazy screens resolve. Existing `tests/App.test.tsx` still passes. |
| 1b. Fallback selection | READY-07 is **not** merged in this checkout (no `src/components/StateViews.tsx` exists), so the story's fallback rule selects a plain accessible "Loading" text with `role="status"`. A comment in `App.tsx` records that READY-07 should swap in `LoadingState`. | `src/app/screenLoading.test.tsx` (fallback tag and role). |
| 2. `scripts/check-bundle-size.mjs` | Reads every `dist/assets/*.js` via `readdirSync`, gzips each with `zlib.gzipSync`, prints an aligned table, and sets `process.exitCode = 1` when any file exceeds the module-level constant `MAX_CHUNK_GZIP_BYTES = 256000`. | `scripts/check-bundle-size.test.mjs` (limit constant, 300 KB rejection, 10 KB acceptance, gzip-vs-raw behaviour, `measureAssets` failure path). |
| 3. Call the script after the build in `check.sh` | `check.sh` had no build step, so `npm run build` was added and `node scripts/check-bundle-size.mjs` placed immediately after it (before `test:coverage`). | Wiring assertion in `scripts/check-bundle-size.test.mjs` checks both strings and their order. |
| 4. Failing test first | `scripts/check-bundle-size.test.mjs` was written before the script existed and shown red. | Red run below. |
| 5. Measured sizes | Recorded below from the final build/gate run. | Table below. |

## Commands and results (exact)

Run in `apps/staff-web` unless stated otherwise.

1. `npm ci --no-audit --no-fund` → `added 312 packages in 5s`.
2. **Red:** `npx vitest run scripts/check-bundle-size.test.mjs` →
   `FAIL scripts/check-bundle-size.test.mjs [ scripts/check-bundle-size.test.mjs ]` —
   `Error: Failed to resolve import "./check-bundle-size.mjs" ... Does the file exist?`
   (0 tests collected).
3. `npx vitest run scripts/check-bundle-size.test.mjs src/app/screenLoading.test.tsx tests/App.test.tsx tests/app/AppShell.test.tsx` →
   `Test Files 4 passed (4)`, `Tests 13 passed (13)`.
4. First full `bash check.sh` → `src/App.tsx(54,9): error TS2322: Type 'string | undefined' is not
   assignable to type 'string'` — `StaffMember.subject` is optional in the generated contract type
   while the synthetic fixture always supplies it. Fixed with a non-null assertion at the adapter
   boundary (the same assertion style already used in the feature fixtures).
5. Second full `bash check.sh` → `eslint`: `'Buffer' is not defined  no-undef` in
   `scripts/check-bundle-size.test.mjs`. Fixed by importing `Buffer` from `node:buffer` instead of
   relying on a global (the module's ESLint config gives no Node globals to `.mjs` files; the CLI
   script declares the few Node globals it uses with an inline `/* global ... */` directive).
6. **Final** `bash check.sh` → exit `0`:
   - `npm run gen:api:check` — "generated client matches the contract";
   - `npm run typecheck` — clean;
   - `npm run lint` — clean;
   - `npm run build` — `58 modules transformed`, built in ~118 ms;
   - `node scripts/check-bundle-size.mjs` — all four chunks `ok` (table below);
   - `npm run test:coverage` — `Test Files 32 passed (32)`, `Tests 206 passed (206)`;
     `Statements 100% (563/563)`, `Branches 100% (286/286)`,
     `Functions 100% (224/224)`, `Lines 100% (529/529)`, `No files with missing coverage.`,
     `45 files fully covered.`

   The stack-trace lines in the captured log come from pre-existing `I18nProvider` / `ThemeProvider`
   tests that intentionally render consumers outside their providers; they are expected console
   noise, not failures, and the run is green.

7. `npm run build && node scripts/check-bundle-size.mjs` → exit `0`, chunk table below.
8. **Gate-failure probe** (temporary 300 KB random `.js` written into the git-ignored `dist/assets`,
   then removed): the script printed `OVER` for that chunk and
   `One or more chunks exceed 256000 bytes gzipped (WEB-08).`, exit `1`. This proves the gate is not
   vacuous.

## Measured gzip sizes (final build, via Node `zlib.gzipSync`)

| Chunk | Gzip bytes | Budget | Result |
|---|---:|---:|---|
| `AdminScreen-COa0bEyg.js` | 4198 | 256000 | ok |
| `ChartScreen-CwF-G8HL.js` | 5712 | 256000 | ok |
| `SchedulingScreen-C7SytwZH.js` | 3235 | 256000 | ok |
| `index-CPdlsAy1.js` | 52977 | 256000 | ok |

Largest chunk is 52977 bytes (≈21% of the budget). The three feature screens are emitted as three
distinct dynamic-import chunks, which is the direct evidence that code-splitting is in effect.
No budget, dependency or third-party script was added or removed.

## Decisions and notes

- The screens need many props and there is no router yet (READY-19/21/22/23 are still open on
  `main`). Rather than change screen APIs, the lazy factories resolve the named screen export and
  return a default component that supplies the existing synthetic fixtures. This keeps each screen
  as its own chunk without duplicating fixture logic into the screen modules.
- Vite's own reported gzip figures differ slightly from the gate because the gate deliberately
  uses Node's `zlib.gzipSync` as the story specifies; the gate's numbers are the authoritative ones
  here.
- `AppShell` gained an optional `children` prop so route content lives inside the `main` landmark;
  existing `AppShell` tests that render without children are unchanged.
- Root Prettier ignores `apps/**` (`.prettierignore`), so the module's own `check.sh` is the
  formatter/tooling authority; no separate Prettier run applies to these files.
- New files are named after what they hold (`check-bundle-size`, `screenLoading`), not after the
  story ID.

## Evidence source

- Tool evidence: every command above was executed locally in this checkout; results are pasted
  verbatim. No reviewer, simulated user or human participant evidence was used for this story.
- No clinical or patient data: all rendered content comes from the existing synthetic fixtures.

## Defects found

None in production behaviour. Two type/lint issues in the new code were found and fixed during the
check runs (optional `StaffMember.subject`, ESLint `no-undef` for `Buffer`); both are recorded above
with their exact diagnostics. No open question or missing evidence remains.


---

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
