# RESP-DOCUMENT-LIST — Staff web: responsive `DocumentList` (features-chart)

## Summary

`DocumentList` now renders document associations as stacked cards on compact
viewports and keeps the original semantic table on medium and expanded
viewports. Both layouts consume one shared set of display values, so a width
change never disagrees about a clinical field. The file name is isolated by
`MixedScriptText` and wraps; every clinical field, both actions, the empty
state and DOM keyboard order are preserved. Presentation uses the existing
`card-list`, `stack`, `cluster` and `touch` classes from `theme/responsive.css`
plus logical inline styles only; no stylesheet, dependency, behaviour, text,
API call or exported name changed. Evidence came from executed tool commands,
file reads, and an advisory senior React specialist (file reads only). No
simulated-user run and no human participant were used.

## Affected requirements

- WEB-07 (responsive staff web): compact rows become cards; no horizontal page
  scroll; long English and Arabic titles wrap; no clinical column hidden;
  coarse-pointer targets come from the existing `.touch` class; logical
  properties only; 16 px input text is not applicable (no inputs in this
  component); safe-area mapping stays at the shell boundary.
- UX-01 (accessibility and localization): semantic labels and roles kept;
  Arabic titles remain bidi-isolated under `dir="rtl"`; focus order is DOM
  order (rotate then void per document); no fixed block size or `display: none`
  on clinical content, so text stays readable at 200 percent zoom.

Acceptance mapping:

| Criterion | Evidence |
|---|---|
| compact → cards, no table | `DocumentList.responsive.test.tsx` first and second tests |
| medium/expanded → full 9-column table | third test |
| breakpoint change keeps actions and order | fourth test |
| RTL, localized title, logical styles | fifth test |
| all branches incl. callbacks covered | `npm run test:coverage` (100%) |
| existing behaviour and names unchanged | `src/features/chart/components.test.tsx` still passes |

## Risk class

Routine (contained UI change, one component plus one new test file). No
clinical policy, authorization, patient association, prescribing or critical
result is touched.

## Review owner

Senior React and Vite specialist (advisory review requested and received from
`orevia-senior-react`); senior TypeScript standards applied. UX review
limited to the responsive/RTL structure assertions in the new test file.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.
All fixtures are synthetic (`syntheticAlphaChart.documents` plus inline
synthetic documents with `SYN-*` IDs). No patient data or credentials were
added to any file, log or note.

## Authorization boundaries and audit events

None. The component only renders documents passed in by its caller and calls
the injected `onVoid`/`onRotate` callbacks with the document id. No role,
tenant or patient-link scope changed; no audit event added or changed.

## Migration and rollout considerations

None. Presentation-only change; no reserved migration version used.

## Test evidence

Entry point → test trace: `DocumentList` receives synthetic documents from
`src/features/chart/fixtures.ts`; `useBreakpoint` reads the mocked `matchMedia`
queries; compact renders the `ul.card-list`, medium/expanded render the
`table`; both actions are fired through `Button`.

Commands run (all from `apps/staff-web` unless noted), with final lines:

- Red first: `./node_modules/.bin/vitest run --exclude 'e2e/**' tests/responsive/features-chart/DocumentList.responsive.test.tsx` → `Test Files 1 failed (1); Tests 5 failed (5)` (compact still showed the table).
- Green: `./node_modules/.bin/vitest run --exclude 'e2e/**' tests/responsive/features-chart/DocumentList.responsive.test.tsx src/features/chart/components.test.tsx` → `Test Files 2 passed (2); Tests 23 passed (23)`.
- `bash check.sh` → `Test Files 159 passed (159); Tests 1897 passed (1897)`; `No files with missing coverage. 207 files fully covered.`; `Statements 100% (3733/3733), Branches 100% (1896/1896), Functions 100% (1263/1263), Lines 100% (3567/3567)`. (The console "useTheme must be used inside ThemeProvider" lines are the expected error path asserted by `tests/theme/ThemeProvider.test.tsx`, not failures.)
- Typecheck final lines (required by the frontend rule):
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  Exit 0, no `error TS…` lines.
- `npm run gen:api:check` → `gen:api: generated client matches the contract`.
- `npm run lint` → exit 0, no output (`eslint . --max-warnings 0`).
- `npm run build` → exit 0 as part of `check.sh`.
- `node scripts/check-bundle-size.mjs` → all chunks `ok` (WEB-08 budget).
- Root `bash scripts/check.sh` (from repo root) → stopped at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (documented worker-image artifact, see Findings). Remaining stages run individually: `bash -n`/shellcheck, `project-name`, `check-docs`, `check-secrets`, `check-names`, `check-wiring`, `check-migrations --require-baseline` all exit 0; `bash scripts/frontend-check.sh` → `frontend standards: policy and tool commands passed`; `bash scripts/python-coverage.sh` → `FAILED (failures=6, errors=2)` from the same CI-profile/Surefire-report artifacts below, not from this change.

## Coverage

Command: `npm run test:coverage` (via `bash check.sh`), from `apps/staff-web`.
Result: per-file 100% on all 207 source files; explicit report entry
`src/features/chart/DocumentList.tsx {"l":100,"b":100,"f":100,"s":100}`.

New or changed branches and the test that takes each side (Vitest aggregates
coverage across the whole run, so existing tests also count where named):

| Branch in `DocumentList.tsx` | Covering test(s) |
|---|---|
| `breakpoint === 'compact'` true | new compact tests 1, 2, 5, callback test |
| `breakpoint === 'compact'` false | new medium/expanded test; existing `components.test.tsx` (no matchMedia → fallback `expanded`) |
| card `documents.length === 0` true / false | new "defined empty state as a card" / new compact non-empty tests |
| table `documents.length === 0` true / false | existing "empty state" / new medium/expanded + existing tests |
| `locale === 'ar' && titleLocal` (both operands) | new RTL test (localized + Latin-only documents), new compact English test |
| `document.language === 'ar' ? ... : ...` | new RTL test (Arabic document) / new compact English test |
| `document.void ? ... : ...` (status) | new compact voided document (`doc-004`) / non-voided documents |
| `voided: document.void !== undefined` | same as status branch; asserts disabled vs enabled card Void buttons |
| card `documents.map` callback | new compact non-empty tests |
| table `documents.map` callback | new medium/expanded test and existing tests |
| card `() => onRotate(id)` / `() => onVoid(id)` | new "routes the card rotate and void actions" test (clicks both, asserts ids) |
| table `() => onRotate(id)` / `() => onVoid(id)` | existing `components.test.tsx` click assertions |
| `CardField` component | every compact non-empty card |
| `documentValues` helper | every non-empty render in both layouts |

No code was excluded, skipped or present only to touch a line; no threshold
was lowered and no `# pragma`, ignore comment or disabled test was added.

## Findings

- The story text mentions a "date" field and a "download" action. Neither
  exists in `DocumentAssociation` (`domain.ts:102-117`); the component has
  title, kind, version, language, checksum, linkedTo, rotation, status and
  Rotate/Void actions. Rule 5 ("do not change behaviour/text/API") wins: all
  existing fields and actions are kept and none were invented. This was
  confirmed by the senior React specialist (file reads only).
- Pre-existing, outside this task's scope: `git grep` finds literal conflict
  markers committed in HEAD in three unrelated Java files —
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java`, `Hl7Segment.java` (lines around 6/14/10). They were not
  introduced by this change and were not edited. The integrator should route
  them to a Java repair story.
- Root `scripts/check.sh` and `scripts/python-coverage.sh` failures are the
  documented cloud-worker artifacts (OPS-CW-003-01): `git ls-files -v
  opencode.json` prints `S` (skip-worktree), so the CI profile masks the
  committed local profile; Python tests that compare the generated profile
  fail accordingly. Two further Python errors are
  `FileNotFoundError: apps/api/target/surefire-reports` in `test_worker_gate`
  because no Java build ran in this worker. Neither relates to the two
  scoped frontend files. `apps/staff-web/check.sh` (the canonical gate for
  this module) is fully green.
- jsdom cannot lay out pixels: class names, roles, attributes, logical inline
  styles and DOM order are asserted here; painted-width, 44 px target and
  zoom checks remain the Playwright tasks as the story directs.

## Lessons for workers

- Extract shared display/format values into one helper before adding a second
  layout. It removes duplicated ternaries and means each branch needs only
  one named test instead of a copy per layout.
- React serializes inline `minInlineSize: 0` as `min-inline-size: 0px`; assert
  such values with a regex or check `overflowWrap`/`whiteSpace` instead of an
  exact `style.minInlineSize` string.
- `headers[n] ?? ''` on a fixed array creates an unreachable branch that fails
  100% branch coverage. Use explicit `chartText(locale, key)` calls for labels.
- In a cloud worker, a root `scripts/check.sh` failure saying `opencode.json
  is stale` is a measurement artifact: confirm `git ls-files -v opencode.json`
  shows `S`, then run the remaining stages individually instead of editing a
  protected file.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient
association, prescribing or critical result is created or changed. The
component is presentational and uses the caller's synthetic data only.
Simulated review only — no authorized clinician or unit user participated.
