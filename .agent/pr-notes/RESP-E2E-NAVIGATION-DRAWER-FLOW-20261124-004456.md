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
- `bash apps/staff-web/check.sh` (from repo root) → generated-client check, typecheck, lint, build and bundle-size stages passed; **final `npm run test:coverage` stage failed on a pre-existing tripwire**, `Test Files 1 failed | 267 passed (268)`, `Tests 1 failed | 3044 passed (3045)`. See `## Why this is not finished`.

**Entry-point-to-test trace:** `page.goto('/')` → Vite dev server → `src/main.tsx` real `isSupportedBrowser` guard → `src/App.tsx` → `src/app/AppShell.tsx` → compact `src/app/NavigationDrawer.tsx` (menu button `aria-label=navigationLabel(locale,'navigation')` = "Sections", `#staff-navigation-drawer` dialog, `data-testid=nav-drawer-backdrop`) or medium/expanded `NavRail` (`.app-shell__nav[data-rail=icon|full]`) → link click/keyboard activation → `src/app/router.tsx` `navigate` → `main#main` focus on path change. Every assertion is made through these real components; no harness or API stub is used.

## Coverage

`Coverage: N/A — e2e excluded, no production source file changed.`

- Coverage command for the touched area: `bash apps/staff-web/check.sh` → `npm run test:coverage` (`vitest run --coverage --exclude 'e2e/**'`). `package.json:16-17` already excludes `e2e/**` and `vite.config.ts` `coverage.include` counts only `src/**/*.{ts,tsx}` with 100% per-file thresholds — none edited.
- No line, branch, method or class in `src/**` was added or changed, so per-file 100% is unchanged. Every one of the 110 new Playwright tests asserts an outcome (`toEqual`, `toBeVisible`, `expect.poll().toBe`, `toHaveCount`, `toBeFocused`, `toHaveAttribute`, `expectNoBlockingViolations`). No threshold, exclusion, ignore comment or skipped test was added; the single `test.fixme` is a task-authorised response to a confirmed defect, not a coverage bypass.

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
5. **Pre-existing red gate (not caused by this change).** `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86,166` pins a hardcoded `currentSpecs` list of 9 e2e spec names, but base `main` already has 14 (adminflow, financeflow, therapyflow, pharmacy-bilingual, radflow-bilingual were added without updating it). Verified by removing the new spec and running the file alone: `Test Files 1 failed (1)`, `Tests 1 failed | 2 passed (3)`. With the new spec the list is 15. The file is outside this task's scope, so it is reported with a follow-up request rather than edited.
6. The brief's "new file absent today" note is a confirmed planned path, not a stale reference; no other scope ambiguity found. `docs/design/responsive-layout.md` still describes a 320 px support floor while WEB-07 text says 768 px — the code and decision 0004 follow 320 px, so this test asserts the measured code, not the stale sentence.

## Lessons for workers

- Before writing any staff-web e2e that drives the real shell, set a supported synthetic user agent: the Playwright headless UA is rejected by `isSupportedBrowser` and silently renders the unsupported-client page. Always assert `.app-shell[data-breakpoint]` mounted before testing shell behavior.
- Branch e2e expectations on the measured `data-breakpoint`, never the Playwright project name: `phone-landscape-844x390` is medium and the default `chromium` project is expanded, so neither renders a drawer.
- For focus assertions that depend on a path change, start each journey on a route different from the destination; clicking the already-active item leaves focus on the opener, not on `<main>`.
- `test.fixme` is the only allowed skip-like construct in this repo and must be tied to a confirmed defect with a route/project reason; `test.skip`/`only`/`xit` are rejected by `check-constraints.py`.

## Why this is not finished

`bash apps/staff-web/check.sh` does not end green, but the failure is pre-existing and outside this task's scope. Exact failing command and result:

```
bash apps/staff-web/check.sh
npm run test:coverage -> vitest run --coverage --exclude 'e2e/**'
Test Files  1 failed | 267 passed (268)
Tests  1 failed | 3044 passed (3045)
FAIL tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts
  > WEB-10 clause 2 ... > GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded
AssertionError: expected [ …(15) ] to deeply equal [ …(9) ]   at ...test.ts:166:26
```

Most likely cause: the GAP tripwire's hardcoded `currentSpecs` list was last updated in commit `6951647b` while per-role journey specs were added later without updating it. Verified by removing the new spec and running that test file alone — it still fails (1 failed | 2 passed), so base `main` is red. What was tried: confirmed every other check.sh stage (gen:api check, typecheck, lint, build, bundle size) passes; ran the full Playwright matrix green apart from the one authorised fixme; attempted no out-of-scope edit. Exact next step: a worker with `apps/staff-web/tests/spec/platform/` in scope updates `currentSpecs` to the full current 15-spec set, reconciles the WEB-10 clause note, and reruns `bash apps/staff-web/check.sh`.

The scoped work itself is complete and verified: 108 Playwright tests pass, 2 are the documented fixme, lint/typecheck/build/bundle checks pass, and no source coverage changed.

## task-requests

```json
[
  {
    "title": "Refresh WEB-10 real-browser journey tripwire spec list",
    "details": "apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86 hardcodes currentSpecs with 9 names; base main already has 14 e2e specs and 15 after navigationDrawerFlow.spec.ts, so the GAP assertion at line 166 fails check.sh's final vitest stage on a clean tree. Add the full current spec set including navigationDrawerFlow.spec.ts, reconcile the WEB-10 clause note, and decide whether the GAP tripwire should remain now that per-role journeys and the axe gate exist. Verified failing with the new spec removed (1 failed | 2 passed).",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": ["RESP-E2E-NAVIGATION-DRAWER-FLOW"]
  },
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
