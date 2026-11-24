# RESP-AUDIT-PAGE — fix pass (2026-11-22) — Staff web: responsive `AuditPage` (features-admin)

## Summary

Fix pass on an already-implemented and already-merged presentation task. The scoped change (implementation commit `972d9c6a`, original PR #732) made `AuditPage` responsive at 320–1280 px in LTR and RTL without changing behaviour, text, API calls or exported names: shared `page`/`stack`/`grid-2`/`cluster` classes from `responsive.css`, the `useBreakpoint` band, five filter fields stacked on phones and two-column from medium upward, and content hooks `data-audit-page`/`data-breakpoint`/`data-audit-part`/`data-audit-fields`. `972d9c6a` was verified this run to be an ancestor of HEAD (`git merge-base --is-ancestor 972d9c6a HEAD` → true), so no production source change was needed or made.

This run re-verified the current branch state. All in-scope verification is green: clean `tsc --noEmit`, 34/34 scoped tests, and 100% coverage of the only touched production file. The mandatory module-wide gate `bash apps/staff-web/check.sh` is **not** green, but it fails only on 7 responsive test files outside this task's two-file scope — each owned by a different, already-merged (`[x]`) responsive-page task. Fixing them is forbidden by task scope. Details and exact next step are under Findings and "Why this is not finished".

Entry point → test trace: route `/admin/audit` → `AuditPage` (`src/features/admin/AuditPage.tsx`) → `AuditContent` → `Form`/`ApiPage`/`AuditEventList` → assertions in `tests/responsive/features-admin/AuditPage.responsive.test.tsx` (structure, labels, roles, DOM keyboard order, Arabic RTL, stylesheet proxies) and the unchanged `src/features/admin/AuditPage.test.tsx` (query string, filters, paging, tenant key).

## Affected requirements

- WEB-07 — supported clients / responsive staff web (compact layout down to 320 px; browser matrix is Playwright E2E work).
- UX-01 — English and Arabic RTL, mixed-script clinical text, WCAG 2.2 AA support.

Spec evidence (tool reads of): `PROJECT_V1_SPEC.md` (WEB-07, UX-01); layout contract `docs/design/responsive-layout.md` §§2–7, 11–13; decision `docs/decisions/0004-responsive-staff-web.md`.

### Acceptance mapping

| Acceptance point | Local evidence (jsdom structure/proxy) | Painted evidence owner |
|---|---|---|
| 320/360/390 compact: single labelled page column, stacked fields | `keeps one labelled page grid with compact filter fields`; `data-audit-fields=stacked` | `RESP-E2E-*` Playwright tasks |
| 768 medium / 820 / 1024 / 1280 expanded: two-column fields | `keeps one labelled page grid with medium/expanded filter fields`; `data-audit-fields=two-column` | `RESP-E2E-*` Playwright tasks |
| No clinical field/column hidden; long EN/AR wraps | `keeps the full audit content in the tree at %s`; no `display:none`/`visibility:hidden`; `.stack > *` wrap rule as text proxy | `RESP-E2E-*` Playwright tasks |
| Labels, roles, keyboard order unchanged | `keeps roles, labels and keyboard order at %s` (field IDs + first focusable order + non-positive tabindex) | `RESP-E2E-*` Playwright tasks |
| Coarse pointer 44×44, 16 px inputs | `asserts the touch-target and 16px-text layout hooks on a phone` + stylesheet text proxies | `RESP-E2E-TOUCH-TARGETS` / coarse-pointer projects |
| RTL layout, logical properties only, safe-area | `asserts logical-only styling and isolated Arabic runs under right-to-left` + `:root[dir='rtl']` inset proxy | `RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE` |
| Resize crosses a breakpoint and reflows | `reflows the fields when the viewport crosses a breakpoint` (`media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true)`) | `RESP-E2E-REFLOW-AND-ZOOM` |

jsdom cannot paint pixels or evaluate media queries; box sizes, 200% zoom reflow and safe-area padding remain Playwright evidence per `docs/design/responsive-layout.md` §13.

## Risk class

Routine (contained staff-web presentation change). Reviewed earlier by a simulated senior React/Vite implementation specialist and a simulated UX reviewer; this fix pass used `aurevia-delivery`, `aurevia-senior-react` and `aurevia-senior-typescript` skills plus the layout contract. No clinical data, authorization or server behaviour touched.

## Review owner

Senior React and Vite specialist; UX reviewer for the LTR/RTL layout contract. Evidence in this note is from executed tool commands and executed Vitest/Vite/TypeScript tooling this run. Earlier-run simulated UX-review findings are carried forward under Findings and are review activity, not human or clinical participant evidence. No human, clinician or patient evidence was available this run.

## Data changes

None. No migration, backfill, schema change, lock or volume impact; no rollback or forward-fix. Synthetic data only (`synthetic.actor`, `cfg-synthetic`, deliberately long synthetic English and Arabic strings). No dependency added; `npm ci` installed the already-pinned lockfile (486 packages) into ignored `node_modules` and did not modify `package.json` or `package-lock.json`.

## Authorization boundaries and audit events

Unchanged. Tenant key still comes from `useActiveOrganization()` and the `X-Active-Organization` header; `AuditContent` still remounts on an organization switch (`key={organizationId}`). No role, permission or audit-event behaviour changed.

## Migration and rollout considerations

None. Presentation-only DOM/class changes already merged; no server, contract or infrastructure change.

## Test evidence

Branch `swarm/RESP-AUDIT-PAGE-997`, HEAD `39ccb7ae76cee1c0b424c11d525423872c9fa6f5`, `origin/main` `e59eca74bc377a65b5322d5b317d2f224e58432a`. `git merge --no-edit origin/main` → `Already up to date.` (run at start and again before finishing). `git grep -nE '^(<<<<<<<|>>>>>>>) '` on the two scoped paths → none. `git merge-base --is-ancestor 972d9c6a HEAD` → true (implementation merged). `git diff --stat origin/main...HEAD` → only the prior run's notes file, no production source change.

1. `cd apps/staff-web && npm ci --no-audit --no-fund` — exit 0. Final lines: `added 486 packages in 7s` (plus two `npm warn deprecated` notices for transitive `whatwg-encoding` and `eslint`). No lockfile change.
2. `cd apps/staff-web && npm run typecheck` — exit 0. Final lines:
   `> aurevia-staff-web@0.1.0 typecheck`
   `> tsc --noEmit -p tsconfig.json`
   No `error TS…` lines. (This is the mandatory TypeScript gate; clean.)
3. `cd apps/staff-web && ./node_modules/.bin/vitest run src/features/admin/AuditPage.test.tsx tests/responsive/features-admin/AuditPage.responsive.test.tsx` — exit 0:
   `Test Files  2 passed (2)` / `Tests  34 passed (34)` (`Duration  2.37s`).
4. `bash apps/staff-web/check.sh` (mandatory full module gate, run from repo root) — exit **1** at the final `npm run test:coverage` stage only. Final lines:
   `Test Files  7 failed | 199 passed (206)` / `Tests  17 failed | 2525 passed (2542)` / `Duration  122.49s`.
   Earlier stages (generated-client drift, typecheck, lint, production build, bundle-size gate) all passed — `set -euo pipefail` would have aborted before the test phase otherwise. The 7 failing files are all outside this task's two-file scope (listed under Findings). Because the coverage stage failed, Vitest wrote no complete module-wide coverage report.
5. Scoped coverage probe (tool): `cd apps/staff-web && ./node_modules/.bin/vitest run --coverage --coverage.reportOnFailure src/features/admin/AuditPage.test.tsx tests/responsive/features-admin/AuditPage.responsive.test.tsx`, then read `coverage/coverage-summary.json` with `node -e`. Probe exit 1 is the expected per-file 100% threshold failure on other source files not exercised by a two-file probe; the touched file reads 100% (see Coverage).

Root `scripts/check.sh` was deliberately not run: it invokes `apps/api/check.sh` → full `mvn verify` and OWASP/SpotBugs, which this worker prompt forbids (>10 min, ends the run as a timeout). The canonical scoped frontend gate (`apps/staff-web/check.sh`) was run and its stages recorded; the integrator and Delivery run the cross-module gate on the merge result.

## Coverage

Command (step 5) and result for the only production file touched, `src/features/admin/AuditPage.tsx`:
`{"lines":{"total":51,"covered":51,"pct":100},"statements":{"total":57,"covered":57,"pct":100},"functions":{"total":20,"covered":20,"pct":100},"branches":{"total":44,"covered":44,"pct":100}}`.
No coverage threshold, exclusion, skip, `@Generated`/`istanbul ignore`/`c8 ignore` comment or generated-code override was added or changed; no production file was changed this run.

New/changed branch and the test that takes it (unchanged from the merged implementation; listed as required by AGENTS.md Coverage rules):

| Branch | Test |
|---|---|
| `breakpoint === 'compact'` → `fieldsLayout = 'stacked'` | `keeps one labelled page grid with compact filter fields`; `keeps roles, labels and keyboard order at compact`; `asserts logical-only styling and isolated Arabic runs under right-to-left` (compact mock) |
| `breakpoint !== 'compact'` → `fieldsLayout = 'two-column'` | `keeps one labelled page grid with medium/expanded filter fields`; `keeps roles, labels and keyboard order at medium/expanded` |
| `useBreakpoint` listener crosses compact → medium | `reflows the fields when the viewport crosses a breakpoint` (`media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true)`) |
| `from`/`to` validation errors, `loadMore`/empty state | unchanged; still covered by `src/features/admin/AuditPage.test.tsx` |

## Clinical and architecture review

Not required for this presentation-only staff-web change (no signed data, cross-organization sharing, patient association, prescribing or critical results). Earlier-run simulated UX-review findings are recorded under Findings; they are review activity, not clinical acceptance.

## Findings

- **Module-wide gate red for out-of-scope reasons (tool evidence).** `bash apps/staff-web/check.sh` fails 7 responsive test files / 17 tests, none of which is this task's `AuditPage` files. Every owning task is already `[x]`/merged on main, so no open task currently covers these failures:
  - `tests/responsive/features-admin/AdminScreen.responsive.test.tsx` — RESP-ADMIN-SCREEN (#712)
  - `tests/responsive/features-chart/ChartPage.responsive.test.tsx` — RESP-CHART-PAGE (#754)
  - `tests/responsive/features-chart/ChartScreen.responsive.test.tsx` — RESP-CHART-SCREEN (#769)
  - `tests/responsive/features-duplicates/DuplicatesPage.responsive.test.tsx` — RESP-DUPLICATES-PAGE (#777)
  - `tests/responsive/features-patients/PatientsPage.responsive.test.tsx` — RESP-PATIENTS-PAGE (#779)
  - `tests/responsive/features-system/NotFoundPage.responsive.test.tsx` — RESP-NOT-FOUND-PAGE-2 (#795)
  - `tests/responsive/features-system/SignedOutPage.responsive.test.tsx` — RESP-SIGNED-OUT-PAGE (#805)
  Sample errors: `DuplicatesPage` uses `getByRole('region', { name: 'Duplicate review worklist' })` where both the `section` and the `div.table-region` carry that name (multiple-elements error); `NotFoundPage` expects `place-items: center` in an inline style that only has `min-inline-size: 0; text-align: start; overflow-wrap: anywhere`; `SignedOutPage` expects `div.page > div.stack` but the component renders a single `div.stack`; `PatientsPage` keyboard-order expectation ends `…, 'A'` but receives `…, 'DIV'`. These are test/component mismatches introduced by other responsive-page PRs merged after the first (all-green, 1903-test) run of this task. I did not touch them (forbidden by task scope).
- **Pre-existing conflict markers in committed unrelated Java files (tool evidence).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` still finds markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java` (`origin/swarm/BUILD-37-469`). Outside this scope; will break Java compilation/docs for whoever owns those files. Carried forward from earlier runs and re-confirmed this run.
- The RTL label `auditEvents` is used by `ApiPage`'s `section.page`, `AuditEventList`'s `section` and `AdminTableRegion`'s `div[role=region]`, so `getByRole('region', { name: … })` is ambiguous there too; the scoped test uses `getAllByRole(...).length > 0`. A future story could give those regions distinct accessible names.
- jsdom cannot measure pixels, evaluate media queries or detect keyboard provenance; the scoped tests assert classes/data attributes/DOM order plus stylesheet text, not painted output.

## Lessons for workers

- A fix-pass worker on an already-merged presentation task cannot turn a module-wide gate green when other merged tasks' test files are broken on main. Verify the scope in isolation (targeted Vitest, `npm run typecheck`, a `--coverage.reportOnFailure` probe) and report the cross-task regression rather than expanding into out-of-scope files.
- `responsive.css` wraps `.app-shell > *`, `.cluster > *`, `.stack > *`, `.grid-2 > *` and `.card-list > *`, but has no `.page > *` rule and no `.audit-filters`/`.audit-events` class. Do not edit the stylesheet from a page story; wrap children in `stack`/`cluster`/`grid-2` and record the missing rule for a stylesheet story.
- `Form` switches its inner grid to two columns only when it has >6 fields; the audit form has 5, so a `grid-2` div around the fields supplies phone stacking / medium two columns without changing `Form`.
- Reuse the `matchMedia` stub pattern from `tests/components/ApiPage.responsive.test.tsx` (one state object per query, `addEventListener`/`removeEventListener`) so `useBreakpoint` listeners fire deterministically inside `act()`.
- In a Vitest jsdom module `import.meta.url` is not a `file:` URL; use `dirname(fileURLToPath(import.meta.url))` + `join` to read the stylesheet. When asserting CSS from stylesheet text, account for grouped selectors (`.app-shell > *, .cluster > *, …`), not a standalone `.stack > *` block.
- `vitest run --coverage` writes no `coverage-summary.json` when any test fails; pass `--coverage.reportOnFailure` for a scoped coverage probe and read per-file numbers rather than the global threshold result.

## Why this is not finished

The task's implementation is complete and already merged (`972d9c6a`, PR #732; verified as an ancestor of HEAD). This fix pass made no source change; all in-scope verification is green (34/34 scoped tests, clean `tsc --noEmit`, 100% coverage of `AuditPage.tsx`). I cannot truthfully report the mandatory **module-wide** `bash apps/staff-web/check.sh` gate as green, so per the worker rules I record it here.

- What failed: `bash apps/staff-web/check.sh` → `Test Files  7 failed | 199 passed (206)`, `Tests  17 failed | 2525 passed (2542)` (7 out-of-scope responsive test files listed under Findings).
- Most likely cause: parallel responsive-page PRs merged with test expectations that no longer match their components (`SignedOutPage` wrapper, `NotFoundPage` inline style, `DuplicatesPage` duplicate region name, `PatientsPage` keyboard order, etc.). All 7 owner tasks are already `[x]`, so the regressions landed on main without an open repair task.
- What I tried: installed pinned deps with `npm ci`; re-ran `npm run typecheck` (clean, no `error TS…`); ran the two scoped test files (34/34 pass); ran a `--coverage.reportOnFailure` probe proving `AuditPage.tsx` is at 100% on all four metrics; confirmed the implementation commit is merged and the branch is current with `origin/main`; checked both scoped paths for conflict markers (none).
- Exact next step: the integrator opens a repair task for the 7 broken responsive test files (owners are already `[x]`); the owning workers reconcile each test with its component, then re-run `bash apps/staff-web/check.sh` on the merge result. No further work is needed for RESP-AUDIT-PAGE itself; expanding this worker's scope to those files is forbidden by the task.

```task-requests
[
  {
    "title": "Reconcile 7 merged responsive staff-web test files failing on main",
    "details": "bash apps/staff-web/check.sh fails 17 tests across 7 responsive test files whose owner tasks are already [x]/merged: tests/responsive/features-admin/AdminScreen.responsive.test.tsx (#712), tests/responsive/features-chart/ChartPage.responsive.test.tsx (#754), tests/responsive/features-chart/ChartScreen.responsive.test.tsx (#769), tests/responsive/features-duplicates/DuplicatesPage.responsive.test.tsx (#777, getByRole('region') duplicate name), tests/responsive/features-patients/PatientsPage.responsive.test.tsx (#779, keyboard-order tail DIV vs A), tests/responsive/features-system/NotFoundPage.responsive.test.tsx (#795, expects place-items:center absent from inline style), tests/responsive/features-system/SignedOutPage.responsive.test.tsx (#805, expects div.page>div.stack but single div.stack reconciled). Reconcile each test with its component so the full module gate is green; then re-run bash apps/staff-web/check.sh on the merge result. Synthetic data only; do not change RESP-AUDIT-PAGE files (they already pass 34/34 at 100% coverage).",
    "scope": [
      "apps/staff-web/tests/responsive/features-admin/AdminScreen.responsive.test.tsx",
      "apps/staff-web/tests/responsive/features-chart/ChartPage.responsive.test.tsx",
      "apps/staff-web/tests/responsive/features-chart/ChartScreen.responsive.test.tsx",
      "apps/staff-web/tests/responsive/features-duplicates/DuplicatesPage.responsive.test.tsx",
      "apps/staff-web/tests/responsive/features-patients/PatientsPage.responsive.test.tsx",
      "apps/staff-web/tests/responsive/features-system/NotFoundPage.responsive.test.tsx",
      "apps/staff-web/tests/responsive/features-system/SignedOutPage.responsive.test.tsx"
    ],
    "depends": []
  }
]
```
