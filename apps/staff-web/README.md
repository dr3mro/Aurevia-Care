# staff-web

React + TypeScript + Vite visual foundation for the SmartClinic staff portal.
Only synthetic fixtures are used. No application endpoint is contacted and no
authentication logic is implemented.

## Requirements

- PRD-06: clinical users decide and attest; this module contains no autonomous
  clinical decision or care action. Sign-in and protected routes are visual
  placeholders only.
- UX-01: English and Arabic bundles, RTL direction, local-name and mixed-script
  display, UTC instants with IANA zones and civil dates, and themed accessible
  primitives.
- ARC-03: request descriptors use the generated contract paths, explicit
  tenant/correlation/idempotency/version metadata and a refusal to call any
  real endpoint.

## Commands

Run from this directory. The toolchain is pinned exactly in `package.json`,
`package-lock.json` and `.nvmrc`; use `npm ci` for a reproducible install.

```sh
npm ci
npm run gen:api          # regenerate client types from contracts/openapi.yaml
npm run gen:api:check    # fail if checked-in types drift from the contract
npm run typecheck        # strict TypeScript, no emit
npm run lint             # ESLint, warnings are errors
npm run test:coverage    # Vitest with 100% per-file thresholds
npm run build            # production build
bash check.sh            # all of the above gates in the canonical order
bash docs.sh ABS_DIR     # TypeDoc reference, fails on warnings, writes index.html
```

## Generated client and coverage scope

`src/api/generated/openapi.d.ts` is produced by `scripts/gen-api-types.sh`
using the pinned `openapi-typescript` CLI against `contracts/openapi.yaml`.
It is checked in, carries the tool's generated-file header, and is verified by
`npm run gen:api:check`. It is never edited by hand and never copied manually.

Coverage uses `include: ['src/**/*.{ts,tsx}']` with `perFile: true` and 100%
thresholds for lines, branches, functions and statements. The only exclusions
are the generated declaration directory and `**/*.d.ts`: declaration files
emit no runnable code, and their freshness is enforced by the drift check.
Every hand-written source file is therefore required to be fully covered; an
unimported source file fails the gate.

## End-to-end and accessibility checks (WEB-10)

The Playwright suite in `e2e/` verifies the home page title and skip-link tab
order, an axe scan of the development-only `/design-preview` route in English
and Arabic, the RTL document direction after a language toggle, and the absence
of horizontal scrolling at 768 px. It uses synthetic fixtures only.

A Chromium browser binary must be installed once per machine:

```sh
npx playwright install chromium
npm run e2e
```

With the default `E2E_BASE_URL` (`http://127.0.0.1:5173`), `npm run e2e` starts
the Vite dev server itself. To test another deployment, set `E2E_BASE_URL`; the
caller then owns that server and no local one is started. Any external target
must still be a dev build exposing `/design-preview`. The suite is not in
`check.sh` because the worker image has no browser binaries; a person must add
a CI job that installs Chromium and runs it. The unit scripts deliberately pass
`--exclude 'e2e/**'` so Vitest collects only the jsdom unit tests and leaves the
Playwright specs to their own runner.

## Responsive layout

The staff web adapts layout to viewport width; presentation changes never alter
the data, the available actions or what the server authorizes (WEB-07, UX-01).

- **Width classes.** `Breakpoint` in `src/theme/breakpoints.ts` has three values:
  `compact` (0 to 767 px, phone), `medium` (768 to 1279 px, tablet) and
  `expanded` (1280 px and above, desktop). `BREAKPOINT_WIDTHS` holds the two
  `min-width` edges (768 and 1280), and `breakpointFor(width)` maps a measured
  width to one of the three classes. `compact` is the absence of both queries,
  so it has no media query of its own.
- **Shared stylesheet and hook.** `src/theme/responsive.css` holds the layout
  rules at `min-width: 768px` and `min-width: 1280px`. Components read the active
  class from the `useBreakpoint()` hook in `src/theme/useBreakpoint.ts` and let
  that stylesheet supply the rules. Screens mirror the resolved class onto
  `data-breakpoint`, so a DOM test can assert the active band and a Playwright
  test can check the painted layout.
- **Device projects.** `e2e/playwright.config.ts` defines one Chromium project
  per device class and viewport. Run a single project with a real, exact name:

```sh
npx playwright install chromium        # once per machine
npm run e2e -- --project=phone-portrait-390x844
```

  Other real names include `phone-portrait-360x740`, `phone-landscape-844x390`,
  `tablet-portrait-768x1024`, `tablet-landscape-1024x768`, `laptop-1280x720` and
  `desktop-1920x1080`; `npm run e2e` without `--project` runs them all. The
  shorter names in `docs/design/responsive-layout.md` (`phone-portrait`,
  `tablet-portrait`, `desktop-en`, ...) describe the planned family, not
  runnable project names: Playwright matches names exactly. WebKit and Firefox
  variants (`...-webkit`, `...-firefox`) are added only when those browser
  binaries are installed; keep the design doc and the config together when
  changing a name.
- **Emulating a phone on desktop.** In Chromium, open DevTools, toggle the device
  toolbar and set a 390 x 844 viewport with touch and mobile emulation enabled;
  this matches the `phone-portrait-390x844` project. Rotate to 844 x 390 for the
  landscape class and repeat in Arabic to check the RTL mirror. For an
  automated run use the project instead of the toolbar.
- **No hidden clinical fields.** A feature must never hide a clinical field or
  table column on a small screen: no `display: none` on clinical content and no
  `.hide-compact` on a field or column. Reflow into a scroll region, a card or a
  wrapping row instead. `.hide-compact` exists for non-clinical chrome only; a
  breakpoint changes density and layout, not which values a clinician can see.

## Design notes

- Tokens live in `src/theme/tokens.ts` and are resolved by `ThemeProvider`,
  which mirrors the light/dark mode onto `document.documentElement.dataset.theme`.
- `I18nProvider` mirrors locale onto `document.documentElement.lang` and `dir`;
  Arabic switches the document to RTL. Mixed-script labels use `<bdi dir="auto">`
  so bidirectional runs cannot leak direction.
- `formatInstant` uses `Intl.DateTimeFormat` with an IANA zone so DST
  transitions are handled by the runtime; `formatCivilDate` formats at UTC
  midnight so the calendar day does not shift.
- Dialogs, tables, fields and buttons use native roles and labels; toggles
  expose `aria-pressed`. These are visual placeholders for WCAG 2.2 AA patterns,
  not clinical workflows.
- TypeDoc is configured with `treatWarningsAsErrors`. Validation of
  "referenced but not exported" symbols is disabled only because generated
  contract types are intentionally outside the hand-authored documentation.

CI note: the repository developer-reference job must provide a pinned Node
toolchain and run `npm ci --prefix apps/staff-web` before invoking this
module's `docs.sh`; that wiring lives outside this module's scope.

## Mutation testing

Run `npm run mutation` (opt-in; not part of check.sh or CI by default). Configure scope in `stryker.config.json`.
