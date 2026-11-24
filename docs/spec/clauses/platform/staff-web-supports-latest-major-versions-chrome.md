# WEB-07 clause 2 — staff web supports the latest major desktop browsers at 1280 px and above

## Clause

Staff web supports the latest two major versions of Chrome, Edge, Firefox and Safari on desktop at 1280 x 720 and above, with a compact layout down to 768 px width.

## Status

Proven by tests

## Evidence

Test class: `WEB-07 clause 2 — support latest major desktop browsers at 1280 px and above` (Vitest `describe` block) in `apps/staff-web/tests/spec/platform/staff-web-supports-latest-major-versions-chrome.test.ts`. It uses the same Vitest + jsdom configuration and `tests/setup.ts` cleanup as the neighbouring `tests/spec/localization/` and `tests/features/system/` suites. Staff web has no database or server in its test path, so the suite contacts no endpoint and sets up no database. All six tests are active (none skipped) and pass. Fixtures are synthetic user agents and viewport widths only; no identifiers or patient data appear.

"Proven by tests" covers this clause's behaviour only. The real-browser matrix named by WEB-07's last sentence is clause 5, owned by a separate task; it is recorded under `## Gap` and is not claimed here.

Test methods:

- `supports the current and two prior majors of each desktop browser at 1280 px` — pins the documented baselines (Chrome, Edge and Firefox at 130; Safari at 17; a two-major window; a 320 px reflow floor), then drives Chrome, Edge, Firefox and Safari at the baseline, one behind and two behind at 1280 px through `isSupportedBrowser` and asserts `true` for all twelve pairs. Pinning the constants keeps a silent baseline change from making the matrix vacuously pass.
- `recognises Edge by its own token when the Chrome token disagrees` — supplies two synthetic Edge agents whose `Edg` and `Chrome` majors conflict (a current Edge with a three-majors-behind Chrome token, and the reverse) and asserts `true` then `false`. Because real Edge advertises both tokens, this proves the more-specific `Edg` rule is evaluated before the generic `Chrome` rule; without it, deleting the Edge rule would still leave the basic Edge cases green.
- `keeps the layout supported at 1280, 1024 and 768 px` — for a supported Chrome agent, asserts `isSupportedBrowser` is `true` at 1280, 1024 and 768 px and that `breakpointFor` resolves those widths to `expanded`, `medium` and `medium`. This pins the compact boundary at 768 px. Under decision 0004 the `medium` class is the icon-rail tablet layout that carries the specification's "compact layout down to 768 px"; the `compact` class covers 320–767 px and is a superset of the clause rather than a contradiction of it.
- `refuses a browser three majors behind` — drives each of the four browsers one major beyond the supported window (127 for the Chromium browsers, 14 for Safari) and asserts `false`, fixing the inclusive version boundary.
- `refuses Internet Explorer and unrecognised user agents` — asserts `false` for a Trident IE agent and for `curl/8.5.0`, proving both the explicit IE guard and the fail-closed final refusal for an unmatched engine.
- `refuses a viewport below the 320 px reflow floor` — uses a *supported* Chrome agent at `MIN_SUPPORTED_WIDTH - 1` (319 px) and asserts `false`, isolating the width guard so no user-agent reason can explain the refusal.

Production modules exercised through their normal entry points:

- `isSupportedBrowser` and the exported baseline constants in `apps/staff-web/src/features/system/browserSupport.ts` — the pure support decision, its version window, its reflow floor and its fail-closed behavior.
- `breakpointFor` and `BREAKPOINT_WIDTHS` in `apps/staff-web/src/theme/breakpoints.ts` — the 768/1280 layout tokens.
- `apps/staff-web/src/main.tsx` — the real entry point that calls `isSupportedBrowser(navigator.userAgent, window.innerWidth)` and mounts either `App` or the unsupported tree. `apps/staff-web/tests/main.test.tsx` proves that switch with the real guard (including 768 px, 360 px and 319 px).
- `apps/staff-web/src/features/system/UnsupportedBrowserPage.tsx` — the plain message shown instead of the application; `apps/staff-web/tests/features/system/systemPages.test.tsx` asserts it renders the notice and no navigation, table or application data.
- `apps/staff-web/src/theme/responsive.css` — the compact, medium and expanded layout rules, already covered by `apps/staff-web/tests/theme/responsiveStylesheet.test.ts` and `apps/staff-web/tests/theme/breakpoints.test.ts`; this clause does not restate that coverage.

Entry-point-to-test trace: `src/main.tsx` selects the application tree only when `isSupportedBrowser` returns `true`, and re-runs the check on `resize` and `orientationchange`. The new test calls that same guard with synthetic user agents and widths and separately checks the breakpoint resolver the rendered layout uses. The `tests/main.test.tsx` cases then prove the entry point honours the same decision at the 768 px and 319 px boundaries, and `tests/features/system/systemPages.test.tsx` proves the refusal path renders no clinical data. No production file was changed by this task.

## Gap

The clause's support rule is fully proved by the tests above; no production behaviour for this clause is missing. Two items are deliberately outside this task:

1. The real-browser end-to-end matrix from WEB-07's last sentence is clause 5, owned by a separate task whose scope is `apps/staff-web/tests/spec/platform/verify-browser-matrix-end-end-suite.test.ts` and `docs/spec/clauses/platform/verify-browser-matrix-end-end-suite.md`, and ultimately by WEB-10. It needs one separate task; synthetic unit fixtures cannot substitute for real Chrome, Edge, Firefox and Safari builds. The `720` in "1280 x 720" has no code path in `isSupportedBrowser`, which takes only a width, so viewport height is also exercised only by that real-browser matrix.
2. The baselines pinned in `browserSupport.ts` (130/130/130/17 and the two-major window) are documented fallbacks, not live browser versions. They need periodic refresh as browsers release, but this is a maintenance risk, not a missing proof of the clause.

Decision 0004 also remains `Proposed` and the specification's 768 px / desktop wording has not been amended by a person; the code follows the decision and supports down to 320 px, which still satisfies this clause at 768 px. That amendment is an owner action, not an in-scope fix.
