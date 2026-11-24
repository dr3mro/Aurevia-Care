# RESP-E2E-NAVIGATION-DRAWER-FLOW — staff-web navigation drawer e2e flow

## Summary

Added one Playwright spec, `apps/staff-web/e2e/navigationDrawerFlow.spec.ts`, that drives the real application shell in a real browser with synthetic `reception` data. It proves the compact drawer exposes exactly the six role-permitted destinations, closes by selection/Escape/backdrop with correct focus, keeps and releases keyboard focus, and mirrors under Arabic RTL; medium and expanded bands assert the corresponding role-scoped rail instead. No production file, config, package, dependency or tracker was changed. The brief was accurate; the only correction was the headless-UA client-support trap below, which is handled in test setup and reported to the owning specialist.

## Affected requirements

- **WEB-07** (`SMARTCLINIC_V1_SPEC.md:155`) — supported-client browser/vector matrix; measured `.app-shell[data-breakpoint]` across all configured projects.
- **UX-01** (`SMARTCLINIC_V1_SPEC.md:90`) — EN/AR, RTL mirroring and WCAG 2.2 AA on staff web.
- Adjacent context only: WEB-10 (`SMARTCLINIC_V1_SPEC.md:158`) appears in the reused `e2e/accessibility.ts` doc comment; not claimed by this task.

## Risk class

Cross-boundary verification (multiple viewports, locales, focus and accessibility boundaries), test-only. No clinical data, authorization or release behavior changed.

## Review owner

smartclinic-senior-react (staff web), smartclinic-senior-typescript (strict e2e TS), smartclinic-test-engineer (assertions first).

## Data changes

None. No migration, backfill, lock, volume impact, table, policy or contract changed. Synthetic fixture `syntheticStaff.roles = ['reception']` (`src/fixtures/synthetic.ts:24-29`) is the only identity source; no patient identifier or credential appears anywhere.

## Authorization boundaries and audit events

The test asserts the exact role-scoped link inventory from `navigationFor('reception')` — `/`, `/schedule`, `/schedule/book`, `/checkin`, `/patients`, `/account/sessions` — so a denied-role destination appearing in the drawer or rail fails the suite. Production `canAccessRoute`, tenant policy and audit events are untouched. A synthetic, recognised Chrome user agent is set with `test.use({ userAgent })` purely so the real `isSupportedBrowser` guard mounts the shell; the guard itself is not stubbed, bypassed or weakened, and its negative tests on `main` are unchanged.

## Migration and rollout considerations

None; no production change.

## Test evidence

All commands run from `apps/staff-web` unless stated. Exact final lines:

- `npx playwright test --config e2e/playwright.config.ts e2e/navigationDrawerFlow.spec.ts --list` → `Total: 110 tests in 1 file`.
- `npx playwright test --config e2e/playwright.config.ts e2e/navigationDrawerFlow.spec.ts --project=phone-portrait-390x844 --retries=0 --reporter=list` → `1 skipped` / `10 passed (4.8s)`.
- `npx playwright test --config e2e/playwright.config.ts e2e/navigationDrawerFlow.spec.ts --project=phone-landscape-844x390 --project=tablet-portrait-820x1180 --retries=0 --reporter=list` → `22 passed (6.6s)`.
- **Full browser run** `npx playwright test --config e2e/playwright.config.ts e2e/navigationDrawerFlow.spec.ts` → `2 skipped` / **`108 passed (30.5s)`**. The 2 skips are the one documented `test.fixme` on the two compact projects only. Chromium was installed for this run (`npx playwright install chromium` plus `npx playwright install-deps chromium`); WebKit/Firefox are absent, so the config omits those optional projects.
- `npm run lint` (strict, type-aware ESLint, `--max-warnings 0`) → exited 0, no findings.
- `npm run typecheck` → `> tsc --noEmit -p tsconfig.json`, exited 0, no `error TS…`.
- `npm run gen:api:check` → `gen:api: generated client matches the contract`.
- `node scripts/check-bundle-size.mjs` → exited 0, all chunk sizes `ok`.
- `bash apps/staff-web/check.sh` (from repo root) → all stages passed after the repair below (previous run had failed only on the stale tripwire pin).
- **Repair attempt 1 (tripwire pin refresh)** `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` → `Test Files 1 passed (1)`, `Tests 3 passed (3)`.
- **Repair attempt 1 full gate** `npm run test:coverage` (from `apps/staff-web`) → `Test Files 268 passed (268)`, `Tests 3045 passed (3045)`, `No files with missing coverage. 267 files fully covered.`; `npm run typecheck` and `npm run lint -- --max-warnings 0` both exited 0. The `jsdom "Not implemented: navigation"` lines are pre-existing console noise, not failures.

**Entry-point-to-test trace:** `page.goto('/')` → Vite dev server → `src/main.tsx` real `isSupportedBrowser` guard → `src/App.tsx` → `src/app/AppShell.tsx` → compact `src/app/NavigationDrawer.tsx` (menu button `aria-label=navigationLabel(locale,'navigation')` = "Sections", `#staff-navigation-drawer` dialog, `data-testid=nav-drawer-backdrop`) or medium/expanded `NavRail` (`.app-shell__nav[data-rail=icon|full]`) → link click/keyboard activation → `src/app/router.tsx` `navigate` → `main#main` focus on path change. Every assertion is made through these real components; no harness or API stub is used.

## Coverage

`Coverage: 100% — no production source line or branch was added or changed; the repair only refreshed a test-artifact pin.`

- Coverage command for the touched area: `npm run test:coverage` (`vitest run --coverage --exclude 'e2e/**'`) → `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`; `No files with missing coverage. 267 files fully covered. 8 empty files skipped.` `package.json:16-17` already excludes `e2e/**` and `vite.config.ts` `coverage.include` counts only `src/**/*.{ts,tsx}` with 100% per-file thresholds — none edited.
- No line, branch, method or class in `src/**` was added or changed, so per-file 100% is unchanged. Every one of the 110 new Playwright tests asserts an outcome (`toEqual`, `toBeVisible`, `expect.poll().toBe`, `toHaveCount`, `toBeFocused`, `toHaveAttribute`, `expectNoBlockingViolations`). No threshold, exclusion, ignore comment or skipped test was added; the single `test.fixme` is a task-authorised response to a confirmed defect, not a coverage bypass.

### Branch-to-test list (repair attempt 1)

The reported failure was **not an uncovered production branch**: it was the GAP tripwire's stale `currentSpecs` pin in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86`, which hardcoded 9 e2e spec names while the committed `e2e/` tree holds 15. Deterministic coverage of each assertion in that test:

| Tripwire assertion (line) | Deterministic test | Result after repair |
|---|---|---|
| `specs.sort()` equals pinned `currentSpecs` (166) | `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` — reads `e2e/**/*.spec.ts` from disk via `filesBelow`, compares to the refreshed 15-name pin | green — pin now matches the 15 committed specs |
| `workflows.sort()` equals `[]` (171) | same test — scans `.github/workflows/*.{yml,yaml}` for `playwright\|e2e` | green — no matching workflow (verified by shell scan) |
| `visualChecks.sort()` equals `[]` (178) | same test — regex-scans each spec for `toHaveScreenshot\|toMatchSnapshot\|getComputedStyle\|data-theme\|colorScheme` | green — no spec contains those tokens (verified by shell scan) |
| `expectTypeOf<Role>()` union (180) | same test — compile-time tripwire enforced by `npm run typecheck` | green |
| positive RTL/axe gate (104) and wrong-role guard (136) | the other two `it` blocks in the file | green (3/3 in the targeted run) |

- **Production branches added/changed by this repair: none.** Only the test pin's expected-value array changed; no `src/**` file, config, threshold, exclusion or `coverage.include` entry was touched. Full 100% coverage is re-proven by the command above.
- The pin refresh is the exact action the test's own comment mandates ("a new journey must replace this pin"): BUILD-60 added five bilingual journeys and this task added `navigationDrawerFlow.spec.ts` after the pin was last written at `6951647b`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical-result path changed.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

Advisory context: `smartclinic-context-optimizer` returned GO on the authoritative path/requirement map. The `smartclinic-senior-react`, `smartclinic-senior-typescript` and `smartclinic-test-engineer` skills and routing were loaded and their constraints applied (real shell entry point, strict TS with no `any`/`!`/ts-ignore, assertions-first); no formal verdict was requested from them by this task, so none is claimed here.

## Findings

1. **Headless UA fails the client-support guard (tool evidence, handled in test setup).** `navigator.userAgent` here is `… HeadlessChrome/159 …`; `src/features/system/browserSupport.ts:61` matches `\bChrome\/(\d+)`, which cannot match `HeadlessChrome`, so `src/main.tsx:22` rendered `UnsupportedBrowserPage` and the existing `smoke.spec.ts` first test already fails on compact projects. The new spec sets a synthetic modern Chrome UA via `test.use`, verified to mount `.app-shell[data-breakpoint=compact]`. No production rule changed.
2. **REAL DEFECT — open drawer fails WCAG 2.2 AA colour contrast (2.79:1).** `NavigationDrawer.tsx:133-147` gives the dialog no opaque surface; its links inherit `#0000ee` over the translucent scrim, and axe reports serious `color-contrast` on all six LTR links (`foreground #0000ee, background #8c8c8c, expected 4.5:1`, measured 2.79:1). Because `NavigationDrawer.tsx` is outside this task's scope, the one axe test is `test.fixme(true, reason)` on compact projects only; medium/expanded projects still scan green. Direction-specific evidence (tool): the LTR run reports the `color-contrast` violation on all six links, while the same open drawer in Arabic reports zero serious/critical violations, so the RTL journey still runs its localised axe gate unchanged. Follow-up requested below.
3. **REAL DEFECT — medium icon-rail pointer clicks are swallowed.** `AppShell.tsx:98-148` `IconRailLink.onFocus` reveals its tooltip, reflowing the link from 16×17 to 44×53 between pointerdown and mouseup; instrumented runs show only `pointerdown`/`mousedown`, no `mouseup`/`click`. Keyboard `press('Enter')` navigates correctly. The per-route test therefore uses keyboard activation on the medium band only; compact and expanded bands still use real pointer clicks. Follow-up requested below.
4. **REAL DEFECT — RTL skip link inflates the emulated mobile layout viewport.** `src/theme/responsive.css:181` `inset-inline-start: -9999px` becomes `right:-9999px` under `dir=rtl`, raising `document.documentElement.scrollWidth` to 10389 and `window.innerWidth` to 1560 on `isMobile` projects, pushing header buttons off-screen (menu box x=1476 at a 390 px viewport). The RTL test focuses the skip link first — the keyboard user's first Tab — which restores the 390 px layout, then opens the drawer by keyboard. Follow-up requested below.
5. **Pre-existing red gate (not caused by this change) — RESOLVED in repair attempt 1.** `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86,166` pinned a hardcoded `currentSpecs` list of 9 e2e spec names, but base `main` already had 14 (adminflow, financeflow, therapyflow, pharmacy-bilingual, radflow-bilingual were added without updating it); with `navigationDrawerFlow.spec.ts` the tree has 15. Confirmed by removing the new spec and running the file alone: `Test Files 1 failed (1)`, `Tests 1 failed | 2 passed (3)`. The repair loop returned this failure to this worker, so the pin was refreshed to the full 15-spec set and the whole `test:coverage` gate is now green. The follow-up task-request below for this item is therefore withdrawn.
6. The brief's "new file absent today" note is a confirmed planned path, not a stale reference; no other scope ambiguity found. `docs/design/responsive-layout.md` still describes a 320 px support floor while WEB-07 text says 768 px — the code and decision 0004 follow 320 px, so this test asserts the measured code, not the stale sentence.

## Lessons for workers

- Before writing any staff-web e2e that drives the real shell, set a supported synthetic user agent: the Playwright headless UA is rejected by `isSupportedBrowser` and silently renders the unsupported-client page. Always assert `.app-shell[data-breakpoint]` mounted before testing shell behavior.
- Branch e2e expectations on the measured `data-breakpoint`, never the Playwright project name: `phone-landscape-844x390` is medium and the default `chromium` project is expanded, so neither renders a drawer.
- For focus assertions that depend on a path change, start each journey on a route different from the destination; clicking the already-active item leaves focus on the opener, not on `<main>`.
- `test.fixme` is the only allowed skip-like construct in this repo and must be tied to a confirmed defect with a route/project reason; `test.skip`/`only`/`xit` are rejected by `check-constraints.py`.

## Resolution of the earlier gate failure

The earlier `bash apps/staff-web/check.sh` failure was a stale tripwire pin, not a coverage miss. It was reported here as out of scope, but the repair loop returned it to this worker, so the pin was refreshed to the 15 committed e2e specs (the exact action the test's own comment requires). Post-repair evidence:

```
npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts
Test Files  1 passed (1)
Tests       3 passed (3)

npm run test:coverage   (from apps/staff-web)
Test Files  268 passed (268)
Tests       3045 passed (3045)
No files with missing coverage. 267 files fully covered.
Statements 100%  Branches 100%  Functions 100%  Lines 100%
npm run typecheck -> exit 0 ; npm run lint -- --max-warnings 0 -> exit 0
```

Cause: the GAP tripwire's `currentSpecs` list was last updated in commit `6951647b`; `4698687f` (BUILD-60) added five bilingual journeys and `31312d08` added `navigationDrawerFlow.spec.ts` without refreshing it. The two open defects (drawer contrast, icon-rail/RTL layout) remain genuine follow-ups and are not production changes of this task.

The scoped work is complete and verified: 108 Playwright tests pass, 2 are the documented fixme, lint/typecheck/build/bundle/coverage checks pass, and no source coverage changed. Nothing is left unfinished.

## task-requests

```json
[
  {
    "title": "Give the compact navigation drawer a contrast-safe opaque surface",
    "details": "apps/staff-web/src/app/NavigationDrawer.tsx:133-147 renders the modal dialog without a background, so its links inherit #0000ee over the translucent scrim and axe reports serious color-contrast 2.79:1 (needs 4.5:1) on all six destinations in English. Add an opaque themed surface and a contrast-safe link colour, cover it with an axe/component test, then remove the test.fixme in apps/staff-web/e2e/navigationDrawerFlow.spec.ts (open navigation has no blocking accessibility violations).",
    "scope": ["apps/staff-web/src/app/NavigationDrawer.tsx", "apps/staff-web/src/theme/responsive.css", "apps/staff-web/e2e/navigationDrawerFlow.spec.ts"],
    "depends": ["RESP-E2E-NAVIGATION-DRAWER-FLOW"]
  },
  {
    "title": "Stop icon-rail tooltip reflow swallowing pointer clicks and RTL skip-link overflow",
    "details": "Two measured layout defects: (a) apps/staff-web/src/app/AppShell.tsx IconRailLink.onFocus reveals a tooltip that reflows the link from 16x17 to 44x53, so a real pointer click never fires (only pointerdown/mousedown) on the medium band; make the tooltip overlay without reflowing the anchor. (b) apps/staff-web/src/theme/responsive.css:181 skip-link inset-inline-start:-9999px becomes right:-9999px in RTL, inflating document scrollWidth to 10389 and, with isMobile emulation, window.innerWidth to 1560 so header controls go off-screen. Fix both with regression tests; then the medium per-route journey can use real clicks instead of keyboard activation.",
    "scope": ["apps/staff-web/src/app/AppShell.tsx", "apps/staff-web/src/theme/responsive.css"],
    "depends": ["RESP-E2E-NAVIGATION-DRAWER-FLOW"]
  }
]
```
