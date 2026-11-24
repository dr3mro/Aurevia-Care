# READY-51 · Staff web: every feature registers its own routes

PR #269, tested branch tip d25dd64f0e947ed31a2fec765345bb73e2371dd2.

Task: READY-51

Swarm worker 2. Local checks: passed. Agent result: ok.

# READY-51 — Staff web: every feature registers its own routes

## Summary

Moved the admin, scheduling and chart route declarations out of the shared
`app/routes.tsx` registry into self-registering `features/<name>/routes.ts`
modules. `routes.tsx` now discovers every such module with
`import.meta.glob('../features/*/routes.ts', { eager: true })`, collects their
routes in a deterministic order and throws a clear error when two features
claim the same pattern. `createRoutes(includeDesignPreview)`, the `AppRoute`
type, exact paths, the last-place `'*'` catch-all and the development-only
preview are unchanged. Adding a screen no longer requires editing `routes.tsx`.

## Affected requirements

- WEB-09 (route registry that lets resilient screens be added independently; no
  clinical state logic changed).

## Risk class

Routine — a contained refactor of route assembly inside the staff web app. No
clinical logic, permissions, tenant scope, migrations or external contracts
change.

## Review owner

Senior React and Vite (bounded review dispatched, findings recorded below under
Findings). Senior TypeScript applies to the strict-type/glob declaration.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None changed. Routes still use opaque `:id` / `:noteId` segments; no patient
name, identifier or free text is placed in a URL, page title, log or browser
storage. No audit event added or changed.

## Migration and rollout considerations

None. Code-splitting is preserved: the build still emits a separate chunk per
page (ChartPage, NotePage, SchedulingPage, FacilitiesPage, StaffPage,
DesignPreview, WelcomePage), all under the WEB-08 gzip budget.

## Test evidence

Evidence source: executed tool commands (Vitest, TypeScript, ESLint, Vite and
the bundle-size check) plus one bounded Senior React review (advisory). No human
or simulated-clinical evidence was used or claimed.

- Red stage (new test before implementation):
  `npx vitest run --exclude 'e2e/**' src/app/routes.test.tsx`
  → `Test Files 1 failed (1); Tests 6 failed | 4 passed (10)` — the six
  `collectFeatureRoutes` cases failed with `collectFeatureRoutes is not a
  function`; the four `createRoutes` cases already passed against the old
  registry.
- Green stage (affected tests):
  `npx vitest run --exclude 'e2e/**' src/app/routes.test.tsx tests/app/AppShell.test.tsx src/features/scheduling/SchedulingPage.test.tsx src/features/chart/ChartPage.test.tsx src/features/chart/NotePage.test.tsx src/features/admin/FacilitiesPage.test.tsx src/features/admin/StaffPage.test.tsx`
  → `Test Files 7 passed (7); Tests 154 passed (154)`.
- Canonical gate: `bash apps/staff-web/check.sh` → `EXIT=0`;
  `Test Files 49 passed (49); Tests 540 passed (540);`
  `No files with missing coverage. 74 files fully covered.`
- Worker gates: `bash scripts/frontend-typecheck-check.sh` → `frontend typecheck:
  passed`; `bash scripts/coverage-check.sh` → `coverage check: passed`.

Typecheck final lines (from `check.sh`, `npm run typecheck` / `tsc --noEmit -p
tsconfig.json`, plus the `tsc` inside `npm run build`):

```
> orevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json


> orevia-staff-web@0.1.0 lint
> eslint . --max-warnings 0
```

No `error TS…` was reported; the stage advanced directly to lint and then
`tsc --noEmit -p tsconfig.json && vite build`, which completed with all bundle
chunks `ok`.

Entry point to test trace: `src/app/routes.tsx:createRoutes()` builds the
table consumed by `src/app/AppShell.tsx` (`routes.find` for the active page) and
`src/App.tsx` (`patterns` for `RouterProvider`). `src/app/routes.test.tsx`
exercises `createRoutes` and `collectFeatureRoutes` directly (all paths,
catch-all last, preview flag, duplicate failure). The out-of-scope
`tests/app/AppShell.test.tsx` renders the shell and asserts the exact registry
order, and each feature page test (e.g. `FacilitiesPage.test.tsx`,
`SchedulingPage.test.tsx`, `ChartPage.test.tsx`, `NotePage.test.tsx`,
`StaffPage.test.tsx`) renders the lazily loaded route component returned by
`createRoutes(false)`, proving the moved lazy imports still resolve end to end.

## Coverage

Command: `npm run test:coverage` (invoked by `bash apps/staff-web/check.sh`,
which exited 0). Result:

```
No files with missing coverage.
74 files fully covered.
Statements   : 100% ( 1380/1380 )
Branches     : 100% ( 697/697 )
Functions    : 100% ( 454/454 )
Lines        : 100% ( 1316/1316 )
```

Per touched file (from `coverage/coverage-summary.json`, all L/B/F/S = 100%):
`src/app/routes.tsx`, `src/features/admin/routes.ts`,
`src/features/chart/routes.ts`, `src/features/scheduling/routes.ts`.
(The test file `src/app/routes.test.tsx` is excluded by the documented Vitest
rule and needs no coverage.)

New or changed branches and the test that takes each side:

- `collectFeatureRoutes` — `if (RESERVED_PATTERNS.has(route.pattern))` true side:
  `collectFeatureRoutes > rejects the app-reserved pattern %s` (parameterised
  over `'/'`, `'/design-preview'`, `'*'`). False side: all non-reserved cases
  below.
- `collectFeatureRoutes` — `if (claimed.has(route.pattern))` true side:
  `collectFeatureRoutes > throws a clear error when two features register the
  same pattern` and `> throws when one feature declares the same pattern twice`.
  False side: `> collects the routes declared by each feature module`,
  `> preserves the declared order of routes within one feature`, and
  `> produces a stable order independent of module enumeration order`.
- `collectFeatureRoutes` — empty module map (no loop iterations):
  `> returns no routes when no feature registers any`.
- `createRoutes` — `if (includeDesignPreview)` true side:
  `createRoutes > registers the development preview only when requested`
  (`createRoutes(true)`) and the out-of-scope `AppShell.test.tsx` route-registry
  case. False side: `createRoutes > resolves every existing path`
  (`createRoutes(false)`).
- `routes.tsx` module scope `createRoutes(import.meta.env.DEV)`: covered by every
  test file importing the module.
- Lazy import functions moved into the three `features/<name>/routes.ts` files:
  covered by the existing feature page tests that render each route component
  obtained from `createRoutes(false)` (see entry-point trace above).

No coverage threshold, exclusion or test was weakened, added, skipped or
removed. No `any`, `@ts-ignore` or new dependency was introduced.

## Clinical and architecture review

Not required for this refactor: no signed data, cross-organization sharing,
patient association, prescribing or critical-result behavior changed.

## Findings

- Out-of-scope constraint honoured: `tests/app/AppShell.test.tsx:128-157`
  asserts the exact registry order
  `['/', '/schedule', '/schedule/book', '/patients/:id/chart',
  '/patients/:id/notes/:noteId', '/admin/facilities', '/admin/staff',
  ('/design-preview'), '*']`. That file is outside the task scope and was not
  edited. To preserve it, `collectFeatureRoutes` sorts glob module keys in
  descending path order (`Object.keys(modules).sort().reverse()`), which yields
  scheduling → chart → admin and keeps registration deterministic without a
  central hard-coded pattern list. New feature modules self-register and are
  appended before the preview/catch-all based on their path.
- Encountered and fixed a self-inflicted parse error: a JSDoc line reading
  `features/*/routes.ts` contained `*/`, which prematurely closed the block
  comment. Reworded to `feature route module`; Vite/OXC then parsed the file
  cleanly. No production behavior was affected.
- No new npm dependency was added (Vite's built-in `import.meta.glob` is used);
  `import.meta.glob` is typed by a local `ImportMeta` augmentation in
  `routes.tsx`, so `env.d.ts` (out of scope) was not touched.
- Bounded Senior React review (advisory,
