# RESP-DESIGN-PREVIEW — Staff web: responsive `DesignPreview` (app)

## Summary

Made the development-only `DesignPreview` page responsive without changing its
behaviour, rendered text, API calls or exported names. The root now uses the
existing `page` class (logical safe-area padding, single wrapping column,
1200 px reading cap) and exposes the active layout band through the existing
`useBreakpoint` hook as `data-breakpoint`. The one bare Latin measurement run was
wrapped in the existing `MixedScriptText` primitive so digits and units stay an
isolated `dir="auto"` run under Arabic RTL. All child components are unchanged;
each already renders its own compact/medium/expanded structure from the same
hook. Added a new jsdom responsive test file that mocks `matchMedia` for the
three bands and the seven target widths, asserts structure, labels, roles,
keyboard order, dialog views and RTL, and leaves pixel checks to the Playwright
tasks.

## Affected requirements

WEB-07 (supported clients / responsive staff web per decision 0004), UX-01
(English/Arabic RTL, WCAG 2.2 AA layout). No other requirement IDs touched.

## Acceptance mapping

| Story step / acceptance | Implementation | Test |
|---|---|---|
| 320/360/390/768/820/1024/1280 px resolve to compact/medium/expanded | root `data-breakpoint` from `useBreakpoint` | `viewport matrix` (`it.each(widths)`, 7 cases) |
| LTR and RTL at each band | document-level `dir`/`lang`; no subtree direction override | `responsive DesignPreview right-to-left at %s width` (3 bands) |
| No horizontal page scroll; columns stack; safe-area insets | `page` class (single `minmax(0,1fr)` column + logical `max()` safe-area padding) | asserts `classList.contains('page')`, no physical inline styles, no `display:none` |
| Long English/Arabic strings wrap; 200% zoom readable | `MixedScriptText` `<bdi dir="auto">` with `overflow-wrap:anywhere`, plus wrapping cells/buttons | asserts `bdi` dir/lang/`data-breakpoint`, cell and button `overflowWrap`/`whiteSpace`, no `nowrap` or fixed block size |
| Coarse pointers get 44×44 targets; inputs 16 px | existing `.touch` + `@media (pointer: coarse)`, `.field` 16 px | asserts `touch`/`field` classes and `data-coarse-pointer` per band (computed pixels are a Playwright check) |
| Logical properties only; no hidden clinical column | no new CSS; `page` class + logical inline styles only | authored inline-style regex check; full `<th scope="col">` set and all cells present |
| Preview shows each component at the three width classes | all children render at every band; each exposes its own band structure | compact labelled `role=region` vs plain table; form/input/dialog/button/`bdi` `data-breakpoint`; dialog `sheet` vs `centered` |
| Labels, roles, keyboard order unchanged | no text/API change | `getByRole`/`getByLabelText` assertions and focusable-order array at each band |
| Existing tests still pass | minimal production diff | `tests/app/AppShell.test.tsx` + full suite green |

## Risk class

Routine (contained staff-web presentation change in one component plus one new
test file), with a simulated UX review because RTL/WCAG is in scope
(`docs/WORKFLOW.md`).

## Review owner

`smartclinic-senior-react` and `smartclinic-ux-reviewer` — both advisory
subagent reviews, simulated/synthetic evidence only, no human or clinical
participant. Senior React verdict: no blocking defects. UX verdict: no blocking
defect after the measurement-isolation fix below.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.
No synthetic identifiers beyond the existing `fixtures/synthetic.ts` values.

## Authorization boundaries and audit events

None. `DesignPreview` is a development-only visual page; no endpoint, tenant,
role, patient-link or audit event is added or changed.

## Migration and rollout considerations

None. No reserved migration version used. No build script, contract, workflow
or tracker file edited.

## Test evidence

Evidence source for every line below: executed tool commands (no human or
clinical evidence; reviewers are simulated subagents).

- Red phase (before production change):
  `cd apps/staff-web && npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx`
  → `Test Files 1 failed (1)`, `Tests 17 failed | 6 passed (23)`; every remaining
  failure was the missing `data-breakpoint`/`page` root.
- Green phase (final), new + existing preview test:
  `npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx tests/app/AppShell.test.tsx --reporter=dot`
  → `Test Files 2 passed (2)`, `Tests 37 passed (37)`.
- Final new test alone: `npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx --reporter=dot`
  → `Test Files 1 passed (1)`, `Tests 25 passed (25)`.
- Mandatory frontend gate: `bash apps/staff-web/check.sh` (exit 0) →
  `gen:api:check`, `npm run typecheck`, `npm run lint`, `npm run build`,
  `node scripts/check-bundle-size.mjs`, `npm run test:coverage` all passed;
  `Test Files 159 passed (159)`, `Tests 1916 passed (1916)`, 0 failures.
- Typecheck final lines (explicit re-run): `npm run typecheck` →
  `> smartclinic-staff-web@0.1.0 typecheck` /
  `> tsc --noEmit -p tsconfig.json`, exit 0, no `error TS…`.
- Formatting: `npx prettier --check` on both changed files passed;
  root `bash scripts/frontend-check.sh` (exit 0) →
  `Checking formatting... All matched files use Prettier code style!`,
  `Test Files 1 passed (1)`, `Tests 1 passed (1)`,
  `frontend standards: policy and tool commands passed`.
- Naming: `python3 -I scripts/check-names.py` → exit 0, no output.
- Repository check: `bash scripts/check.sh` stopped at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker `skip-worktree` artifact, not a change defect:
  `git ls-files -v opencode.json` → `S opencode.json`; the worktree file is
  byte-identical to `.github/opencode.ci.json`, while `git show HEAD:opencode.json`
  is the local profile. The protected file was **not** edited or regenerated.
- Entry-point-to-test trace: dev route `/design-preview` → lazy
  `createRoutes(includeDesignPreview)` in `src/app/routes.tsx` →
  `DesignPreview` → `tests/app/AppShell.test.tsx` "keeps the development preview
  interactive for visual review" (click/submit/echo, dev only) and the new
  `tests/responsive/app/DesignPreview.responsive.test.tsx` (structure/roles/RTL
  at all bands). The gate proving the entry point is `AppShell.test.tsx`; the
  pixel/zoom/measure claims are explicitly deferred to the Playwright E2E tasks.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest v8,
  per-file thresholds 100%). Final result:
  `No files with missing coverage.`, `207 files fully covered.`,
  `Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`,
  `Functions 100% (1258/1258)`, `Lines 100% (3556/3556)`. No threshold,
  exclusion or skip was changed; no test disabled or ignored.
- Changed production file `src/app/DesignPreview.tsx`: 100% statements,
  branches, functions and lines (covered by the new test at all bands and by
  `AppShell.test.tsx` for the dialog-open, close and note-submit callbacks).
- New branches introduced in production: **none**. The change adds one hook
  call, one `className`, one `data-*` attribute and one existing-primitive
  wrapper; there is no `if`, ternary, `&&`, `||`, `switch` or `catch` in the
  changed code. Behavioural coverage per band:
  - root `page` class + `data-breakpoint=band` → "renders the page with
    responsive classes and exposes the active band" (3 cases) and the 7-width
    viewport matrix.
  - compact table region vs medium/expanded plain table → "shows every
    component with the table, fields and dialog for the band".
  - dialog `sheet`/`centered` and sticky header → "opens the dialog with the
    band-appropriate view and restores focus" (3 cases).
  - measurement `<bdi dir="auto">` under Arabic → RTL block (3 cases) with
    `measurement.tagName === 'BDI'`.
- New test file is a test, not production code, so it has no coverage threshold
  of its own.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. UX/WCAG-01 surface was reviewed by the
simulated UX subagent only; this is synthetic review, not clinical or human
acceptance.

## Findings

1. Fixed during review (UX subagent, simulated): `formatMeasurement(...)` was a
   bare text run in an RTL document, so Latin digits/unit could inherit RTL base
   direction. Wrapped it in the existing `MixedScriptText` primitive; no text or
   API change. RTL test now asserts the isolated `bdi[dir="auto"][lang="ar"]`.
2. Senior React subagent recommended testing RTL at all three bands, not only
   compact. Done: the RTL block is now `describe.each(['compact','medium','expanded'])`.
3. Not changed (UX subagent, optional): the root keeps its pre-existing inline
   `style={{ display:'grid', gap: tokens.spacing.lg }}` alongside the new `page`
   class. It duplicates `.page`'s display/gap only, does not override safe-area
   padding or breakpoint rules, and preserves the existing theme subscription
   and spacing; removing it was judged a larger-than-needed behaviour change.
4. Environment artifact (tool evidence): `bash scripts/check.sh` fails only at
   the stale-`opencode.json` pack check because cloud workers mount the CI
   profile under `skip-worktree` (`S` flag). Next worker should not regenerate
   that file; use `git show HEAD:opencode.json` for HEAD truth.
5. Pre-existing and outside this scope (tool evidence): `git grep -nE
   '^(<<<<<<<|>>>>>>>) '` reports committed conflict markers in
   `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
   `Hl7Parser.java` and `Hl7Segment.java` at HEAD. They are unrelated to this
   story, were present before any edit, and were left untouched.
6. jsdom cannot paint pixels, so 44×44/16 px/safe-area/200%-zoom claims are
   asserted through classes, attributes and structure as the story directs;
   computed values remain Playwright evidence (separate E2E tasks). No claim of
   painted-layout verification is made here.

## Lessons for workers

- When wrapping an existing component in a responsive class, keep the diff to
  the root element: add `data-breakpoint` from the shared hook so child
  components' band structures become assertable in jsdom without touching them.
- `previewRoot()`-style queries must exclude nested elements that share the
  same class: the `Form` primitive renders `<form className="page" …>`, so
  select `div.page[data-breakpoint]` (element type matters, not just DOM order).
- In jsdom, a symmetric CSS shorthand (`padding: 4px`, `border: 1px solid …`)
  expands to physical `paddingLeft`/`borderLeft` keys, so assert on the authored
  `style` attribute text with a regex rather than on expanded style properties
  when checking "logical properties only".
- `fireEvent.click` does not focus the clicked control; focus it explicitly
  before opening a focus-managing dialog if the test asserts focus restoration.
- `getByRole('status')` is ambiguous on this page (the `Echo` output and
  `ProtectedRoute`'s note); always query by accessible name.
- A cloud worker's worktree `opencode.json` is the CI profile under
  `skip-worktree`; a stale-pack failure there is a measurement artifact, not a
  reason to regenerate or commit that protected file.
