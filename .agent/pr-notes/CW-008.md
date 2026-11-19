# CW-008 — Staff web React chart and document screens (synthetic fixtures)

## Scope and requirements

- Scope: `apps/staff-web/src/features/chart` only. No tracker, API, database, contract, workflow, script or shared-config files were edited.
- Requirements: **CLN-03, CLN-05, CLN-07, UX-01** (plus tenancy evidence for IAM-01/ARC-01 at the UI boundary).
- Depends on CW-004 scaffold; reuses its `ThemeProvider`, `I18nProvider`, `MixedScriptText`, `Button`, `i18n/format` helpers and `syntheticOrganizations` fixtures. No real endpoint or clinical data is used.

## Acceptance mapping

| Requirement | Acceptance behaviour | Implementation / evidence |
|---|---|---|
| CLN-03 | Longitudinal view of problems, allergies, medications and vitals with units, source, effective date, verification state, original code/text, and separate clinical author vs data-entry actor. Explicit `none` and `unknown` states are first-class values, not missing fields. | `LongitudinalChart.tsx`, `domain.ts` (`CodedEntry`, `Vital`, `ChartVerification`), `fixtures.ts`; `components.test.tsx` asserts captions, units (`mmHg`, `°C`, `beats/min`), author/entered-by headers and none/unknown/verified labels; `chart-core.test.ts` asserts fixture states. |
| CLN-05 | SOAP draft → signed; required SOAP fields, signer, attestation, patient/encounter context and optimistic version precondition enforced in the domain; content checksum and template version stamped on signing; signed revisions read-only in the UI; separate amendment draft linked to the source and superseding it; entered-in-error preserves prior revision and records actor, reason, recipients and time. | `domain.ts` (`signDraft` guards, `updateDraft`, `createAmendment`, `markSuperseded`, `markEnteredInError`, `syntheticChecksum`, `currentRevision`), `NoteEditor.tsx` (`readOnly` textareas with no change handler for signed revisions, amendment action only when `canAmend`, `role="alert"` banner), `RevisionHistory.tsx`; `chart-core.test.ts` covers every guard; `chart-screen.test.tsx` covers sign/amend/entered-in-error flows and history preservation. |
| CLN-07 | Document list shows PDF/image associations with version, language, checksum, link target and rotation; void records retain reason and disable the void action; rotation changes only presentation state, never the checksum. | `domain.ts` (`visibleDocuments`, `voidDocument`, `rotateDocument`, `replaceDocument`), `DocumentList.tsx`; tests assert en/ar badges, void reason, disabled void button, rotation normalization and checksum immutability. `visibleDocuments` is the tenant-safe projection for a future read-only view; the screen intentionally renders voided rows so the audit state is visible. |
| UX-01 | English/Arabic bundles with key parity, document direction from the shared `I18nProvider`, `<bdi dir="auto">` mixed-script isolation, logical CSS only, local-script names, UTC instants with IANA zones and unit-preserving number formatting. | `messages.ts` + parity test in `chart-core.test.ts`; `chart-core.test.ts` RTL source scan rejects physical margin/padding/border/text-align properties; Arabic RTL rerender assertions in all three test files; `MixedScriptText` and `formatInstant`/`formatMeasurement` reuse. |

## Entry point → test trace

`ChartScreen` (active-tenant chart selection) → `chartsForTenant` → `LongitudinalChart` / `NoteEditor` / `DocumentList` → guarded domain commands (`signDraft`, `createAmendment` + `markSuperseded`, `markEnteredInError`, `voidDocument`, `rotateDocument`, `replaceRevision`/`replaceDocument`) → `chart-status` live region or `role="alert"` banner. The happy path and the denial/lock paths are asserted from that entry point in `chart-screen.test.tsx`; guard-level failures are asserted directly in `chart-core.test.ts`.

## Decisions

1. Feature-local bilingual dictionary (mirrors CW-007's scheduling module) because the shared i18n bundle is outside this story's scope; parity is machine-checked for both locales.
2. `enteredInError`, `void` and `supersededBy` are explicit optional fields with named evidence objects, so an explicit none/unknown/void state is never confused with an unrecorded one.
3. `syntheticChecksum` is a deterministic stand-in for a server digest and is documented as such — not a clinical integrity control.
4. Amending a signed revision appends a new draft and marks the source `supersededBy`, so `canAmend` becomes false for an already-amended revision.
5. Voided documents remain in the list with an explanatory status and a disabled void action, preserving the audit trail while making the live/void distinction explicit.
6. No PDF/image bytes are generated or fetched: this is a synthetic screen-only slice. Real rendering, font embedding, page numbering and print profiles remain server-side work (CLN-07), out of scope here.

## Commands run and results (evidence source: executed tool commands)

From `apps/staff-web`:

- `npm ci --no-audit --no-fund` → exit 0 (312 packages; pinned toolchain installed).
- `npm run typecheck` → exit 0 (strict TS with `exactOptionalPropertyTypes` and `noUncheckedIndexedAccess`).
- `npx vitest run src/features/chart` → red first (3 assertion failures + 2 type errors during the first pass), then green: 43 tests passed.
- `bash check.sh` → **exit 0**. Gates in order: `gen:api:check`, `typecheck`, `lint` (`eslint . --max-warnings 0`), `test:coverage`. Result: **26 test files, 147 tests passed; statements 100% (405/405), branches 100% (204/204), functions 100% (164/164), lines 100% (386/386)** with `perFile: true`.
- `bash docs.sh /tmp/cw008-docs-1` → exit 0; TypeDoc (`treatWarningsAsErrors`) generated `index.html`.
- `npm run build` → exit 0 (`tsc --noEmit` + `vite build`, 31 modules transformed).

Repo-level note (not caused by this change): `bash scripts/quick-check.sh` stops at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`; `python3 -I scripts/gen-opencode.py --check` likewise exits 1. `git ls-files -v opencode.json` reports flag `S` (skip-worktree), i.e. the cloud worker's CI profile is present in the worktree, exactly the known cloud-worker artifact described in the delivery skill. No file outside the story scope was changed to address it; the integrator's trusted environment owns that gate.

## Defects found

- During implementation: three test-assertion defects (exact-text matches against composite cell text) and two TypeScript narrowing errors, all corrected in this branch before green. No production defect remains open.
- No clinical, human-participant or simulated-user evidence was available or claimed. The two bounded reviews were read-only specialist design reviews (Senior React/Vite and Test Engineer); their findings on RTL source scanning, explicit none/unknown states, draft-version/checksum provenance, clinical-vs-entry actors and amendment supersession were incorporated.

## Limitations / unverified behaviour

- The integrator runs the database and integration tests; none were run here by design.
- Accessibility was verified through role/name assertions and logical-CSS source scanning in jsdom, not through an actual WCAG 2.2 AA audit or screen reader.
- Arabic copy is synthetically translated for the fixture; it is not a clinically approved translation.
