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
