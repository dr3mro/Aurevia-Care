# CW-008 — Staff web React chart and document screens (synthetic fixtures)

## Scope and requirements

- Scope: `apps/staff-web/src/features/chart` only. No tracker, API, database, contract, workflow, script, shared config or shared component file was edited.
- Requirements: **CLN-03, CLN-05, CLN-07, UX-01** (plus tenant-isolation evidence for IAM-01/ARC-01 at the UI boundary).
- Depends on CW-004 scaffold; reuses its `ThemeProvider`, `I18nProvider`, `MixedScriptText`, `Button`, `i18n/format` helpers and `syntheticOrganizations` fixtures. No real endpoint, credential or patient data is used.
- Continuation note: this branch already carried a complete chart implementation from an earlier attempt. This attempt independently re-read every source and test file, re-ran all gates, hardened one tenant-key inconsistency, added one CLN-05 acceptance test, and refreshed these notes with observed results. No claim is made that this attempt produced the original red→green implementation.

## Acceptance mapping

| Requirement | Acceptance behaviour | Implementation / evidence |
|---|---|---|
| CLN-03 | Longitudinal view of problems, allergies, medications and vitals with units, source, effective date, verification state, original code/text, and separate clinical author vs data-entry actor. Explicit `none` and `unknown` states are first-class values, not missing fields. | `LongitudinalChart.tsx`, `domain.ts` (`CodedEntry`, `Vital`, `ChartVerification`), `fixtures.ts`; `components.test.tsx` asserts captions, units (`mmHg`, `°C`, `beats/min`), author/entered-by headers and none/unknown/verified labels; `chart-core.test.ts` asserts fixture states. |
| CLN-05 | SOAP draft → signed; required SOAP fields, signer, attestation, patient/encounter context and optimistic version precondition enforced in the domain; content checksum and template version stamped on signing; signed revisions read-only in the UI; separate amendment draft linked to the source and superseding it; entered-in-error preserves prior revision and records actor, reason, recipients and time. | `domain.ts` (`signDraft` guards, `updateDraft`, `createAmendment`, `markSuperseded`, `markEnteredInError`, `syntheticChecksum`, `currentRevision`), `NoteEditor.tsx` (`readOnly` textareas with no change handler for signed revisions, amendment action only when `canAmend`, `role="alert"` banner), `RevisionHistory.tsx`; `chart-core.test.ts` covers every guard; `chart-screen.test.tsx` covers sign/amend/entered-in-error flows and history preservation. |
| CLN-07 | Document list shows PDF/image associations with version, language, checksum, link target and rotation; void records retain reason and disable the void action; rotation changes only presentation state, never the checksum. | `domain.ts` (`visibleDocuments`, `voidDocument`, `rotateDocument`, `replaceDocument`), `DocumentList.tsx`; tests assert en/ar badges, void reason, disabled void button, rotation normalization and checksum immutability. `visibleDocuments` is the tenant-safe projection for a future read-only view; the screen intentionally renders voided rows so the audit state is visible. |
| UX-01 | English/Arabic bundles with key parity, document direction from the shared `I18nProvider`, `<bdi dir="auto">` mixed-script isolation, logical CSS only, local-script names, UTC instants with IANA zones and unit-preserving number formatting. | `messages.ts` + parity test in `chart-core.test.ts`; `chart-core.test.ts` RTL source scan rejects physical margin/padding/border/text-align properties; Arabic RTL rerender assertions in all three test files; `MixedScriptText` and `formatInstant`/`formatMeasurement` reuse. |

## Entry point → test trace

`ChartScreen` (active-tenant chart selection) → `chartsForTenant` → `LongitudinalChart` / `NoteEditor` / `DocumentList` → guarded domain commands (`signDraft`, `createAmendment` + `markSuperseded`, `markEnteredInError`, `voidDocument`, `rotateDocument`, `replaceRevision`/`replaceDocument`) → `chart-status` live region or `role="alert"` banner. The happy path and the denial/lock paths are asserted from that entry point in `chart-screen.test.tsx`; guard-level failures are asserted directly in `chart-core.test.ts`.

## Changes made in this attempt

1. `ChartScreen.tsx` — `handleAmend` now supersedes the source revision through `replaceRevision(items, organizationId, current.id, ...)`, the same tenant-composite key used by every other mutation, instead of matching on local id alone. Local ids are shared across tenants, so id-only mutation violates the ARC-01/IAM-01 composite-key discipline even though the current state happens to be tenant-scoped. This is a defensive correctness fix, behaviour-preserving under valid state.
2. `chart-screen.test.tsx` — added `preserves the signed source revision in history after amendment`, asserting that after sign → amend the history still lists the two original revisions by id, the amendment notice names the exact superseded revision, and the new amendment draft is editable (CLN-05).
3. This notes file — refreshed with observed commands, results and evidence provenance.

## Resumed-session fixes (red → green, this worker)

A fresh bounded Senior React/Vite review (`ses_e095b2ca0ffebbIh3uaBlS06VD`) found two medium issues. Both were fixed test-first; exact commands and results below.

1. **Cross-tenant render frame (IAM-01/ARC-02).** `ChartScreen` derived `chart` synchronously from `organizationId` but reset `revisions`/`documents` in a `useEffect` (after paint), so an organization switch painted one frame with the new tenant's chart and the old tenant's note state. Fix: all mutable screen state is now one `TenantChartState` object keyed by `organizationId`, reset **during render** before any JSX/child is produced (React's "adjust state when props change" pattern); the `useEffect` was removed. `ChartScreen.tsx` diff and a new `chart-screen.test.tsx` test that spies on every `NoteEditor` render via `vi.mock`/`vi.hoisted` and asserts every rendered revision's tenant equals the active organization.
   - **RED:** `npx vitest run src/features/chart/chart-screen.test.tsx` → 2 failed / 10 passed, `RED_EXIT=1`; the tenant test observed `[alphaId, alphaId]` on the switch path (stale pass), and the sign test found no `role="alert"`.
   - **GREEN:** after the fix, `npx vitest run src/features/chart` → 3 files, 47 tests, `GREEN_EXIT=0`.
2. **Unhandled sign failure (CLN-05).** `signDraft` throws `IncompleteDraft` when any SOAP field is blank; the UI enabled Sign on attestation alone and had no `try/catch`, so clearing fields + signing threw an uncaught exception with no announcement. Fix: `handleSign` now catches rejection at the UI boundary, keeps the draft intact, and renders a translated `role="alert"` (`signError`, en/ar key added to `messages.ts`). Domain guards remain authoritative and are still tested directly.
3. **Row headers (low).** The first cell of each keyed row in `LongitudinalChart` and `DocumentList` is now `<th scope="row">` per `docs/design/accessibility-patterns.md` §4.

Final full gate after the fixes: `bash check.sh` exit 0 (192 tests, 100% coverage), `bash scripts/frontend-check.sh` exit 0, root Prettier exit 0, staff-web TypeDoc hook exit 0. The first lint attempt failed on `import()` type syntax in the new spy (`@typescript-eslint/consistent-type-imports`); fixed with a type-only namespace import (`import type * as NoteEditorModule`). No shared file, tracker, check script or generated config was edited. Commit is scoped to the six files in `git status`.

## Decisions

1. Feature-local bilingual dictionary (mirrors CW-007's scheduling module) because the shared i18n bundle is outside this story's scope; parity is machine-checked for both locales.
2. `enteredInError`, `void` and `supersededBy` are explicit optional fields with named evidence objects, so an explicit none/unknown/void state is never confused with an unrecorded one.
3. `syntheticChecksum` is a deterministic stand-in for a server digest and is documented as such — not a clinical integrity control.
4. Amending a signed revision appends a new draft and marks the source `supersededBy`, so `canAmend` becomes false for an already-amended revision.
5. Voided documents remain in the list with an explanatory status and a disabled void action, preserving the audit trail while making the live/void distinction explicit.
6. No PDF/image bytes are generated or fetched: this is a synthetic screen-only slice. Real rendering, font embedding, page numbering and print profiles remain server-side work (CLN-07), out of scope here.
7. Accessible row-action naming on the document table was considered and deliberately not changed: the shared `Button` component accepts no `aria-label`, and modifying it or adding raw-CSS visually-hidden text would breach this story's scope and the token contract. The row's identity is instead carried by its `<caption>`, `<th scope="col">` headers and cell content; a real WCAG audit remains a launch-gate activity.

## Commands run and results (evidence source: executed tool commands)

### Resumed-session verification (this worker)

At session start the branch `swarm/CW-008-122` was clean and its HEAD equalled `main` (`5d959f7`); the chart implementation from the earlier attempts was already committed. I re-read all 12 chart files and the shared scaffold they import, then re-ran every applicable gate myself; all were green on the untouched tree. The bounded specialist review below then found two medium issues (plus two low), so this session made the test-first production fixes described above and re-ran every gate to green again.

From `apps/staff-web` (node v22.23.5, npm 10.9.9; `npm ci` performed by `check.sh`):

- `bash check.sh` → **exit 0**. Gates in order: `gen:api:check`, `typecheck`, `lint` (`eslint . --max-warnings 0`), `test:coverage`. Final observed after the resumed-session fixes: **29 test files, 192 tests passed; statements 100% (550/550), branches 100% (286/286), functions 100% (214/214), lines 100% (520/520)**, "No files with missing coverage. 44 files fully covered." (The first re-run before the fixes had 190 tests / 555 / 286 / 214 / 526; counts moved because the state refactor consolidated handlers and two tests were added. These are the numbers actually observed here.)
- `npx vitest run src/features/chart` → first attempt hit a transient `Cannot find module .../picomatch/index.js` startup error while a concurrent root `npm ci` was reinstalling dependencies; after that finished, a sequential re-run was **exit 0**, 3 files, **45 tests passed**. After the resumed-session fixes the targeted suite is **exit 0**, 3 files, **47 tests passed**. The module file was present on disk on inspection — this was an install race, not a source defect.
- `bash docs.sh /tmp/cw008-docs-verify` (the staff-web TypeDoc hook) → **exit 0**; `index.html` generated.

From the repository root:

- `bash scripts/frontend-check.sh` → **exit 0**: module typecheck/lint/tests, TypeScript-only and no-`any`/no-class-component source scan, tsconfig strict flags, root Prettier and root Vitest all passed ("frontend standards: policy and tool commands passed"). The run also exercised the patient-mobile Jest suite (26 suites / 280 tests passed) as part of the root gate.
- `node_modules/.bin/prettier --check 'apps/staff-web/src/features/chart/**/*.{ts,tsx}'` → **exit 0**, "All matched files use Prettier code style!".
- `bash scripts/docs.sh` → **exit 1**, "Install Doxygen to build the developer reference." Doxygen is not installed in this worker image (environment limitation, not a source failure); the module's own TypeDoc hook above passed. No docs source was changed by this story.

The jsdom console stack traces for "useI18n/useTheme must be used inside Provider" come from provider-missing negative-control tests in the shared scaffold (`tests/i18n/I18nProvider.test.tsx`, `tests/theme/ThemeProvider.test.tsx`); they do not fail any gate (all tests and the coverage gate passed).

## Defects found

- No production defect remains open. In this attempt only one tenant-key inconsistency was found and hardened (id-only supersede in `handleAmend`); all gates were green before and after the change.
- The inherited notes record that the earlier attempt corrected three test-assertion defects and two TypeScript narrowing errors before reaching green. Those were not re-observed here.
- No clinical, human-participant or simulated-user evidence was available or claimed in this attempt. The earlier attempt's two bounded reviews (Senior React/Vite and Test Engineer) were read-only specialist design reviews; their findings on RTL source scanning, explicit none/unknown states, draft-version/checksum provenance, clinical-vs-entry actors and amendment supersession are reflected in the code. Evidence provenance: tool commands for all gate results; simulated specialist review (earlier attempt, static, did not re-execute checks) for those findings. No real clinical acceptance.
- Final bounded review of the earlier attempt's two-file diff by the Senior React/Vite specialist (session `ses_e09d59b6cffee9igasnBQNylWe`): **no findings**. It source-traced `domain.ts`, `NoteEditor.tsx`, `RevisionHistory.tsx`, `fixtures.ts` and `messages.ts` and confirmed the composite-key supersede and the new history test. Read-only simulated review; the reviewer did not execute checks.
- A fresh bounded Senior React/Vite review of the full final feature (session `ses_e095b2ca0ffebbIh3uaBlS06VD`, this session) found: (1) MEDIUM — a cross-tenant render frame on organization switch (`useEffect` resets `revisions`/`documents` after paint while `chart` updates synchronously); (2) MEDIUM — signing an incomplete draft throws an unhandled `IncompleteDraft` with no announcement; (3) LOW — identifiers/original text are not bidi-isolated; (4) LOW — no `<th scope="row">` row headers. Findings (1), (2) and (4) were fixed test-first in this session (see "Resumed-session fixes" below). Finding (3) needs a change to the shared `MixedScriptText` and a stored language tag on clinical text, both outside this story's scope, so it is recorded as a limitation. Evidence provenance: simulated specialist source review for findings; executed tool commands for all gate results; no human or clinical evidence.

## Limitations / unverified behaviour

- The integrator runs the database and integration tests; this frontend story has none, and none were run here by design.
- Accessibility was verified through role/name assertions and logical-CSS source scanning in jsdom, not through an actual WCAG 2.2 AA audit, keyboard pass or screen reader.
- Arabic copy is synthetically translated for the fixture; it is not a clinically approved translation.
- No image/PDF bytes are rendered or scanned in this slice; document associations are synthetic records only.
- Open review finding (LOW, out of scope): codes, original text, source, checksums and raw timestamps are rendered as bare text rather than through the bidi-isolation element. Correctly fixing this needs a change to the shared `MixedScriptText` (accept an explicit `lang`/direction for Latin identifiers) and a stored language tag on clinical free text; both are outside `apps/staff-web/src/features/chart`. The two MEDIUM findings and the row-header finding from the same review were fixed above. A real WCAG/RTL pass remains a G5 launch-gate activity.
