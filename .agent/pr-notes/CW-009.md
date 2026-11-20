# CW-009 — Staff web React audit and access-review screens (synthetic fixtures)

## Requirements and acceptance mapping

- **ADM-02** — audit review: `AuditEventList` renders actor, purpose, scope, correlation ID, action, resource, UTC instant and outcome for every row; `AccessStateNotice` renders permission-denied (`role="alert"`) and concealment/permitted (`role="status"`) states from fixture probes; `AdminScreen` composes the tenant-scoped views and a configuration-proposal announcement.
- **OPS-02** — audit and least privilege: `StaffHistoryView` shows append-only invite / role-grant / deactivation events with actor, grantor, effective instant and deactivation reason; deactivation collapses current roles to none. `AuditEventList` computes a synthetic FNV-1a hash-chain verdict with a chain-verified / chain-not-verified / no-events badge and an escalation hint for a broken chain. No real credentials, endpoints or patient data.
- **UX-01** — English/Arabic bundles with machine-checked key parity, document `dir`/`lang` direction, logical CSS properties only, `<bdi dir="auto">` isolation with content-correct `lang`, and `Intl` civil-date and UTC-instant formatting via the shared CW-004 helpers.
- Tenant isolation (IAM-01/ARC-01 evidence at the UI boundary): two synthetic tenants intentionally share local identifiers (config key `facility.MAIN.booking`, staff subject `synthetic.reception`, audit sequences); every selection folds on `organizationId` and cross-tenant resources are concealed.

## Scope

`apps/staff-web/src/features/admin/**` only, plus this notes file. No shared tracker, `check.sh`, API, db, contracts, workflow or script was edited. No migration version used. The feature is intentionally not mounted in `AppShell` (matching the CW-004/CW-007 pattern; mounting is outside this scope).

## Files

`domain.ts`, `fixtures.ts`, `messages.ts`, `StateBadge.tsx`, `AccessStateNotice.tsx`, `ConfigEditor.tsx`, `StaffHistoryView.tsx`, `AuditEventList.tsx`, `AdminScreen.tsx`, plus `admin-core.test.ts`, `admin-components.test.tsx`, `admin-screen.test.tsx`.

## Specialist routing and evidence source

- `smartclinic-senior-react`: bounded read-only review of the finished feature against ADM-02/OPS-02/UX-01. Found one genuine low-severity defect (stale cross-tenant announcement across an active-organization switch); fixed here. Found no other material gap. **The reviewer had no shell, so its findings are source inspection only, not executed evidence** (simulated review).
- `smartclinic-ux-reviewer` and `smartclinic-senior-typescript` skills were loaded; the prior attempt recorded three UX review rounds (invalid-date crash and accessibility/accuracy fixes). Those are prior simulated-review findings, not human or clinical feedback.
- All command results below are **tool-generated** from commands executed by the implementing agent in this attempt (evidence source: tool). No human participant, real clinician or patient evidence exists for this story.

## Defect found and fixed in this attempt (CW-009-01)

- **Stale cross-tenant announcement on organization switch (UI tenant-isolation seam).** `AdminScreen` cleared `proposals`/`proposal` in a `useEffect` keyed on `organizationId`. Passive effects run after paint, so the first committed frame under a new tenant still rendered the previous tenant's `{ facilityCode, version }` status announcement, contradicting the component's "never carried into another tenant" invariant. The existing tenant-switch test passed only because RTL flushes effects inside `act()`.
- **Regression test (red first):** `admin-screen.test.tsx > never paints the previous tenant proposal after an active-organization switch` uses a `useLayoutEffect` probe that reads the status region during the commit phase, before passive effects. Against the old code it captured `Proposed version 5 for MAIN` under tenant beta (assertion failed). `npx vitest run src/features/admin/admin-screen.test.tsx` → 1 failed / 4 passed.
- **Fix:** moved the reset to render time using React's "adjust state when props change" pattern — a `proposalOrganizationId` state and a synchronous guard that clears `proposals`/`proposal` when `organizationId` differs. React discards and immediately re-runs that render, so no stale frame is committed. `useEffect` import removed.
- **Green after fix:** `npx vitest run src/features/admin` → 3 files, 42 tests passed (was 41).

## Final verification commands and results (this attempt)

All run from `apps/staff-web` unless noted; evidence source = executed tool commands.

- `npm ci --no-audit --no-fund` → exit 0 (312 packages, pinned toolchain).
- `npx vitest run src/features/admin` → 3 files, 42 tests passed.
- `bash check.sh` (canonical gate: `gen:api:check`, `typecheck`, `lint`, `test:coverage`) → **exit 0**; **29 test files, 189 tests passed, 44 files fully covered, 100% statements (555/555), branches (288/288), functions (214/214), lines (526/526)** with `perFile: true`.
- `npm run build` → exit 0 (`tsc --noEmit` + `vite build`, 31 modules).
- `bash docs.sh /tmp/opencode/cw009-docs-final` → exit 0; TypeDoc (`treatWarningsAsErrors`) produced `index.html`.
- From repo root: `bash scripts/frontend-check.sh` → exit 0 (module typecheck/lint/tests, TypeScript-only policy, strict tsconfig, no `any`/`@ts-ignore`, root Prettier, root Vitest).
- Quality scan: no `any`/`@ts-ignore`, no story/task-ID naming, no endpoint or PHI-shaped strings in the feature.

The stderr stack trace from `tests/theme/ThemeProvider.test.tsx` is the pre-existing fail-closed test (`useTheme` outside a provider); it does not fail any gate.

Prettier note: `apps/staff-web` has no Prettier dependency or script (its canonical formatter surface is ESLint + strict TypeScript), and `.prettierignore` excludes `apps/**`, so the root `format:check` does not measure module files. Adding a module formatter is outside this story's scope and was not silently skipped.

## Entry-point-to-test trace

`AdminScreen` (composition + synchronous tenant reset) → `ConfigEditor.submit` → `isValidCivilDate` / `proposeVersion` → `versionsFor` / `effectiveVersion` → rendered version table (`admin-screen.test.tsx`, `admin-components.test.tsx`, `admin-core.test.ts`). `AdminScreen` → `StaffHistoryView` → `historyFor` / `isDeactivated` / `currentRoles` → append-only event table (same tests). `AdminScreen` → `AuditEventList` → `adminChainFor` / `verifyChain` → outcome rows and chain badge (same tests). `AdminScreen` → `resolveAccess(probe.viewer, probe.resourceOrganizationId)` → `AccessStateNotice` permitted/denied/concealed notices (same tests). The new regression test drives `AdminScreen` through an active-organization switch and asserts via a commit-phase probe that no prior-tenant announcement is painted.

## Limitations / unverified behaviour

- The integrator runs the database and integration tests; none were run here by design.
- Accessibility was verified through role/name assertions, a commit-phase DOM probe and logical-CSS source scanning in jsdom, not a real WCAG 2.2 AA audit or screen reader.
- Arabic copy is synthetically translated for the fixture; it is not a clinically approved translation. The shared `i18n/format.ts` does not force `numberingSystem: 'latn'` (a documented pre-existing deviation outside this scope).
- The chain badge uses a deterministic FNV-1a hash for synthetic fixtures only; it is demonstrative, not a production integrity control.

## Delivery

Committed to the assigned branch only; no push, tag, `gh` call or tracker edit.
