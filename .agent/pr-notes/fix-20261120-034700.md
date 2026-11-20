# CW-009 — fix attempt `fix-20261120-034700` (Fix PR gate failed, attempt 2 of 3)

Worker branch: `swarm/CW-009-122` · HEAD at start: `61d572f Agent changes for CW-009` · working tree clean at start.

## Scope and rules honoured

Changed path: this notes file only. **No production code was changed in this attempt** — the in-scope feature was already implemented and hardened across earlier CW-009 attempts and is green (see evidence). I did NOT touch `apps/api`, `db/`, `contracts/`, `apps/staff-web/check.sh`, `.github/`, `scripts/` or any shared tracker. No push, tag or `gh` call. No migration version used. Synthetic fixtures only.

## Requirements and acceptance mapping (unchanged, verified present)

- **ADM-02** — `AuditEventList` renders actor, purpose, scope, correlation ID, action, resource, UTC instant and outcome per row, with a chain-verified / not-verified / no-events badge. `AdminScreen` composes the tenant-scoped views; `AccessStateNotice` renders permitted / denied (`role="alert"`) / concealed states from fixture probes.
- **OPS-02** — `StaffHistoryView` shows append-only invite / role-grant / deactivation events with actor, grantor, effective UTC instant and deactivation reason; deactivation collapses current roles to none. `AuditEventList` computes a deterministic synthetic FNV-1a chain verdict with an escalation hint on a broken chain (beta tenant fixture is deliberately tampered).
- **UX-01** — English/Arabic bundles with key parity, document `dir`/`lang` via the shared providers, logical CSS properties only, `<bdi dir="auto">` isolation via `MixedScriptText`, `Intl` civil-date/instant formatting via shared CW-004 helpers, and labelled keyboard-scrollable table regions (`AdminTableRegion`, WCAG 1.4.10).
- **Tenant isolation (IAM-01/ARC-01 at the UI boundary)** — two synthetic tenants share local identifiers (config key `facility.MAIN.booking`, staff subject `synthetic.reception`, colliding audit sequences); every selection folds on `organizationId`; `ConfigEditor` is keyed by tenant so an unsubmitted draft cannot cross an active-organization switch.

Entry-point-to-test trace: `AdminScreen` → `ConfigEditor.submit` → `isValidCivilDate` / `proposeVersion` → `versionsFor` / `effectiveVersion`; `AdminScreen` → `StaffHistoryView` → `historyFor` / `isDeactivated` / `currentRoles`; `AdminScreen` → `AuditEventList` → `adminChainFor` / `verifyChain`; `AdminScreen` → `resolveAccess` → `AccessStateNotice`. Driven by `admin-screen.test.tsx`, `admin-components.test.tsx`, `admin-core.test.ts`.

## Exact commands run and results (evidence source: executed tool commands by the implementing agent)

From `apps/staff-web` (pinned deps installed first with `npm ci --no-audit --no-fund` → exit 0, 312 packages):

- `bash check.sh` → **exit 0**. Canonical gate in order: `gen:api:check` → generated client matches the contract; `typecheck` (`tsc --noEmit`) → clean; `lint` (`eslint . --max-warnings 0`) → clean; `test:coverage` → **29 test files, 194 tests passed**, **45 files fully covered**, **100% statements (556/556), branches (286/286), functions (215/215), lines (527/527)**.
- The stderr stack traces from `tests/i18n/I18nProvider.test.tsx` and `tests/theme/ThemeProvider.test.tsx` are the pre-existing fail-closed "hook outside provider" tests. They are intentionally expected exceptions and do not fail any gate (Vitest reports all 194 passing; `check.sh` exit 0). This matches the note in the prior CW-009 record.

The integrator runs the database and integration tests; none were run here by design.

## Defects found in this attempt

**None in scope.** The CW-009 staff-web admin feature's own gate is green.

## Missing evidence / decision — why I stopped

The "Requested change" reports a failed merge gate with **62 failures and 7 errors, all in `apps/api` integration tests**. Every named failing suite is in a different domain from this story:

- `IdentityProofingIsolationTest` (IAM-05 patient identity proofing) — `S-G0-07`
- `RxTransmissionFlowTest` (RX-02 prescription transmission) — `S-G4-04`
- `CheckInWorklistIntegrationTest` (CLN-02 check-in worklist) — `S-G1-02`
- `ChartRecordIntegrationTest`, `ChartEntryIntegrationTest` (CLN-03 chart) — `S-G1-03`
- `ExchangeTaskIsolationTest` (XCH-02 exchange tasks) — `S-G2-02`
- `ConversationFlowTest` / patient-unit messaging — `S-G3-*` messaging stories

These are Java/Spring integration tests against real PostgreSQL. They cannot be caused by a change confined to `apps/staff-web/src/features/admin` (React/TypeScript), and this story's scope and rules explicitly forbid editing `apps/api`, `db/`, shared trackers, or running the integrator's database/integration gate. There is therefore **no evidence connecting the reported failures to CW-009**, and I have **no authorized path to repair them** from this worker. Per the stop rule, I am recording this and stopping rather than making unrelated or out-of-scope changes.

Required next step (owner/integrator): route the reported API failures to their owning stories above. They appear to be in-progress parallel-worker API branches merged into the gate's `main` snapshot, not a CW-009 defect.

## Specialist routing and evidence source

Prior CW-009 attempts recorded three read-only specialist reviews (`smartclinic-senior-react`, `smartclinic-test-engineer`, `smartclinic-ux-reviewer`) whose findings (draft leak across tenants, live-region static label, keyboard-scrollable table regions, actor/grantor test gaps) were fixed and documented in `.agent/pr-notes/CW-009.md`. Those are simulated reviews, not executed or human evidence. In this attempt I loaded `smartclinic-delivery` and `smartclinic-senior-react` skills and re-verified the feature myself with executed commands. **No human, real clinician or patient evidence exists for this story.**

## Limitations / unverified behaviour (carried from prior record, unchanged)

- Accessibility verified through role/name assertions and logical-CSS source scanning in jsdom, not a real WCAG 2.2 AA audit or screen reader. A named AT pass and Arabic-speaking clinical review remain launch gates.
- Arabic copy is synthetic, not a clinically approved translation. The chain badge is a deterministic FNV-1a demonstrator, not a production integrity control.
- Deferred (out-of-scope or latent, recorded for owning stories): shared `FormField` form-level error summary/focus move; malformed-ISO formatter guard; `numberingSystem: 'latn'` in shared `i18n/format.ts`; `dir="ltr"` mono identifier runs; `effectiveVersion` ranks by version rather than effective date (fixtures keep both aligned).
