# WEB-08 clause 3 — each route chunk is at most 250 KB gzip, enforced in CI

## Clause

Each route chunk is at most 250 KB gzip, enforced in CI.

## Status

Partly proven

## Evidence

The clause test is [route-chunk-most-kb-gzip-enforced-ci.test.ts](../../../../apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts), a Vitest suite in jsdom with synthetic buffers only, no server or database setup, no helper added to `src/`, and no `it.skip`. It is enabled and green: 3 tests, 0 failures, 0 skipped. Staff web has no database in its test path.

Test methods (one budget-pin proof, one refusal-pin proof, one active CI gap tripwire):

- `pins the 256000-byte ceiling and gzip measurement in the budget script` — reads the checked-in gate source and asserts that `MAX_CHUNK_GZIP_BYTES` is `256_000`, that the measurement is `gzipSync(contents).byteLength`, and that the measured directory is Vite's `dist/assets`. It then gzips a small synthetic buffer with the same `node:zlib` API and asserts the result is within the ceiling. This pins the clause's numeric and measurement contract so a silent change to the budget is caught; it does **not** itself rebuild the app.
- `pins the over-budget refusal branch and WEB-08 message in the budget script` — gzips a 300 KB incompressible synthetic buffer, asserts the result exceeds 256000 bytes, and asserts that the gate's `if (size > MAX_CHUNK_GZIP_BYTES)` branch and its `byte budget (WEB-08)` failure message are present. This is the negative proof for the script's refusal path; the same behaviour is exercised against real built chunks by the existing unit suite below.
- `GAP: the required CI frontend job never runs the gzip budget gate` — an ACTIVE tripwire (not `it.skip`). It extracts the `frontend` job body from the workflow and the per-module npm command list from the shared frontend script, asserts that the required `Frontend standards` job delegates entirely to `bash scripts/frontend-check.sh`, and pins that those commands are only `npm ci`, `npm run typecheck`, `npm run lint` and `npm test`. Both exact arrays are load-bearing: adding a build or budget step, or switching the job to `bash apps/staff-web/check.sh` (which does enforce the budget), fails the case and forces the gap to be closed and this test replaced by positive proof.

Behavioural proof already in the repository (this task did not change or rerun it beyond the module `check.sh` gate):

- [check-bundle-size.test.mjs](../../../../apps/staff-web/scripts/check-bundle-size.test.mjs) imports the real `MAX_CHUNK_GZIP_BYTES`, `gzipSize`, `assertWithinBudget` and `measureAssets` exports and proves: the constant is 256000; a 300 KB incompressible buffer is rejected with a `WEB-08` message; a small buffer is accepted; gzip size, not raw length, is measured; `measureAssets` marks an oversized `.js` asset as failed and ignores non-JavaScript files; and `check.sh` places the budget gate after `npm run build`.
- [check.sh](../../../../apps/staff-web/check.sh) runs `npm run build` then `node scripts/check-bundle-size.mjs`, so the gate measures the real emitted chunks and fails the module's canonical check when any chunk exceeds the ceiling.

Tooling modules read as evidence for the CI half (behaviour is not executed by them in this test):

- [frontend-check.sh](../../../../scripts/frontend-check.sh) — lines 42-48 run only `npm ci`, `npm run typecheck`, `npm run lint` and `npm test` per module; no build and no budget script.
- [delivery.yml](../../../../.github/workflows/delivery.yml) — the `Frontend standards` job (lines 70-86) runs only `bash scripts/frontend-check.sh`.

Entry-point-to-test trace: `apps/staff-web/check.sh` runs `npm run build` (Vite emits hashed per-feature chunks into `dist/assets`) then `node scripts/check-bundle-size.mjs`; `measureAssets` reads that directory, `gzipSize` compresses each `.js` chunk and the script sets `process.exitCode = 1` when any chunk exceeds 256000 bytes. The new test reads that script source and repeats the measurement on synthetic buffers, so it fails if the ceiling, measurement or refusal message is removed. The module check.sh is the enforcing entry point locally; the CI `Frontend standards` job does not invoke it, which is the gap below.

## Gap

The budget is measured and enforced by `apps/staff-web/check.sh`, but the required CI `Frontend standards` job does not run that check. It runs `bash scripts/frontend-check.sh`, whose per-module subshell never calls `npm run build`, so no `dist/assets` output exists for `check-bundle-size.mjs` to measure, and it never invokes `node scripts/check-bundle-size.mjs`. A future contributor could ship an oversized chunk and the required CI job would still pass; only a developer running the module check.sh locally would measure the real built chunks. Raising the ceiling is a separate matter: CI does run `npm test`, which executes both the 256000-byte pin in `check-bundle-size.test.mjs:16` and the new clause test, so that change alone makes CI red rather than green.

One separate follow-up task owns closing this gap: wire `npm run build` followed by `node scripts/check-bundle-size.mjs` into the required CI frontend path (either the per-module subshell in `scripts/frontend-check.sh` after the staff-web checks, or an explicit step in the `frontend` job of `.github/workflows/delivery.yml`). That task must also replace the active `GAP:` tripwire in the clause test with a positive assertion that the required job runs the gate, and change this document's `## Status` from `Partly proven` to `Proven by tests`. Files that would change: `scripts/frontend-check.sh` or `.github/workflows/delivery.yml`, the clause test above and this document. Those are protected workflow/script paths, so the change needs the owner's authorisation and is deliberately not bundled here. The tripwire inspects only the `frontend` job's `- run:` steps and the delegated script's per-module npm commands; if the wiring instead adds a separate CI job or a `uses:` action, that task must still replace this case with positive proof explicitly. Until that task merges, the tripwire is intentionally green only because CI enforcement is absent. This task added proof only; it did not change production code, an existing test, the contract, a migration or a configuration file.
