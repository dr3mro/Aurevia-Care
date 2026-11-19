# CW-009 — Staff web React audit and access-review screens (synthetic fixtures)

## Requirements and acceptance mapping

- **ADM-02** — operational/audit review: `AuditEventList` renders actor, purpose, scope, correlation ID, action, resource, UTC instant and outcome for every row; `AccessStateNotice` renders permission-denied (role="alert") and concealment (role="status") states from fixture probes; `AdminScreen` composes the tenant-scoped views and a configuration proposal announcement. The requested configuration editor is primarily an ADM-01 display (version/approval/effective date) and is included because the story requires it.
- **OPS-02** — audit and least privilege: `StaffHistoryView` shows append-only invite / role-grant / deactivation events with grantor, actor, effective instant and deactivation reason; deactivation collapses current roles to none. `AuditEventList` computes a synthetic hash-chain verdict with a chain-verified / chain-not-verified badge and an escalation hint for a broken chain. No real credentials or endpoints.
- **UX-01** — English/Arabic bundles with parity checks, RTL document direction, logical CSS properties only, `<bdi dir="auto">` isolation with content-correct `lang`, `Intl` civil-date and UTC-instant formatting.

Synthetic data only: all fixtures use `Synthetic*` names and two organizations from `src/fixtures/synthetic.ts` whose local identifiers intentionally collide (config key, staff subject, audit sequence). No patient data, credentials, endpoint calls or transport imports.

## Scope

`apps/staff-web/src/features/admin/**` only, plus this notes file. No shared trackers, `check.sh`, API, db, contracts, GitHub or scripts were edited. No migration version used. The feature is intentionally not mounted in `AppShell` (matching the CW-004/CW-007 pattern; mounting is outside this scope).

## Files

`domain.ts`, `fixtures.ts`, `messages.ts`, `StateBadge.tsx`, `AccessStateNotice.tsx`, `ConfigEditor.tsx`, `StaffHistoryView.tsx`, `AuditEventList.tsx`, `AdminScreen.tsx`, plus `admin-core.test.ts`, `admin-components.test.tsx`, `admin-screen.test.tsx`.

## Specialist routing and evidence source

- `smartclinic-senior-react`: bounded design proposal (read-only) before implementation; recommended the feature-local pure core / fixtures / messages / presentational components / screen split, a shared badge primitive, and the v8 coverage pitfalls that were followed. No files edited by the specialist.
- `smartclinic-ux-reviewer`: three static review rounds (read-only, no shell available to the reviewer). Round 1 found a reproducible invalid-date `RangeError` crash plus six accessibility/accuracy findings; all were fixed. Round 2 found a field-association WCAG issue plus residual `lang` metadata issues; both were fixed. Round 3 confirmed no remaining findings. These are simulated reviewer findings, not human or clinical feedback.
- All command results below are tool-generated from commands executed by the implementing agent (evidence source: tool). No human participant, real clinician or patient evidence.

## Test-first evidence

- Red before production code: `npx vitest run src/features/admin/admin-core.test.ts` → import resolution failure on missing `./domain` (new behaviour absent). Red before UI code: `npx vitest run src/features/admin/admin-components.test.tsx src/features/admin/admin-screen.test.tsx` → import resolution failures on missing `./ConfigEditor` and `./AdminScreen`.
- Green after implementation: `npx vitest run src/features/admin` → 3 files, 40 tests passed (later 144 after the UX fixes).
- Defects found and fixed: (1) invalid/free-text effective date reached `Intl.DateTimeFormat` and would throw `RangeError` — added `isValidCivilDate` (format + real-calendar round-trip) and a localized, field-associated error; (2) status live region mislabelled as the audit region and unlocalized — localized announcement re-computed at render time with correct label and bidi isolation; (3) concealment wording implied cross-tenant existence — neutral "not found" copy and neutral probe label; (4) deactivation actor mislabelled as grantor, non-unique row headers, UTC not disclosed — separate Actor/Grantor columns, sequence row header, UTC header; (5) empty audit chain shown as "Chain not verified" — neutral "no events to verify" verdict and an escalation hint for genuinely broken chains; (6) mixed-script language metadata incorrect for Latin fixtures — content-correct `lang`; (7) raw technical table caption, cramped badges — localized caption and token gap.

## Final verification commands and results

All run from `apps/staff-web` unless noted.

- `npm run typecheck` → exit 0 (`tsc --noEmit -p tsconfig.json`).
- `npm run lint` → exit 0 (`eslint . --max-warnings 0`).
- `npm run test:coverage` → 26 files, **144 tests passed**, 35 files fully covered; **100% statements (388/388), branches (195/195), functions (140/140), lines (370/370)** with `perFile: true`.
- `bash check.sh` (canonical gate: generated-client drift, typecheck, lint, coverage) → **exit 0**, same 144 tests and 100% metrics.
- `bash docs.sh /tmp/opencode/cw009-docs3` (TypeDoc, warnings are errors) → **exit 0**, `index.html` produced.
- From repo root: `bash scripts/frontend-check.sh` → **exit 0**, including module typecheck/lint/tests, TypeScript-only policy, strict tsconfig flags, no `any`/`@ts-ignore`, root Prettier check and root Vitest.

The stderr stack trace from `tests/theme/ThemeProvider.test.tsx` is the pre-existing fail-closed test (`useTheme` outside a provider); it does not fail any gate.

Prettier note: `apps/staff-web` has no Prettier dependency or script (its canonical formatter surface is ESLint + strict TypeScript), and `.prettierignore` excludes `apps/**`; the root `format:check` therefore does not measure module files. Adding a module formatter is outside this story's scope and was not silently skipped.

## Entry-point-to-test trace

`AdminScreen` (composition) → `ConfigEditor.submit` → `isValidCivilDate` / `proposeVersion` → `versionsFor` / `effectiveVersion` → rendered version table (`admin-screen.test.tsx`, `admin-components.test.tsx`, `admin-core.test.ts`). `AdminScreen` → `StaffHistoryView` → `historyFor` / `isDeactivated` / `currentRoles` → append-only event table (same tests). `AdminScreen` → `AuditEventList` → `adminChainFor` / `verifyChain` → outcome rows and chain badge (same tests). `AdminScreen` → `resolveAccess(probe.viewer, probe.resourceOrganizationId)` → `AccessStateNotice` permitted/denied/concealed notices (same tests). Tenant switching resets local proposals via `useEffect`, covered by rerender in `admin-screen.test.tsx`.

## Delivery

Committed to the assigned branch only; no push, tag, `gh` call or tracker edit.
