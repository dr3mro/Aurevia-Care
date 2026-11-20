# READY-10 — Staff web navigation model from the screen map

## Requirement IDs

- **UX-01** (SMARTCLINIC_V1_SPEC.md §6 / §8.1): English and Arabic, right-to-left direction, accessible staff web.
- **PRD-05** (spec §1): English and Arabic operation for one market.
- **WEB-07 / WEB-09** (spec §8.1): supported clients and resilient screens; role-scoped navigation is part of the shell.
- **IAM-01** (spec §2): access is scoped by active role; items a role cannot use are not rendered.
- Design contract: `docs/design/screens-and-navigation.md` §2 (screen map) and §1 (application shell).

## Files changed (all strictly inside `apps/staff-web/src/app`)

| Path | Change |
|---|---|
| `apps/staff-web/src/app/navigation.ts` | New — typed screen-map data (`navigation`), `Role`, `NavigationItem`, `NavigationGroup`, `NavigationLabelKey`, `navigationFor(role)`, `navigationGroupsFor(role)`, and an en/ar label bundle with `navigationLabel` / `groupLabelKey`. |
| `apps/staff-web/src/app/AppShell.tsx` | Modified — new optional `role` prop (default `reception`), renders a grouped left rail from `navigationFor(role)` beside `<main>` in a logical flex row. |
| `apps/staff-web/src/app/navigation.test.tsx` | New — test-first: 14 tests covering role filtering, uniqueness, label completeness, table fidelity, the rail component and RTL mirroring. |
| `.agent/pr-notes/READY-10.md` | This record (PR description). |

No tracker, workflow, `.opencode`, migration, i18n-module or file outside the declared scope was edited.

## Acceptance mapping

| Story step / criterion | Implementation | Test assertion |
|---|---|---|
| 1. Typed array `{ id, route, group, labelKey, roles: Role[] }` from §2, every row with a route starting `/` except System | `navigation.ts` `NavigationItem` and `navigation` (24 rows, in table order, System group excluded) | `navigation.test.tsx` "adds no System route and no route outside the screen map" asserts every route starts `/` and no System route appears |
| `navigationFor(role)` returns only the role's items, grouped in table order | `navigationFor` filters exact role match plus the `staff` base role; `navigationGroupsFor` groups consecutive rows | "gives reception…", "gives the tenant admin…", "gives the privacy auditor…", "groups items in the table order" |
| Reuse existing role names / create `Role` beside them | `Role` defined in `navigation.ts`: a superset containing `tenant_admin`, `privacy_auditor`, `reception` from `features/admin/domain.ts`'s narrower `AdminRole`, plus the other screen-map roles and the `staff` base role | "never shows the same item twice, for any role" iterates all 12 roles |
| 2. `AppShell` renders the rail from `navigationFor(role)` | `AppShell` calls `navigationGroupsFor(role)` and renders one `<section>` per group with a labelled `<ul>` of links | "renders the reception rail…", "renders the Administration region…", "renders only the audit and account links…", "defaults to the synthetic reception role…" |
| Labels come from i18n messages in English and Arabic | `navigationLabels` en/ar bundle + `navigationLabel(locale, key)`; AppShell uses `locale` from `useI18n()` | "has a label for every item and group in both languages", "resolves a navigation label for the active locale", Arabic rail test |
| Mirror the rail in RTL | Rail is the first child of a `flexDirection: 'row'` container; the document `dir` mirrors it, and no physical `left`/`right` style is used | "mirrors the rail in Arabic using logical layout only" asserts `document.dir === 'rtl'`, Arabic labels and no `left:`/`right:` inline style |
| 3. Reception sees Scheduling, Clinic, Patients, not Administration | Role filter | "gives reception…" (model) + "renders the reception rail… and hides Administration" (component) |
| Tenant admin sees Administration | Role filter | "gives the tenant admin…" + "renders the Administration region for a tenant admin" |
| Privacy auditor sees only the audit item and Account | `['privacy_auditor','tenant_admin']` on the audit row; Today and Account are the table's "all staff" rows available to every role | "gives the privacy auditor only the audit item plus the two all-staff items" asserts exact ids `['home','admin-audit','account-sessions']`; component test asserts links `['Today','Audit','Sessions']` |
| No item appears twice | Unique ids and a single filter pass | "never shows the same item twice, for any role" |
| Every label key exists in both languages | `Record<Locale, Record<NavigationLabelKey, string>>` plus runtime assertions | "has a label for every item and group in both languages" |
| Do NOT add a route for a screen without a table row; do NOT show items the role cannot use | Data is copied from §2 only; denial is by omission from `roles` | System-route test and all three role-filter tests |

## Entry-point-to-test trace

Screen-map row → `navigation` array (`apps/staff-web/src/app/navigation.ts`) → `navigationFor(role)` → `AppShell({ role })` rail `<section>`/`<ul>`/`<a href>` → `navigation.test.tsx` queries the rail with Testing Library (`getByRole('group', {name})`, `within(...).getAllByRole('link')`, `getByRole('region', {name})`) and reads the model directly. The existing `tests/app/AppShell.test.tsx` still renders `<AppShell />` (no role) and is unaffected by the default.

## Test-first (TDD) evidence

- **Red** — `node_modules/.bin/vitest run src/app/navigation.test.tsx` before creating `navigation.ts`: `Test Files 1 failed (1)`, `Tests no tests`, `Error: Failed to resolve import "./navigation" from "src/app/navigation.test.tsx". Does the file exist?`
- **Red (component stage)** — after adding `navigation.ts` but before editing `AppShell.tsx`: `Tests 1 failed | 11 passed (12)`; the five rail tests failed with `Unable to find an accessible element with the role "group" and name "Sections"`.
- **Green** — after editing `AppShell.tsx`: `Test Files 1 passed (1)`, `Tests 13 passed (13)`; after the reviewer-driven heading fix and parity test, the targeted file passed `Tests 14 passed (14)`.

## Commands run and results (tool evidence)

All commands executed locally in this checkout on branch `swarm/READY-10-266`, start HEAD `59e3096`.

| Command | Result |
|---|---|
| `npm ci --no-audit --no-fund` (apps/staff-web) | exit 0 — `added 312 packages in 3s` (deprecation warnings only) |
| `node_modules/.bin/vitest run src/app/navigation.test.tsx` (before implementation) | exit 1 — 1 failed suite, missing `./navigation` (expected red) |
| `node_modules/.bin/vitest run src/app/navigation.test.tsx` (after model, before AppShell edit) | exit 1 — 1 failed / 11 passed (expected red for the rail) |
| `node_modules/.bin/vitest run src/app/navigation.test.tsx` (final) | exit 0 — `Test Files 1 passed (1)`, `Tests 13 passed (13)` |
| `npm run typecheck` | exit 0 — `tsc --noEmit -p tsconfig.json` |
| `npm run lint` | exit 0 — `eslint . --max-warnings 0` |
| `bash apps/staff-web/check.sh` (repo root) | exit 0 — gen:api:check, typecheck, lint, build, bundle-size gate and test:coverage all pass; `Test Files 34 passed (34)`, `Tests 249 passed (249)`; coverage `Statements 100% (653/653)`, `Branches 100% (339/339)`, `Functions 100% (243/243)`, `Lines 100% (617/617)`, `47 files fully covered` (final tree, after the specialist-review fixes) |
| `python3 -I scripts/check-names.py` | exit 0 — no story/gate-ID path or package name |
| `git status --short` | only the three scoped paths above plus this note |

The stack traces in the `check.sh` output are the expected console output of the pre-existing negative test `tests/theme/ThemeProvider.test.tsx` (asserting `useTheme` outside a provider); the suite still reports all tests passing with exit 0. Prettier is not applicable to these files: the repository `.prettierignore` excludes `apps/**`, and the module's pinned canonical gate is `apps/staff-web/check.sh` (typecheck + ESLint `--max-warnings 0` + build + coverage), which is green.

## Defects found and decisions

- **Decision — label bundle location.** The story says labels come from i18n messages in English and Arabic, but the declared scope is `apps/staff-web/src/app` and the central `src/i18n/messages.ts` has no navigation keys. To stay inside scope I defined a typed en/ar navigation message bundle in `navigation.ts` with an identical key set (`Record<Locale, Record<NavigationLabelKey, string>>`) and a `navigationLabel(locale, key)` resolver. No file under `src/i18n` was edited.
- **Decision — `Role` type.** There is no `Role` type in the repo; `features/admin/domain.ts` exports only the narrower `AdminRole = 'tenant_admin' | 'privacy_auditor' | 'reception'`, and it is outside scope. `navigation.ts` defines `Role` as a superset that reuses those three names verbatim, adds the other screen-map roles, and adds `staff` for the table's "all staff" rows.
- **Decision — "all staff" semantics.** `staff` is a base role held by every signed-in user; `navigationFor(role)` includes `staff` items for any role. Therefore the privacy auditor's exact set is `[home, admin-audit, account-sessions]`: the audit item is its only role-restricted item, and Today/Account are the two "all staff" rows. This is recorded explicitly so the acceptance phrasing "only the audit item and Account" is auditable rather than ambiguous.
- **Decision — no second navigation landmark.** The pre-existing `tests/app/AppShell.test.tsx` calls `getByRole('navigation')` and expects exactly one (the header org switcher). The rail therefore uses `role="group"` with an accessible name plus named `<section>` regions, so the existing test stays green without editing an out-of-scope file. READY-19 will own the final landmark/`<Link>`/active-route rewrite of the shell.
- **Decision — RTL mirroring.** The rail is the first child of a `flexDirection: 'row'` container; under `document.dir = 'rtl'` flexbox places it on the right, and no physical `left`/`right` CSS is used (consistent with `docs/design/rtl-and-localization.md` §1.1). jsdom cannot measure painted position, so the test asserts direction, Arabic labels and the absence of physical insets.
- **Scope boundary — "not available yet" pages.** The story's rail links point at all §2 routes, including screens not implemented yet. The router/catch-all page is owned by READY-19 (`src/app/router.tsx` / `routes.tsx`); this story only supplies the navigation data model and the rail. No route was invented and no screen outside `src/app` was touched.
- No production defect was found. One self-correction during the red/green loop: an RTL test asserted Arabic labels before toggling the locale; it was reordered after the toggle.
- No patient data, credential or real identifier was used; all content is synthetic.

## Evidence sources

- **Tool evidence (executed commands):** all commands and results above, including the red/green targeted runs, the full `apps/staff-web/check.sh` gate, typecheck, lint and the name check.
- **Reviewer evidence:** bounded read-only reviews by `smartclinic-senior-react` and `smartclinic-senior-typescript` — see "Specialist review" below.
- **Simulated user / human participant evidence:** none. This is a navigation data model and shell rendering change with synthetic fixtures; no clinical or patient flow is involved.

## Specialist review

Two bounded, read-only reviews were requested; neither reviewer edited files and neither executed the gates (they have no shell tool). All gate results above are my own tool evidence on the exact final tree.

- **`smartclinic-senior-typescript`** — no blocking findings. Verified strict-mode coherence (`import type` separation under `verbatimModuleSyntax` / `consistent-type-imports`, finite-literal `Record` lookups under `noUncheckedIndexedAccess`, `role?: Role` under `exactOptionalPropertyTypes`), all 24 §2 rows and 37 label keys present, and that every new branch is covered. Static-review evidence only.
- **`smartclinic-senior-react`** — no blocking defect; acceptance criteria traced out correctly. Four low/moderate findings, dispositioned below. Static-review evidence only.

| # | React-review finding | Disposition |
|---|---|---|
| 1 | Rail exposed as `role="group"` instead of a `<nav>` landmark (moderate): screen-reader landmark navigation cannot jump to site sections. | **Accepted deviation, documented.** The suggested fix edits `tests/app/AppShell.test.tsx` (`getByRole('navigation')`), which is outside the declared `src/app` scope. Making the rail a second `<nav>` would make that singular query throw. The rail is a labelled `group` of labelled `region`s, the header keeps the single navigation landmark, and READY-19 owns the final shell/landmark rewrite. Recorded as an a11y follow-up for that story, not silently ignored. |
| 2 | Role vocabulary duplicated across `Role`, `AdminRole` and the generated server union (low). | **Out of scope + documented.** `features/admin/domain.ts` and `contracts/openapi` are outside `apps/staff-web/src/app`; `Role` deliberately reuses the three `AdminRole` string values verbatim as a superset. Follow-up for a shared cross-client role type, not fixed here. |
| 3 | Group headings used `<p>` inside a named `<section>`, so heading-based screen-reader navigation could not find them (low). | **Fixed in this change.** Each group heading is now `<h2 id=…>` and the `<section>` uses `aria-labelledby` to that visible heading, so the accessible name and the visible text come from the same label. The full gate was re-run after the fix. |
| 4 | A second bilingual bundle outside central `src/i18n/messages.ts` (low). | **Documented as the approved in-scope equivalent + strengthened.** `navigationLabels` is typed `Record<Locale, Record<NavigationLabelKey, string>>`, so both bundles are statically forced to carry every key; a new runtime test now also asserts the two key sets are identical. Folding keys into `messages.ts` is out of scope. |

No clinical or human-participant evidence was available or applicable; this is a synthetic navigation model and shell component change.
