# RESP-AUDIT-PAGE — fix pass (2026-11-22) — Staff web: responsive `AuditPage` (features-admin)

## Summary

Fix pass on a presentation-only staff-web story whose implementation was already merged (`972d9c6a`, PR #732). `AuditPage` works from 320 px to 1280 px in LTR and RTL without changing behaviour, text, API calls or exported names: it uses the shared `page`/`stack`/`grid-2`/`cluster` classes from `apps/staff-web/src/theme/responsive.css`, reads the layout band from `useBreakpoint`, stacks the five filter fields on phones and gives them two columns from medium upward, and exposes `data-audit-page`/`data-breakpoint`/`data-audit-part`/`data-audit-fields` hooks for jsdom assertions. The responsive test file mocks `matchMedia` for compact/medium/expanded plus coarse-pointer and RTL, and asserts structure, labels, roles, DOM keyboard order and stylesheet-text proxies.

This pass changed **no production source**. `git diff --stat origin/main...HEAD -- apps/staff-web/src/features/admin/AuditPage.tsx apps/staff-web/tests/responsive/features-admin/AuditPage.responsive.test.tsx` is empty — both scoped files are byte-identical to `origin/main`. The earlier fix passes stopped with "Why this is not finished" because `bash apps/staff-web/check.sh` failed on seven responsive test files owned by other, already-merged tasks. On the current base those failures are resolved: the mandatory module gate is fully green (206/206 files, 2542/2542 tests, 100% on all four coverage metrics). This pass re-verifies the merged implementation end to end and records the evidence; the only file it adds is this note.

Entry point → test trace: route `/admin/audit` → `AuditPage` (`src/features/admin/AuditPage.tsx`) → `AuditContent` (useBreakpoint, filter form, tenant key) → `Form`/`ApiPage`/`AuditEventList` → assertions in `tests/responsive/features-admin/AuditPage.responsive.test.tsx` (structure, labels, roles, keyboard order, reflow, RTL, stylesheet proxies) and the unchanged `src/features/admin/AuditPage.test.tsx` (query string, filters, paging, tenant key).

## Affected requirements

- WEB-07 — supported clients / responsive staff web (compact layout down to 320 px; the browser matrix itself is Playwright E2E work). `PROJECT_V1_SPEC.md` line 155.
- UX-01 — English and Arabic RTL, mixed-script clinical text, WCAG 2.2 AA support. `PROJECT_V1_SPEC.md` line 90.

Design evidence (tool reads): `docs/design/responsive-layout.md` §§2–7, 11–13; decision `docs/decisions/0004-responsive-staff-web.md` (Phones 320–767, Tablets 768–1279, Desktop ≥1280; compact/medium/expanded `min-width` bands; no clinical column hidden with `display: none`; 44×44 coarse-pointer targets; 200 % zoom reflow; RTL mirror). Note 0004 is `Proposed` and the spec amendment is an owner decision — the swarm did not edit the spec.

### Acceptance mapping

| Acceptance point (task detail 2) | Named test in `tests/responsive/features-admin/AuditPage.responsive.test.tsx` | Painted-evidence owner |
|---|---|---|
| 320/360/390 compact: one labelled page column, stacked fields | `keeps one labelled page grid with %s filter fields` (compact); `data-audit-fields='stacked'` | `RESP-E2E-*` Playwright tasks |
| 768 medium, 820/1024/1280 expanded: two-column fields | `keeps one labelled page grid with %s filter fields` (medium, expanded); `data-audit-fields='two-column'` | `RESP-E2E-*` Playwright tasks |
| LTR and RTL, logical properties only, safe-area insets | `asserts logical-only styling and isolated Arabic runs under right-to-left` (`wrap('ar')`, `dir=rtl`, no physical inline sides, `:root[dir='rtl']` inset proxy) | `RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE` |
| No clinical column/field hidden; long EN and AR wrap | `keeps the full audit content in the tree at %s (layout proxy)` (no `display:none`/`visibility:hidden`; `.stack > *` `min-inline-size:0` + `overflow-wrap:anywhere` proxy) | `RESP-E2E-REFLOW-AND-ZOOM` |
| Labels, roles and keyboard order unchanged | `keeps roles, labels and keyboard order at %s` (field IDs, first seven focusables, non-positive tabindex) | `RESP-E2E-*` Playwright tasks |
| Coarse pointer 44×44, inputs 16 px | `asserts the touch-target and 16px-text layout hooks on a phone` (`.field`, `.touch`, stylesheet proxies) | `RESP-E2E-TOUCH-TARGETS` |
| Resize crosses a breakpoint and reflows | `reflows the fields when the viewport crosses a breakpoint` (`media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true)` inside `act()`) | `RESP-E2E-REFLOW-AND-ZOOM` |

jsdom cannot paint pixels, evaluate media queries or prove keyboard provenance; box sizes, 200 % zoom, safe-area padding and real keyboard order remain Playwright evidence per `docs/design/responsive-layout.md` §13. No pixel claim is made from these tests.

## Risk class

Routine (contained staff-web presentation change). No clinical data, authorization, permission, patient association, prescribing, critical result, server behavior or contract touched.

## Review owner

Senior React and Vite specialist; UX reviewer for the LTR/RTL layout contract. This pass used `aurevia-delivery`, `aurevia-senior-react`, `aurevia-senior-typescript`, `aurevia-plan-reviewer`, `aurevia-code-reviewer`, `aurevia-coverage-auditor` and `aurevia-devsecops`. Evidence here comes from executed tool commands and executed Vitest/Vite/TypeScript tooling plus read-only specialist reviews — no human, clinician or patient participant evidence. Earlier-run simulated UX findings are carried forward under Findings as review activity only.

## Data changes

None. No migration, backfill, schema change, lock or volume impact, rollback or forward-fix. Synthetic data only (`synthetic.actor`, `cfg-synthetic`, deliberately long synthetic English and Arabic strings). No dependency added; `check.sh` ran `npm ci` against the pinned lockfile (486 packages) without modifying `package.json` or `package-lock.json`.

## Authorization boundaries and audit events

Unchanged. Tenant key still comes from `useActiveOrganization()` and `X-Active-Organization`; `AuditContent` still remounts on an organization switch (`key={organizationId}`) and passes `organizationId` to `queryAuditEvents`. No role, permission or audit-event behavior changed. The RTL test uses the same tenant-scoped provider chain as the real route.

## Migration and rollout considerations

None. Presentation-only DOM/class changes already merged; no server, contract or infrastructure change.

## Test evidence

All commands executed from branch `swarm/RESP-AUDIT-PAGE-997`, HEAD `fe844c6cd6429996ff832e40ab08b0d6d1a75658`, `origin/main` `f80b58b8a9dc60f3975d50ad8583f3c38f8a4fd3`. At the start: `git merge --no-edit origin/main` → `Already up to date.` `git grep -nE '^(<<<<<<<|>>>>>>>) '` on the two scoped paths → no output. `git diff --stat origin/main...HEAD -- <the two scoped files>` → empty (no source change this pass).

1. `cd apps/staff-web && npm run typecheck` — exit 0. Final lines (mandatory typecheck evidence):
   ```
   > aurevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   No `error TS…` lines. The same typecheck also ran inside step 3 and passed.
2. `cd apps/staff-web && ./node_modules/.bin/vitest run src/features/admin/AuditPage.test.tsx tests/responsive/features-admin/AuditPage.responsive.test.tsx` — exit 0:
   `Test Files  2 passed (2)` / `Tests  34 passed (34)` (`Duration  1.10s`).
3. `bash apps/staff-web/check.sh` (from repo root, mandatory module gate) — **exit 0**. Stages, all green: `npm ci` (486 packages), `gen:api:check` (`generated client matches the contract`), `npm run typecheck` (final lines above), `eslint . --max-warnings 0`, `vite build` (`✓ built in 118ms`), bundle-size budget (all chunks `ok`), and `vitest run --coverage --exclude 'e2e/**'`:
   `Test Files  206 passed (206)` / `Tests  2542 passed (2542)` / `Duration  81.19s`.
   Coverage footer: `No files with missing coverage.` / `213 files fully covered. 4 empty files skipped.`
   The `Error: Not implemented: navigation (except hash changes)`, `BoomFailure: MARKER-THROW-MESSAGE`, `useI18n must be used inside I18nProvider` and `useTheme must be used inside ThemeProvider` console lines are expected negative-path noise from ErrorBoundary, UnsupportedBrowserPage and provider tests; they are reported by jsdom, not test failures — the run ends with 206/206 and 2542/2542 passed and exit 0.
4. Scoped coverage probe (tool): `cd apps/staff-web && ./node_modules/.bin/vitest run --coverage --coverage.reportOnFailure src/features/admin/AuditPage.test.tsx tests/responsive/features-admin/AuditPage.responsive.test.tsx`, then `node -e "const s=require('./coverage/coverage-summary.json'); const k=Object.keys(s).find(x=>x.endsWith('/src/features/admin/AuditPage.tsx')); console.log(JSON.stringify(s[k]));"`. The probe's global per-file thresholds go red on untouched `src` files (expected for a two-file probe); the authoritative gate is step 3, which is green. The touched file's row is 100 % on all metrics (see Coverage).

Root `scripts/check.sh` was not run: it invokes `apps/api/check.sh` → full `mvn verify` plus OWASP/SpotBugs, which this worker prompt forbids (>10 min). The canonical module gate was run and is green; the integrator and Delivery run the cross-module gate on the merge result.

## Coverage

Authoritative command: `bash apps/staff-web/check.sh` (step 3). Result: `Statements 100% (4059/4059)`, `Branches 100% (2071/2071)`, `Functions 100% (1346/1346)`, `Lines 100% (3876/3876)`, `No files with missing coverage. 213 files fully covered.`

Scoped probe command (step 4) result for the only in-scope production file, `src/features/admin/AuditPage.tsx`:
`{"lines":{"total":51,"covered":51,"skipped":0,"pct":100},"functions":{"total":20,"covered":20,"skipped":0,"pct":100},"statements":{"total":57,"covered":57,"skipped":0,"pct":100},"branches":{"total":44,"covered":44,"skipped":0,"pct":100}}`.

No coverage threshold, exclusion, ignore comment, skip or generated-code override was added or changed. No production file changed this pass, so there are **no new branches** to attribute; the merged implementation's branches and the existing tests that take them are:

| Branch in `AuditPage.tsx` | Test that takes it |
|---|---|
| `fieldsLayout = breakpoint === 'compact' ? 'stacked' : 'two-column'` — compact side | `keeps one labelled page grid with %s filter fields` (compact), `keeps roles, labels and keyboard order at %s` (compact), `asserts logical-only styling and isolated Arabic runs under right-to-left` |
| Same ternary — non-compact side (medium, expanded) | `keeps one labelled page grid with %s filter fields` (medium/expanded), `keeps roles, labels and keyboard order at %s` (medium/expanded) |
| `useBreakpoint` listener across a band boundary | `reflows the fields when the viewport crosses a breakpoint` |
| `from`/`to` validation `&&`, error-summary `if`s and field `error` spread ternaries (`:86-93, 118-133, 186, 197`) | `blocks an invalid instant with a linked error and no query`; `links an invalid upper-bound instant to the to field`; `submits the exact contract query string for the supplied filters`; `applies an empty filter set without sending a redundant query` — all in `src/features/admin/AuditPage.test.tsx` |
| Five `applyFilters` `=== '' ? {} : {...}` ternaries (`:99-103`) | `submits the exact contract query string for the supplied filters`; `applies an empty filter set without sending a redundant query` |
| `cursor === undefined ? {} : {cursor}` (`:213`) | `appends the next page while a cursor exists` (asserts `cursor=seq-2` in `descriptorAt(1).url`) |
| `accumulated.length === 0` empty state vs list (`:227`) | `shows a clear no-events state...`; `treats a page without an events field as empty` (empty side); row-rendering tests (non-empty side) |
| `page.events ?? []` in `append` (`:79`) | `treats a page without an events field as empty` (undefined side); row-rendering tests (present side) |
| `next !== null && next !== undefined` load-more branch (`:237`) | `appends the next page while a cursor exists` (clicks Load more, asserts the second page and the button's absence afterward) |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing, release of information or critical results. Simulated UX-review findings from earlier runs are recorded under Findings as review activity, not clinical acceptance.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- **Earlier blockers are resolved on the current base (tool evidence).** The prior notes reported `bash apps/staff-web/check.sh` failing 17 tests in seven responsive test files (`AdminScreen`, `ChartPage`, `ChartScreen`, `DuplicatesPage`, `PatientsPage`, `NotFoundPage`, `SignedOutPage`). On current `origin/main` `f80b58b` the same command exits 0 with 206/206 files, 2542/2542 tests and 100 % coverage. No out-of-scope file was edited by this pass.
- **Pre-existing conflict markers in three HL7 Java files (tool evidence, out of scope).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds lines 6/10 in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, lines 14/18 in `Hl7Parser.java` and lines 10/14 in `Hl7Segment.java`. They are unresolved merge output committed on `main`, inside Javadoc, so they do not fail `javac`, tests or the staff-web gate, but they violate the repository rule that the grep be empty. Owners: the BUILD-37 / interoperability work; requested as a task below. This pass did not touch them.
- The RTL label `auditEvents` names `ApiPage`'s `section.page`, `AuditEventList`'s `section` and `AdminTableRegion`'s `div[role=region]`, so `getByRole('region', { name: … })` is ambiguous; the scoped test uses `getAllByRole(...).length > 0`. A future story could give those regions distinct accessible names.
- jsdom cannot measure pixels, evaluate media queries or detect keyboard provenance; the scoped tests assert classes, data attributes, DOM order and stylesheet text, never painted output. Painted size, 44×44 boxes, 200 % zoom and safe-area padding remain `RESP-E2E-*` evidence (design contract §13).
- Coverage auditor Optional (review evidence, not a gate failure): `src/features/admin/AuditPage.test.tsx` has no case that supplies events with the `nextCursor` key absent (`undefined`) to pin the second clause of `next !== null && next !== undefined` behaviorally. Istanbul reports 44/44 branches and the full module gate is green; adding such a test would strengthen semantics but is not required by any gate. Left unchanged to keep this pass notes-only and inside scope.

## Lessons for workers

- A re-dispatched worker can find the task's code already merged in HEAD. Verify the scope in isolation (targeted Vitest, `npm run typecheck`, a `--coverage.reportOnFailure` probe) and then run the module gate; distinguish already-resolved module-gate failures from ones you cause by checking whether the failing files are in your scope and by re-running the gate on the current base.
- `bash apps/staff-web/check.sh` writes expected jsdom console errors (`Not implemented: navigation`, provider-outside-provider throws) while still exiting 0 — judge the green by the `Test Files`/`Tests` summary and exit status, not by console noise.
- `vitest run --coverage` on a two-file subset always fails the global per-file threshold because `vite.config.ts` sets `include: ['src/**/*']` with `perFile: true`; pass `--coverage.reportOnFailure`, read the touched file's row from `coverage/coverage-summary.json`, and treat the full module gate as authoritative.
- `scripts/check-verdicts.py` matches `- aurevia-<name>: GO`, not a bare `VERDICT: GO` line, in `## Review verdicts`; write the three lines in that exact form.
- `responsive.css` wraps `.app-shell > *`, `.cluster > *`, `.stack > *`, `.grid-2 > *` and `.card-list > *`, but has no `.page > *` rule and no `.audit-filters`/`.audit-events` class. Do not edit the stylesheet from a page story; wrap children in `stack`/`cluster`/`grid-2` and record the missing rule for a stylesheet story. `Form` switches to two columns only above six fields, so a `grid-2` div supplies phone stacking / medium two columns for the five-field audit form.
- Reuse the `matchMedia` stub pattern with one state object per query so `useBreakpoint` listeners can be fired deterministically inside `act()`; in a Vitest jsdom module `import.meta.url` is not a `file:` URL, so use `dirname(fileURLToPath(import.meta.url))` + `join` to read the stylesheet.

```task-requests
[
  {
    "title": "Resolve conflict markers in three committed HL7 Javadoc files",
    "details": "git grep -nE '^(<<<<<<<|>>>>>>>) ' finds unresolved merge markers inside Javadoc in apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java (lines 6/10), Hl7Parser.java (14/18) and Hl7Segment.java (10/14), committed on main from origin/swarm/BUILD-37-469. Both sides of each conflict are byte-identical lines, so keep one copy and delete the markers plus the duplicate. Add a regression check that fails future merges carrying conflict markers, with its own 100%-covered unit tests. Do not touch RESP-AUDIT-PAGE files; they are green at 100%.",
    "scope": [
      "apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java",
      "apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7Parser.java",
      "apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7Segment.java"
    ],
    "depends": []
  }
]
```
