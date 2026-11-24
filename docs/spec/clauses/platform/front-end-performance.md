# WEB-08 clause 1 — front-end performance

## Clause

Front-end performance.

## Status

Partly proven

## Evidence

Test class: `WEB-08 clause 1 — front-end performance` (Vitest `describe` block) in
`apps/staff-web/tests/spec/platform/front-end-performance.test.ts`. The suite uses the
same Vitest + jsdom configuration and `tests/setup.ts` cleanup as the neighbouring
`tests/spec/localization/` tests. It is plain TypeScript, uses no JSX, contacts no
endpoint and sets up no database: staff web has no database in its test path. All
seven tests are active and pass; none is skipped. Fixtures are synthetic (an
incompressible `randomBytes` payload and a fabricated tracker HTML string) and the
remaining assertions read real build artifacts and repository source with typed
`node:fs` and `node:zlib`. No production source, test, contract, migration,
configuration or threshold was changed by this task.

Test methods:

- `enforcesTheGzipBudgetOnRealBuiltChunks` — verifies that the local 256000-byte
  constant matches the CI gate declared in `scripts/check-bundle-size.mjs`, then
  gzips every real `dist/assets/*.js` chunk with typed `gzipSync` and asserts each
  stays within budget. It provisions the build itself when `dist/` is absent, so it
  passes under plain `npm test` and `test:coverage` as well as `check.sh`. Mutation
  evidence: appending 320000 incompressible bytes to `index-*.js` made this test
  fail with `398245 bytes gzipped exceeds the budget (WEB-08)`; the artifact was
  rebuilt afterwards.
- `refusesAnOverBudgetPayload` — negative proof: a 300 KB incompressible synthetic
  buffer gzips above the budget and is refused with a chunk-labelled, WEB-08-named
  error, while a compressible 10 KB buffer is accepted. The local detector mirrors
  `assertWithinBudget` in `scripts/check-bundle-size.mjs`; the authoritative
  production refusal proof is `apps/staff-web/scripts/check-bundle-size.test.mjs`
  (`rejects a 300 KB incompressible buffer`, `fails the gate when any measured
  asset is over budget`, `wires the size check into check.sh after the build step`).
- `splitsFeatureRoutesIntoLazyChunks` — asserts that the app registry and all 17
  `src/features/*/routes.ts` modules either declare `lazy(() => import(...))` pages
  or, for the screen-less print feature, an empty registry. It also asserts more
  than ten real `dist/assets/*.js` chunks are emitted, so a source comment alone
  cannot satisfy the code-splitting claim.
- `loadsNoThirdPartyScriptsOrTrackers` — positive: the source `index.html` and the
  built `dist/index.html` each contain exactly one local `type="module"` script and
  no external `src` or tracker marker. Negative: a local detector refuses a fabricated
  HTML document containing `https://tracker.example.invalid/analytics.js`, while it
  accepts both real documents. There is no role, tenant, version or idempotency rule
  in this presentation-only clause; the refusal is the no-tracker control.

Three active GAP tripwires (complete assertions pinning the current absence, not
placeholders and not skipped tests) prove the unimplemented halves are recorded
rather than silently claimed: `GAP: field p75 LCP, INP and CLS are not measured`,
`GAP: fonts are not self-hosted with Arabic coverage` and
`GAP: hashed assets are not served as immutable`. Each names the file and API that
a later task must replace.

Production modules and build artifacts exercised through their normal entry points:

- `apps/staff-web/scripts/check-bundle-size.mjs` — the CI-enforced 256000-byte gzip
  budget (`MAX_CHUNK_GZIP_BYTES`, `gzipSize`, `assertWithinBudget`, `measureAssets`).
- `apps/staff-web/check.sh:17-19` — builds first, then runs the budget gate, so the
  gate measures real emitted chunks.
- `apps/staff-web/src/app/routes.tsx` — the app route registry with `lazy()` pages and
  the `import.meta.glob` feature-module discovery; `src/features/*/routes.ts` — the
  17 feature registries that supply lazily loaded screens.
- `apps/staff-web/index.html` — the single local module script and inline
  presentation styles that carry no third-party reference.

Entry-point-to-test trace: `apps/staff-web/index.html` loads `/src/main.tsx`;
`src/main.tsx` mounts `App`, whose `src/app/routes.tsx` registry composes the
`src/features/*/routes.ts` entries through `import.meta.glob`. Vite emits each lazily
imported screen as a separate `dist/assets/*.js` chunk; `check.sh` builds and then
runs `scripts/check-bundle-size.mjs` over those exact chunks. The new spec reads the
same emitted files, the same registries and the same source and built HTML, so its
assertions run against the artifacts the production build ships.

## Gap

Three halves of WEB-08 are not implemented and are therefore `Partly proven`, not
`Proven by tests`:

1. **Field p75 web vitals.** LCP <= 2.5 s, INP <= 200 ms and CLS <= 0.1 are documented
   in `docs/reference/performance-checklist.md` and in
   `docs/operations/production-readiness/evidence.md:60` but are not collected. There
   is no web-vitals dependency, collector module or reporting call in
   `apps/staff-web/src/main.tsx`. A later task adds a typed collector (for example
   `src/performance/webVitals.ts`), wires it into the entry point, records the three
   p75 values and asserts the thresholds in CI, then replaces the active GAP tripwire
   with a positive measurement test.
2. **Self-hosted Arabic-coverage fonts.** `apps/staff-web/index.html` declares no
   `@font-face` and the module ships no font files, so no provider is contacted but
   Arabic text relies on system fonts. A later task adds Arabic-covering WOFF2 assets
   under `public/fonts`, a self-hosted `@font-face` declaration and a glyph-coverage
   test, then replaces the active GAP tripwire.
3. **Immutable hashed assets.** `vite.config.ts` emits hashed filenames but no
   delivery configuration sets `Cache-Control: public, max-age=..., immutable` for
   them. `infra/app/nginx.conf` is the serving layer available in this repository
   (it serves `/opt/aurevia/web`) and declares no cache rule; `public/_headers` is only
   a candidate location in the web module. The approved production deployment or CDN
   may own the real policy. A later task adds the header rule at the owning layer,
   proves it with an HTTP/edge test and replaces the active GAP tripwire.

Each half needs its own task: they cross different boundaries (browser instrumentation,
font assets and localization, and deployment caching) and have different owners. This
task added proof only.

Advisor-note resolution: the senior-developer brief for this story requested complete
`it.skip("GAP: ...")` bodies. A skipped test is rejected by
`scripts/check-constraints.py` and forbidden by AGENTS.md and the story's own coverage
rule, so the repository's established active-tripwire pattern
(`tests/spec/localization/preserve-language-units-exports.test.ts:49-81`) was used
instead: the bodies are complete, assert the current absence and must be replaced when
the behavior lands. The PR stays green without a skip, exclusion or lowered threshold.
