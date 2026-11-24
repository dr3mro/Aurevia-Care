# BUILD-18 · Arabic and RTL: staff web layout and mixed-script text

PR #288, tested branch tip 901a7db096be7a42c4361a564978192b443203ff.

Task: BUILD-18

Swarm worker 5. Local checks: passed. Agent result: ok.

# BUILD-18 — Arabic and RTL: staff web layout and mixed-script text

## Summary

Delivered the UX-01 subset inside the two scoped directories only
(`apps/staff-web/src/i18n`, `apps/staff-web/src/theme`). Locale direction was
already mirrored onto the document; it is now covered by a scoped render test.
Numeric and date values are pinned to Latin digits in both locales so a value
never sits beside an identifier in a different numeral system, a missing
translation key resolves to English, person names are exposed for isolated
mixed-script rendering with `dir="auto"`, and the theme provides a
logical-property-only offset style derived from the active tokens. No new
dependency and no file outside the declared scope was changed.

## Affected requirements

- UX-01 (English and Arabic, RTL direction, local name display, mixed-script
  clinical text). Design references: `docs/design/rtl-and-localization.md`
  sections 1, 1.1, 3, 6, 8 and 9; `docs/design/README.md` D4 and deviation 11.

## Risk class

Routine (contained i18n/theme change). No clinical, tenant or release contract
changed. Documentation-only design rules remain owned by the design docs.

## Review owner

Senior React and Vite (bounded plan and file-by-file API), Senior TypeScript
(strict typing of the new exports), UX reviewer (RTL and mixed-script gap
review). Both specialists were advisory and ran read-only; implementation and
executed checks were done by this worker.

## Data changes

None. No migration, backfill, schema, lock or volume impact. No data persisted.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or authentication code touched; no audit
events added or changed. All test data is synthetic (`Layla Haddad`/`ليلى حداد`,
`Synthetic Name`, `ليلى Haddad · MAIN`) and contains no real identifier.

## Migration and rollout considerations

Not applicable — static client-side formatting and styling helpers with no
server call, no storage and no rollout state.

## Test evidence

Commands run (from `apps/staff-web` unless stated), with final lines:

1. Red state before implementation —
   `npx vitest run src/i18n/format.localization.test.ts src/i18n/I18nProvider.direction.test.tsx src/theme/logicalLayout.test.ts --reporter=dot`
   → `Test Files 2 failed | 1 passed (3)`, `Tests 7 failed | 5 passed (12)`
   (the direction test already passed because `dir`/`lang` mirroring existed).
2. `npm run typecheck` →
   `> orevia-staff-web@0.1.0 typecheck` /
   `> tsc --noEmit -p tsconfig.json` / `EXIT=0` (no `error TS…`).
3. `npm run lint` →
   `> orevia-staff-web@0.1.0 lint` /
   `> eslint . --max-warnings 0` / `EXIT=0`.
4. `bash apps/staff-web/check.sh` → `Test Files 52 passed (52)`,
   `Tests 554 passed (554)`, then:
   `No files with missing coverage.`, `75 files fully covered.`,
   `Statements : 100% ( 1389/1389 )`, `Branches : 100% ( 700/700 )`,
   `Functions : 100% ( 459/459 )`, `Lines : 100% ( 1325/1325 )`.
   This is the mandatory gate: it includes `gen:api:check`, typecheck, lint,
   build, the WEB-08 bundle budget and 100% v8 coverage.
5. `bash scripts/check.sh` → EXIT=1, last line
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is a
   known cloud-worker measurement artifact, not a change defect: `git ls-files -v
   opencode.json` reports flag `S` (skip-worktree), the harness copied the CI
   profile into the worktree, and `git show HEAD:opencode.json` is byte-identical
   to `scripts/gen-opencode.py`'s `render(ROOT, False)` while the worktree copy
   is not. `opencode.json` is protected and outside this task's scope, so it was
   deliberately not rewritten. `python3 -I -m unittest discover -s scripts/tests
   -q` likewise reported 3 failures, all the same stale-profile assertion
   (`test_check_mode_detects_drift`,
   `test_committed_opencode_json_is_generated`,
   `test_repository_pack_is_valid`); the other 433 tests passed. The policy
   checks `check-docs.py`, `check-secrets.py`, `check-names.py`,
   `check-wiring.py` and `check-migrations.py --require-baseline` were run
   separately and passed. `bash scripts/frontend-check.sh` passed (`ok`).

Entry point to test trace:

- `useI18n().t(key)` → `I18nProvider` context value → `message()` →
  `resolveMessage()`; asserted by `src/i18n/format.localization.test.ts`
  (`uses the active locale translation when it exists`, two fallback cases).
- Locale toggle → `I18nProvider` effect writes `document.documentElement.lang`
  and `.dir` → `src/i18n/I18nProvider.direction.test.tsx`
  (`flips the document from English LTR to Arabic RTL and back`).
- `displayLocalName()` / `annotatedName()` → `src/i18n/format.localization.test.ts`
  (five local-name cases); `mixedScriptRun()` (one isolation case).
- `logicalContentOffset(tokens)` → `src/theme/logicalLayout.test.ts`
  (logical-only property names, token-derived values in light and dark).

## Coverage

Command and result: `bash apps/staff-web/check.sh` → 100% statements
(1389/1389), branches (700/700), functions (459/459), lines (1325/1325),
75/75 source files fully covered, per-file thresholds enabled.

New/changed branches and the test that takes each side:

| Branch | Test |
|---|---|
| `resolveMessage` first `??` — translated present | `uses the active locale translation when it exists` |
| `resolveMessage` first `??` — translated absent | `falls back to the English bundle when a key is absent in Arabic` |
| `resolveMessage` second `??` — English present | same fallback test |
| `resolveMessage` second `??` — English absent | `returns the key itself when no bundle has it` |
| `nameRun` `locale === 'ar'` true, `localName` present | `annotates the Arabic name with its language and isolated direction` |
| `nameRun` `locale === 'ar'` true, `localName` absent | `falls back to the Latin name when Arabic has no local form` |
| `nameRun` `locale === 'ar'` false | `uses the Latin name in an English interface even when a local form exists` |

`formatInstant`, `formatCivilDate`, `formatMeasurement` gained only the
`numberingSystem` option (no branch); their digit/separator assertions are in
`UX-01 numeric form is locale independent`. `annotatedName`, `mixedScriptRun`
and `logicalContentOffset` are straight-line functions; each is asserted by the
named tests above. `I18nProvider` and `ThemeProvider` were not changed.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result logic. Simulated/unit review only; no Arabic-speaking
clinician or real pilot feedback was collected. A site-level RTL review remains a
separate launch gate (design `rtl-and-localization.md` section 9).

## Findings

- The locale bundles were already complete and key-symmetric (23 keys in both
  `en` and `ar`), so "add Arabic messages" needed no new keys; the parity test
  in `tests/i18n/messages.test.ts` stays green.
- `dir`/`lang` mirroring already existed in `I18nProvider`; the scoped change
  adds a render test proving LTR → RTL → LTR, not duplicate logic.
- Design deviation 11 has two halves. The numeral-system half is fixed here.
  The non-breaking-space half (U+00A0 between value and unit) was intentionally
  left unchanged: the join is a plain space and an out-of-scope existing test
  (`tests/i18n/format.test.ts:66`) asserts exactly `'12.5 cm'`. Changing it
  would require editing a file outside the declared scope and rewriting that
  existing assertion. Follow-up for the owning module with an intentional test
  change, recorded here rather than hidden.
- `scriptAnnotation` is unused but retained: an out-of-scope test asserts its
  fixed `dir:'rtl'` result, so changing its contract is a separate decision.
  New mixed-script runs use `mixedScriptRun`/`annotatedName` with `dir:'auto'`.
- The rendered mirror and painted logical properties live in out-of-scope
  components and styles. This change supplies
