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

## Decisions

1. Feature-local bilingual dictionary (mirrors CW-007's scheduling module) because the shared i18n bundle is outside this story's scope; parity is machine-checked for both locales.
2. `enteredInError`, `void` and `supersededBy` are explicit optional fields with named evidence objects, so an explicit none/unknown/void state is never confused with an unrecorded one.
3. `syntheticChecksum` is a deterministic stand-in for a server digest and is documented as such — not a clinical integrity control.
4. Amending a signed revision appends a new draft and marks the source `supersededBy`, so `canAmend` becomes false for an already-amended revision.
5. Voided documents remain in the list with an explanatory status and a disabled void action, preserving the audit trail while making the live/void distinction explicit.
6. No PDF/image bytes are generated or fetched: this is a synthetic screen-only slice. Real rendering, font embedding, page numbering and print profiles remain server-side work (CLN-07), out of scope here.
7. Accessible row-action naming on the document table was considered and deliberately not changed: the shared `Button` component accepts no `aria-label`, and modifying it or adding raw-CSS visually-hidden text would breach this story's scope and the token contract. The row's identity is instead carried by its `<caption>`, `<th scope="col">` headers and cell content; a real WCAG audit remains a launch-gate activity.

## Commands run and results (evidence source: executed tool commands, this attempt)

From `apps/staff-web` (node v22.23.5, npm 10.9.9; `npm ci` performed by `check.sh`):

- `bash check.sh` → **exit 0**. Gates in order: `gen:api:check`, `typecheck`, `lint` (`eslint . --max-warnings 0`), `test:coverage`. Result: **29 test files, 189 tests passed; statements 100% (553/553), branches 100% (284/284), functions 100% (215/215), lines 100% (524/524)**, `perFile: true`, "No files with missing coverage."
- `npx vitest run src/features/chart` → **exit 0**, 3 files, **45 tests passed** (after the added CLN-05 test).
- `npm run build` → **exit 0** (`tsc --noEmit` + `vite build`, 31 modules transformed).
- `bash docs.sh /tmp/cw008-docs-verify` → **exit 0**; TypeDoc (`treatWarningsAsErrors`) generated `index.html`.
- `node_modules/.bin/prettier --check apps/staff-web/src/features/chart/ChartScreen.tsx .../chart-screen.test.tsx` (run from repo root with root Prettier) → **exit 0**, "All matched files use Prettier code style!".

From the repository root:

- `bash scripts/frontend-check.sh` → **exit 0**: module typecheck/lint/tests, TypeScript-only and no-`any`/no-class-component source scan, tsconfig strict flags, root Prettier and root Vitest all passed ("frontend standards: policy and tool commands passed").
- `python3 -I scripts/gen-opencode.py --check` → **exit 1** (`opencode.json is out of date`), and `git ls-files -v opencode.json` → flag `S` (skip-worktree). This is the known cloud-worker CI-profile artifact (delivery skill; BUGS OPS-CW-003-01, closed as a false positive), not a change made here. No generated/shared config was edited.

The jsdom console stack traces for "useI18n/useTheme must be used inside Provider" come from provider-missing negative-control tests in the shared scaffold; they do not fail any gate (all tests and the coverage gate passed).

## Defects found

- No production defect remains open. In this attempt only one tenant-key inconsistency was found and hardened (id-only supersede in `handleAmend`); all gates were green before and after the change.
- The inherited notes record that the earlier attempt corrected three test-assertion defects and two TypeScript narrowing errors before reaching green. Those were not re-observed here.
- No clinical, human-participant or simulated-user evidence was available or claimed in this attempt. The earlier attempt's two bounded reviews (Senior React/Vite and Test Engineer) were read-only specialist design reviews; their findings on RTL source scanning, explicit none/unknown states, draft-version/checksum provenance, clinical-vs-entry actors and amendment supersession are reflected in the code. Evidence provenance: tool commands for all gate results; simulated specialist review (earlier attempt, static, did not re-execute checks) for those findings. No real clinical acceptance.
- Final bounded review of this attempt's two-file diff by the Senior React/Vite specialist (session `ses_e09d59b6cffee9igasnBQNylWe`): **no findings**. It source-traced `domain.ts`, `NoteEditor.tsx`, `RevisionHistory.tsx`, `fixtures.ts` and `messages.ts` and confirmed (a) the composite-key supersede is a correct defensive no-op consistent with the other handlers and preserves CLN-05 immutability/amendment semantics, (b) the new history test is deterministic and asserts the exact superseded revision id, and (c) no tenant-leak, signed-edit or RTL/accessibility regression was introduced. This was a read-only simulated specialist review; the reviewer did not execute checks, so all gate results above remain tool evidence only.

## Limitations / unverified behaviour

- The integrator runs the database and integration tests; this frontend story has none, and none were run here by design.
- Accessibility was verified through role/name assertions and logical-CSS source scanning in jsdom, not through an actual WCAG 2.2 AA audit, keyboard pass or screen reader.
- Arabic copy is synthetically translated for the fixture; it is not a clinically approved translation.
- No image/PDF bytes are rendered or scanned in this slice; document associations are synthetic records only.
