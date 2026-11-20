# CW-009 — Staff web React audit and access-review screens (synthetic fixtures)

## Requirements and acceptance mapping

- **ADM-02** — `AuditEventList` renders actor, purpose, scope, correlation ID, action, resource, UTC instant and outcome for every row, plus a chain-verified / not-verified / no-events badge. `AdminScreen` composes the tenant-scoped views; `AccessStateNotice` renders permission-denied (`role="alert"`) and permitted/concealed (`role="status"`) states from fixture probes.
- **OPS-02** — `StaffHistoryView` shows append-only invite / role-grant / deactivation events with actor, grantor, effective UTC instant and deactivation reason; deactivation collapses current roles to none. `AuditEventList` computes a deterministic synthetic FNV-1a hash-chain verdict with an escalation hint on a broken chain. No real credentials, endpoints or patient data.
- **UX-01** — English/Arabic bundles with compile-time + runtime key parity, document `dir`/`lang`, logical CSS properties only, `<bdi dir="auto">` isolation with content-correct `lang`, `Intl` civil-date and UTC-instant formatting via the shared CW-004 helpers, and labelled keyboard-scrollable table regions per the project's WCAG 1.4.10 pattern.
- **Tenant isolation (IAM-01/ARC-01 at the UI boundary)** — two synthetic tenants intentionally share local identifiers (config key `facility.MAIN.booking`, staff subject `synthetic.reception`, audit sequences); every selection folds on `organizationId`, cross-tenant resources are concealed, and no local draft state or announcement crosses an active-organization switch.

## Scope

`apps/staff-web/src/features/admin/**` only, plus this notes file. No shared tracker, `check.sh`, API, db, contracts, workflow or script was edited. No migration version used. The feature is intentionally not mounted in `AppShell` (matching the CW-004 / CW-007 / CW-008 pattern; mounting is outside this scope).

## Files

Production: `domain.ts`, `fixtures.ts`, `messages.ts`, `StateBadge.tsx`, `AccessStateNotice.tsx`, `ConfigEditor.tsx`, `StaffHistoryView.tsx`, `AuditEventList.tsx`, `AdminScreen.tsx`, and new `AdminTableRegion.tsx`.
Tests: `admin-core.test.ts`, `admin-components.test.tsx`, `admin-screen.test.tsx`.

## Changes made in this attempt (all test-first)

- **CW-009-02 — cross-tenant unsubmitted draft leak (defect, HIGH).** `ConfigEditor` holds `value`/`effectiveFrom`/`invalidField` in local state; `AdminScreen` reconciled it by type with no `key`, so text typed under tenant A survived a switch to tenant B and could be proposed into a B-scoped command. The prior attempt's "no stale announcement" fix (CW-009-01, kept) reset only `AdminScreen`'s own state, not the child's. **Red first:** `admin-screen.test.tsx > does not carry an unsubmitted configuration draft across an active-organization switch` asserted `''` but received `'Synthetic alpha unsubmitted draft'` (`npx vitest run src/features/admin/admin-screen.test.tsx` → 1 failed / 5 passed). **Fix:** `key={organizationId}` on `<ConfigEditor>` so the editor instance (and its draft) is discarded on a tenant switch. **Green:** 6/6 in that file, 194/194 in the module. Two independent reviewers reported this same defect (convergent evidence).
- **CW-009-03 — live-region `aria-label` masked the announcement (a11y, MEDIUM-HIGH).** `AdminScreen`'s proposal status and `AccessStateNotice` gave a static `aria-label` to `role="status"`/`role="alert"` regions; AT could announce the static name instead of the visible dynamic result, contrary to `accessibility-patterns.md §7` ("injected text MUST be the same text visible"). Removed both `aria-label`s and the now-unused `proposalStatus` message key from both bundles; kept the visible result text and the probe label. Added a regression assertion that the status region has no `aria-label`.
- **CW-009-04 — wide tables were not keyboard-scrollable reflow regions (a11y, MEDIUM).** The 5/8/9-column tables could not be scrolled with the keyboard at 320 px / 400% zoom, violating WCAG 1.4.10 and `accessibility-patterns.md §4/§111`. Added `AdminTableRegion` (one labelled `role="region"` with `tabIndex={0}` and `overflowX: 'auto'`) and wrapped all three tables; added a test that finds the focusable region for each table and asserts it contains the table.
- **Test adequacy (reviewer findings).** Added ADM-02 assertions that the audit actor column is actually rendered (`Synthetic alpha privacy auditor`, `Synthetic alpha reception`), and OPS-02 assertions that the grantor appears only on the role-grant row, the invited row shows "Not applicable", and the role-grant `<time datetime="2026-01-06T08:00:00Z">` is present. These lock down behavior that previously passed on fixture data alone.

## Specialist routing and evidence source

Three read-only specialists were dispatched per AGENTS.md with only story IDs, scope, acceptance criteria and evidence; each returned a bounded report. **All three had no shell, so their findings are source inspection / simulated review, not executed evidence.** No human, real clinician or patient evidence exists for this story.

- `smartclinic-senior-react` — found CW-009-02 (convergent), CW-009-04 and a shared-scaffold form-error finding (deferred, out of scope), plus a latent `effectiveVersion` ranking note (deferred).
- `smartclinic-test-engineer` — found CW-009-02 (HIGH, convergent) and the actor/grantor/effective-date test gaps (fixed); noted invalid-instant guard and RTL source-level assertions as latent.
- `smartclinic-ux-reviewer` — found CW-009-03 (F1) and CW-009-04 (F2) as the two blocking UX issues (both fixed); form error summary (inherited), hardcoded `lang="en"` on synthetic Latin fixtures (latent), and `numberingSystem` (documented pre-existing deviation) were reported for the record.
- `smartclinic-delivery`, `smartclinic-senior-typescript` and `smartclinic-test-engineer` skills were loaded; the prior attempt's notes record earlier UX review rounds. Deferred/out-of-scope findings are listed under Limitations.

## Final verification commands and results (this attempt)

All run from `apps/staff-web` unless noted; evidence source = **executed tool commands** by the implementing agent.

- `npm ci --no-audit --no-fund` → exit 0 (312 packages, pinned toolchain).
- Red step: `npx vitest run src/features/admin/admin-screen.test.tsx` → 1 failed / 5 passed (draft leak reproduced); green after fix → 6 passed.
- `bash check.sh` (canonical gate: `gen:api:check`, `typecheck`, `lint`, `test:coverage`) → **exit 0**; **29 test files, 194 tests passed, 45 files fully covered, 100% statements (556/556), branches (286/286), functions (215/215), lines (527/527)** with `perFile: true`. `gen:api:check` and ESLint (`--max-warnings 0`) both passed.
- `npm run build` → exit 0 (`tsc --noEmit` + `vite build`, 31 modules).
- `bash docs.sh /tmp/opencode/cw009-docs-verify` → exit 0; TypeDoc (`treatWarningsAsErrors`) produced `index.html`.
- From repo root: `bash scripts/frontend-check.sh` → exit 0 (module typecheck/lint/tests, TypeScript-only policy, strict tsconfig, no `any`/`@ts-ignore`, root Prettier, root Vitest).
- Scope/quality scans: `git status --short` shows only the nine files under `apps/staff-web/src/features/admin` plus this notes file; no `any`/`@ts-ignore`, story/task-ID naming, endpoint or PHI-shaped string in the feature; RTL source test found no physical CSS properties.

The stderr stack trace from `tests/theme/ThemeProvider.test.tsx` is the pre-existing fail-closed test (`useTheme` outside a provider); it does not fail any gate.

Prettier note: `apps/staff-web` has no Prettier dependency or script; its canonical formatter surface is ESLint + strict TypeScript, and `.prettierignore` excludes `apps/**`. The original admin files were already not Prettier-formatted, so no module-wide formatting churn was introduced; only lines structurally affected by the table wrappers were re-indented.

## Entry-point-to-test trace

`AdminScreen` (composition, synchronous tenant reset, `key`-based editor remount) → `ConfigEditor.submit` → `isValidCivilDate` / `proposeVersion` → `versionsFor` / `effectiveVersion` → labelled table (`admin-screen.test.tsx`, `admin-components.test.tsx`, `admin-core.test.ts`). `AdminScreen` → `StaffHistoryView` → `historyFor` / `isDeactivated` / `currentRoles` → append-only event table (same tests). `AdminScreen` → `AuditEventList` → `adminChainFor` / `verifyChain` → outcome rows and chain badge (same tests). `AdminScreen` → `resolveAccess(probe.viewer, probe.resourceOrganizationId)` → `AccessStateNotice` permitted/denied/concealed notices (same tests). New regression tests drive (a) an unsubmitted draft through an active-organization switch, (b) the three labelled keyboard-scroll regions, (c) the no-static-label live region, and (d) rendered actor/grantor/effective-instant cells.

## Limitations / unverified behaviour (deferred findings recorded)

- The integrator runs the database and integration tests; none were run here by design.
- Accessibility was verified through role/name assertions, a commit-phase DOM probe and logical-CSS source scanning in jsdom, not a real WCAG 2.2 AA audit or screen reader. A named AT pass and Arabic-speaking clinical review remain launch gates.
- Arabic copy is synthetically translated for the fixture; it is not a clinically approved translation. Shared `i18n/format.ts` does not force `numberingSystem: 'latn'` (documented pre-existing deviation, shared file out of scope).
- The chain badge uses a deterministic FNV-1a hash for synthetic fixtures only; it is demonstrative, not a production integrity control.
- Deferred from reviewer findings (not fixed here): the shared `FormField` inline `role="alert"` lacks the mandated form-level error summary/focus move (root lives in shared scaffold, outside this scope); event-instant formatters would throw on a malformed ISO string (current synthetic fixtures are valid); identifier runs use `<bdi dir="auto">` rather than the design contract's `dir="ltr"` mono; and `effectiveVersion` ranks by version rather than effective date (fixtures keep both orders aligned, so no observable divergence). These are recorded for the owning stories, not silently ignored.

## Delivery

Committed to the assigned branch only; no push, tag, `gh` call or shared-tracker edit.
