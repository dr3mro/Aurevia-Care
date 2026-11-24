# RESP-VISIT-STATE-BADGE — responsive `VisitStateBadge` (features-scheduling)

## Summary

Make the scheduling visit-state badge responsive and RTL-safe at every supported
width (320–1280 px) without changing its behaviour, text, exported names or API
calls. The badge now exposes its active layout band as `data-breakpoint` on the
root and wraps with logical inline styles, so the glyph and the translated state
word wrap instead of clipping or forcing a page-level horizontal scroll. Added a
new jsdom responsive test that drives the real `useBreakpoint` hook through a
deterministic `matchMedia` stand-in at compact, medium and expanded, over the
seven required widths, across viewport changes and under right-to-left.

Files changed (both inside task scope):
- `apps/staff-web/src/features/scheduling/VisitStateBadge.tsx`
- `apps/staff-web/tests/responsive/features-scheduling/VisitStateBadge.responsive.test.tsx` (new)

## Affected requirements

- WEB-07 — staff web usable from 320 px; responsive layout contract.
- UX-01 — WCAG 2.2 AA: logical properties, no hidden clinical text, readable at 200% zoom, keyboard and RTL behaviour.

Acceptance mapping (story details → evidence):
- Widths 320/360/390/768/820/1024/1280, LTR and RTL → viewport-matrix test and RTL test (new file).
- "No horizontal scroll / no clipped or overlapping text / long EN and AR wrap" → wrapping test: `flexWrap: wrap`, `minInlineSize: 0`, `maxInlineSize: 100%` on the root; `<bdi>` keeps `whiteSpace: normal`, `overflowWrap: anywhere`, `minInlineSize: 0`; no fixed block size or font size. jsdom checks structure, not painted pixels (per the story, pixel checks belong to Playwright).
- "Columns stack on phones" → not applicable to a single badge; this is an ancestor table/layout concern. The badge stays one DOM and never hides a value.
- "Coarse pointers get 44×44 targets" / "inputs use 16 px text" → not applicable: the badge has no interactive control or input (asserted: no focusable descendants, no `touch` class). Documented under Findings.
- "Safe-area insets respected" → the badge adds no physical insets; safe-area padding is provided by ancestor `.page`/`.app-shell` classes. Asserted by absence of physical side properties.
- "CSS logical properties only / no clinical field hidden with `display: none`" → logical-properties test and no-hidden-text assertions in every band.
- "Keyboard order" → badge introduces zero tab stops, so the surrounding worklist order is unchanged (asserted `tabIndex === -1`, no focusable descendants, DOM order glyph→label).
- "Do not change behaviour, text, API calls or exported names" → only `data-breakpoint` and three logical style properties were added; glyph, labels, `data-state`, `data-badge-icon` and the `VisitStateBadgeProps` export are unchanged.

## Risk class

Routine (contained UI primitive presentation change). Routed per `docs/WORKFLOW.md`.

## Review owner

Primary implementation: this worker. Bounded plan from the `orevia-senior-react` specialist (report received; it had read-only tools and ran no checks). UX/RTL review applied by the primary via the `orevia-ux-reviewer` and `orevia-senior-typescript` skills. This is synthetic/simulated review, not clinical or human acceptance.

## Data changes

None. Presentation-only change; no schema, migration, backfill, lock or volume impact. Synthetic fixtures only.

## Authorization boundaries and audit events

None. The badge displays an already-computed `VisitState`; it makes no authorization decision and emits no audit event.

## Migration and rollout considerations

None.

## Test evidence

Commands run (exit codes and final lines quoted; evidence source = executed tool commands by this worker unless noted):

- Red first, before the production change:
  `cd apps/staff-web && npx vitest run tests/responsive/features-scheduling/VisitStateBadge.responsive.test.tsx src/features/scheduling/components.test.tsx --reporter=dot`
  → `Test Files 1 failed (1) / Tests 15 failed | 10 passed (25)`; failures were exactly the missing `data-breakpoint` and wrapping styles.

- Green after the change:
  `cd apps/staff-web && npx vitest run tests/responsive/features-scheduling/VisitStateBadge.responsive.test.tsx src/features/scheduling/components.test.tsx --reporter=dot`
  → `Test Files 2 passed (2) / Tests 38 passed (38)`.

- Canonical frontend gate:
  `bash apps/staff-web/check.sh` → `CHECK_EXIT=0`.
  Stages, in order, all clean: `gen:api:check` (`bash scripts/gen-api-types.sh --check`), `typecheck`, `lint` (`eslint . --max-warnings 0`), `build`, bundle-size gate (`node scripts/check-bundle-size.mjs`), `test:coverage`.
  Final test lines:
  `Test Files  159 passed (159)` / `Tests  1916 passed (1916)`.
  (Stack traces in the raw log are expected console noise from provider-boundary negative tests in `I18nProvider.test.tsx` / `ThemeProvider.test.tsx`; the run is green.)

- Typecheck final lines (required paste — no `error TS…` emitted):
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```

- Fast policy checks: `python3 -I scripts/check-names.py` → exit 0; `python3 -I scripts/check-secrets.py` → exit 0; `python3 -I scripts/check-docs.py` → exit 0.

- Deliberately not run: `scripts/check.sh`. It invokes `apps/api/check.sh`, which is `mvn -q -B verify` (all integration tests). The task explicitly forbids a worker from running the full Maven verify / every integration test, so the canonical module gate above plus the fast policy checks were run instead; the integrator and Delivery run the full repository gate.

Entry-point-to-test trace: `VisitStateBadge` is exported from `src/features/scheduling/VisitStateBadge.tsx` and consumed by `src/features/scheduling/AppointmentWorklist.tsx` (the worklist state cell). It is rendered directly through `ThemeProvider` + `I18nProvider` by the new `tests/responsive/features-scheduling/VisitStateBadge.responsive.test.tsx`, with its existing behavioural coverage in `src/features/scheduling/components.test.tsx`.

## Coverage

Command: `bash apps/staff-web/check.sh` (step `test:coverage` → `vitest run --coverage --exclude 'e2e/**'`, v8, `perFile: true`, all thresholds 100%).

Result:
```
No files with missing coverage.
207 files fully covered.
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
```

New production branches: **none**. `VisitStateBadge.tsx` has no `if`, ternary, `&&`/`||`, `switch` or `catch`; its only function is the component, executed by every render. The `useBreakpoint` hook's own branches are covered by existing tests (`tests/theme/breakpoints.test.ts`, `tests/components/MixedScriptText.responsive.test.tsx`). No threshold, exclusion or ignore comment was added or changed.

Each new/changed production line and its test:
- `import { useBreakpoint }` and `const breakpoint = useBreakpoint()` → every test in the new file drives the mocked `matchMedia` at compact/medium/expanded.
- `data-breakpoint={breakpoint}` on the root → asserted by the per-breakpoint structure tests, the seven-width viewport matrix, the band-change test and the RTL test.
- `flexWrap: 'wrap'` → asserted by the per-breakpoint wrapping test.
- `minInlineSize: 0` → asserted by the per-breakpoint wrapping test (tolerant `0`/`0px` check; jsdom serialisation varies).
- `maxInlineSize: '100%'` → asserted by the per-breakpoint wrapping test.
- Preserved lines (glyph span, `aria-hidden`, `data-state`, `data-badge-icon`, `<MixedScriptText>`) → asserted by the structure test, the six-state test, the keyboard-order test and the RTL test.
- Updated TSDoc is a comment, not an executable line.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical results are touched. UX/RTL and keyboard behaviour were checked in jsdom with synthetic data; real-browser Playwright layout, 200%/400% zoom and Arabic-clinician evidence remain separate WEB-10 launch work (as the design contract states). Evidence source: executed tool commands and a simulated UX review; no human, clinician or patient evidence.

## Findings

1. **Pre-existing, out-of-scope conflict markers in HEAD.** `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports committed conflict markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java` (markers reference `origin/swarm/BUILD-37-469`). Verified present in `git show HEAD:...`. These are unrelated to this staff-web story and were not touched. `git merge --no-edit origin/main` reported "Already up to date", so they were not introduced here. The integrator/owner must resolve them before a full-repository gate can pass.
2. **No badge class in `responsive.css`.** There is no dedicated badge/status-chip class. `.cluster` only partially fits: it sets `gap: var(--sc-space-2, 8px)` whereas the badge uses `tokens.spacing.sm` (4 px), and it supplies no `padding-inline`/`border`/`border-radius`. Adding it or overriding it would change presentation the story forbids. Per story rule 3, logical inline styles were used for wrapping.
3. **Requirements explicitly not applicable to this primitive.** 44×44 coarse-pointer targets and 16 px input font need an interactive control/input; the badge has none. Column stacking, safe-area padding and table reflow are ancestor shell/table responsibilities (`.page`, `.app-shell`, `.table-region`). The badge satisfies its part by adding no physical side, no fixed block size and no hidden text.
4. **Two `useBreakpoint` subscriptions per badge** (root and `MixedScriptText`) register two listeners per media query; the test's `matchMedia` stand-in uses a `Set` per query so one `fire()` reaches both. A single-listener fake would silently miss the root's band update.
5. **Environment note:** dependencies were not preinstalled (`vitest MISSING`); `npm ci --no-audit --no-fund` was run in `apps/staff-web` (486 packages) before testing.

## Lessons for workers

- For a pure presentation primitive with no conditionals, per-file 100% v8 coverage is reached once every render executes all lines — but still write the test first and assert the structural outcome; do not add defensive guards you would then have to cover.
- jsdom serialises `minInlineSize: 0` inconsistently (`0` vs `0px`); assert tolerantly rather than pinning a numeric string.
- When a component and one of its children both call `useBreakpoint`, a `matchMedia` fake must keep a `Set` of listeners per query and notify all of them on change.
- Use `I18nProvider initialLocale="ar"` to exercise RTL: it sets `document.documentElement.dir/lang` through the real provider, so the test covers real behaviour instead of hand-setting the document.
- Pre-existing conflict markers or failures in files outside your `scope` are not your defect: record them with the exact command and final line under Findings and stay in scope.

## Why this is not finished

Not applicable — the task is complete. The canonical frontend gate passed with 100% coverage on every metric, all existing tests remain green, and every change is inside the two-path task scope.
