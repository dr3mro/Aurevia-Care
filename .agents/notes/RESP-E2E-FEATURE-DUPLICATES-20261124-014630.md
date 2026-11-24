# RESP-E2E-FEATURE-DUPLICATES — staff-web e2e: duplicates screens on phone, tablet and desktop

## Summary

No code was written in this run. The task scope is a **single new file**,
`apps/staff-web/e2e/responsive-duplicates.spec.ts`. The advisor brief's
reachability gate (step 1) was confirmed to fail: no in-scope path can mount the
real `DuplicatesPage` in a browser, so writing the spec would require either a
fake DOM or out-of-scope harness files. Per the brief's step 7, `tasks/_head.md`
lines 31 and 43 and AGENTS.md ("Ask for a task that does not exist") I stopped
without creating any file, recorded the missing prerequisites below as
`task-requests`, and left this task open for the orchestrator.

All six named specialists were consulted. They were unanimous that no in-scope
mount exists and that stopping is the compliant outcome:
`aurevia-context-optimizer`, `aurevia-plan-reviewer`,
`aurevia-senior-react`, `aurevia-code-reviewer`,
`aurevia-coverage-auditor`, `aurevia-devsecops`.

No file was created, modified or committed. Working tree is clean at
`398824281e61748a12ce4b030325e9bd76a608c1`, equal to `origin/main` (divergence
`0 0`). No conflict markers. Branch `swarm/RESP-E2E-FEATURE-DUPLICATES-1369`.

## Affected requirements

- WEB-07 — supported clients / browser matrix.
- UX-01 — English and Arabic, RTL, mixed-script clinical text, WCAG 2.2 AA.

Neither requirement can be evidenced by a browser test in this run because the
feature has no reachable browser entry point.

## Risk class

Routine (test-only task, no production code). The blocker is test
infrastructure reachability, not a clinical or tenant-policy change.

## Review owner

`aurevia-senior-react` (staff web). Supporting read-only reviews from
`aurevia-senior-typescript`, `aurevia-test-engineer`,
`aurevia-context-optimizer`, `aurevia-plan-reviewer`,
`aurevia-code-reviewer`, `aurevia-coverage-auditor` and
`aurevia-devsecops`.

## Data changes

None. No migration, backfill, lock or volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope was modified and no audit
event was added. The future harness must scope every intercepted call to the
synthetic `alphaOrganizationId` tenant and add a catch-all synthetic 404 so no
unmatched request can reach a real host (DevSecOps Optional finding).

## Migration and rollout considerations

None. No Flyway/RLS, OpenAPI, regenerated client, Idempotency-Key or deployment
change.

## Test evidence

Commands executed in this run (all tool evidence):

1. Reachability search (tool):
   `grep -R "DuplicatesPage\|/duplicates\|duplicatesflow" apps/staff-web/src/features/*/routes.ts apps/staff-web/e2e`
   → no matches. `find apps/staff-web/e2e` → no `duplicatesflow` directory.
   `sed -n '400,415p' apps/staff-web/src/features/duplicates/DuplicatesPage.test.tsx`
   → `it('does not claim a shared app route (BUILD-52 owns registration)', ...)
   expect(patterns).not.toContain('/duplicates');` (lines 405–409).
   `git log --all --diff-filter=D -- 'apps/staff-web/e2e/*duplicat*'` → no
   deleted duplicates harness; none ever existed.
2. Every sibling flow reaches its page through a checked-in harness pair.
   Representative trace (tool, read):
   `apps/staff-web/e2e/pharmacyflow/pharmacy-bilingual-journey.spec.ts:71`
   `page.goto('/e2e/pharmacyflow/pharmacy-bilingual-harness.html')` →
   `pharmacy-bilingual-harness.html:21` loads `./pharmacy-bilingual-harness.tsx`
   → that `.tsx` imports the real page and mounts it under `ThemeProvider` >
   `I18nProvider` > `ActiveOrganizationProvider`. The same `.html` + `.tsx`
   pattern exists for admin, lab, radiology, reception, print, finance,
   orderentry, therapy and clinical-note. `playwright.config.ts:136-144` starts
   Vite dev, which is what serves those harness pages.
3. `bash apps/staff-web/check.sh` — FAILED at the last stage (`npm run
   test:coverage`). The earlier stages (`gen:api:check`, `typecheck`, `lint`,
   `build`, `node scripts/check-bundle-size.mjs`) passed because the script uses
   `set -euo pipefail` and reached the final stage. Final lines (tool):

   ```
    FAIL  tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts > WEB-10 clause 2 — real-browser role journeys > GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded
   AssertionError: expected [ …(14) ] to deeply equal [ …(9) ]
   ❯ tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166:26

    Test Files  1 failed | 270 passed (271)
         Tests  1 failed | 3050 passed (3051)
      Duration  102.78s
   ```

   This failure is **pre-existing on `main`** and unrelated to this run: the tree
   was clean and unchanged, and HEAD equals `origin/main`. The GAP tripwire at
   that file's line 166 pins the exact e2e spec set (`currentSpecs`, 9 names),
   while five specs from other merged stories are present on disk (14 names):
   `adminflow/admin-journey.spec.ts`, `financeflow/finance-journey.spec.ts`,
   `pharmacyflow/pharmacy-bilingual-journey.spec.ts`,
   `radflow/radiology-bilingual-journey.spec.ts`,
   `therapyflow/therapy-journey.spec.ts`. I did not edit the tripwire: it is a
   test file outside this task's single-file scope, and the daily-notes rule for
   a pre-existing red on `main` is "owner fixes it". Recorded as a task-request
   below.
4. `npm run typecheck` (in `apps/staff-web`) — PASS, exit 0. Final lines (tool):

   ```
   > aurevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   TYPECHECK_EXIT=0
   ```
5. No Playwright command was run because no spec file was created: there is no
   page to navigate to, so any spec would assert against a 404 or be
   assertion-free.

Entry-point-to-test trace: **cannot be produced**. The required chain
harness → `DuplicatesPage` → screen → assertion has no first link. There is no
registered `/duplicates` route (`src/app/routes.tsx:35-37,76-89` discovers only
`features/*/routes.ts`; no `src/features/duplicates/routes.ts` exists), no
duplicates harness pair, and the pre-login shell exposes no other mount. The
jsdom tests under `tests/responsive/features-duplicates/` exercise the real
components but cannot paint or measure pixels or drive real keyboard/touch, so
they do not satisfy this task's browser claims.

## Coverage

No production source was changed; per-file 100% coverage is unchanged. The
intended e2e spec is outside the Vitest coverage scope
(`apps/staff-web/vite.config.ts:24` includes only `src/**/*.{ts,tsx}`), and
`apps/staff-web/check.sh:20` runs Vitest, not Playwright. No new production
branch exists, so there is no branch-to-test mapping to record. Coverage
auditor verdict: GO. The observed baseline coverage run failed only on the
pre-existing spec-set tripwire documented under Test evidence.

## Clinical and architecture review

Not applicable: no production code, clinical data, real identifier, credential
or cross-organization behavior was added. The feature's synthetic fixtures
(`alphaOrganizationId`, `syntheticPair`, `syntheticDetail`, `reviewOpen`,
`secondReviewerSubject` from `src/features/duplicates/fixtures.ts`) were verified
by DevSecOps as synthetic-only, with no PHI or secrets.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

Additionally, `aurevia-senior-react` returned **NO-GO on task achievability**
(not a review of a change): the single-file scope cannot mount the page, and an
inline-module `page.addScriptTag` mount is not technically viable — Vite does
not rewrite bare specifiers in a Playwright-injected script, and using hashed
optimized-dep URLs would couple the test to Vite internals. This finding
confirms the stop; it is recorded here rather than hidden.

## Findings

1. **Dead-end scope (blocker).** `apps/staff-web/src/features/duplicates/` has
   no `routes.ts`; `DuplicatesPage.test.tsx:405-409` asserts `/duplicates` is
   not registered and that BUILD-52 owns registration. There is no
   `apps/staff-web/e2e/duplicatesflow/` harness (`.html`/`.tsx`) and no
   `synthetic-duplicates-api.ts`; none ever existed in git history. The
   permitted single spec file cannot mount the real feature. Next worker must
   wait for the harness task below (or BUILD-52 route registration) before
   retrying this story.
2. **Do not fake the mount.** An injected inline ES module that imports
   `DuplicatesPage` and calls `createRoot` is a fake DOM in disguise, is not a
   discoverable or traceable entry point, and breaks the established harness
   convention. All reviewers rejected it.
3. **Pre-existing red on `main`.** `bash apps/staff-web/check.sh` fails at the
   `test:coverage` stage on the GAP spec-set tripwire
   (`tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`),
   14 specs on disk vs 9 pinned. Caused by other merged stories adding e2e
   specs without updating `currentSpecs`; not caused by this run. It will also
   block the next worker's `check.sh` and must be reconciled when the duplicates
   spec lands. Owner fix requested below.
4. **Brief accuracy.** The advisor brief was accurate on the dead-end and stop
   condition. Two minor corrections from reviewers: (a) do **not** request route
   registration in the harness task — harnesses mount the component directly
   and BUILD-52 already owns the route, so asking risks duplicate work; (b) the
   brief's note that `test.fixme` fails `check-constraints.py` is imprecise
   (that script has no `fixme` rule), but AGENTS.md Coverage rules still forbid
   skipped tests, so the conclusion to avoid a `fixme` spec stands.
5. **No task tracker edits.** `tasks/`, MEMORY.md, WORKSPACE.md, BUGS.md,
   PROJECT.md, `docs/swarm`, `.opencode/`, `scripts/` and workflows were not
   touched, as required for a swarm worker.

## Lessons for workers

- Before writing a feature e2e spec, confirm a checked-in harness `.html` +
  `.tsx` pair exists for that feature. A single spec file cannot mount a real
  React page; `page.goto` needs a served entry HTML. If no harness exists,
  task-request it and stop instead of inventing a mount.
- An inline-module mount via `page.addScriptTag` is not a legitimate entry
  point (Vite does not rewrite bare specifiers in Playwright-injected scripts)
  and is a fake DOM in disguise. The harness pattern is the team's accepted,
  reviewable convention.
- Classify a failing gate before editing code: a red tripwire on an untouched
  tree at HEAD == `origin/main` is a pre-existing defect for the owner, not a
  defect to fix inside an unrelated one-file scope.
- When a new e2e spec is finally added for duplicates, update `currentSpecs` in
  the GAP tripwire in the same wave, or the frontend gate stays red.

## Why this is not finished

The task cannot be completed inside its declared scope.

- **What failed:** the reachability gate, not a command. Evidence:
  `grep -R "DuplicatesPage" apps/staff-web/src/features/*/routes.ts apps/staff-web/e2e`
  → no matches; `DuplicatesPage.test.tsx:405-409` asserts `/duplicates` is not
  registered; no `apps/staff-web/e2e/duplicatesflow/` harness or synthetic API
  exists and git history shows none ever existed. `bash apps/staff-web/check.sh`
  additionally fails on a pre-existing, unrelated spec-set tripwire at
  `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`
  (14 e2e specs on disk vs 9 pinned).
- **Most likely cause:** `RESP-E2E-FEATURE-*` stories were written assuming a
  per-feature browser harness exists. For admin and other flows it was added by
  a separate story (e.g. the admin harness landed in commit `4698687f`,
  BUILD-60). No equivalent story created a duplicates harness, so this story's
  one-file scope has no page to navigate to.
- **What I tried:** verified route registration, harness directory, git
  history, the provider-mount pattern in sibling specs, and the Vite dev
  server wiring; consulted all six named specialists. `aurevia-senior-react`
  specifically tested the inline-module escape hatch and rejected it as both
  technically brittle and a rule violation. No assertion was weakened, no
  `test.fixme` or skip was added, and no out-of-scope file was created.
- **Exact next step:** schedule the harness task in `task-requests` below; after
  it merges, re-dispatch this story (the scope can remain the single spec) and
  drive `DuplicatesPage` through
  `/e2e/duplicatesflow/duplicates-harness.html`, expanding the GAP tripwire's
  `currentSpecs` in the same wave so `bash apps/staff-web/check.sh` can pass.
  Route registration, if still desired for production navigation, belongs to
  BUILD-52 and is not needed for the harness-based e2e path.

```task-requests
[
  {
    "title": "Add a synthetic duplicates browser harness for staff-web e2e",
    "details": "Create a dev-only Vite harness pair plus API stub so a Playwright spec can mount the real DuplicatesPage: e2e/duplicatesflow/duplicates-harness.html (served at /e2e/duplicatesflow/duplicates-harness.html), duplicates-harness.tsx mounting DuplicatesPage under ThemeProvider > I18nProvider > ActiveOrganizationProvider with a locale switch, and synthetic-duplicates-api.ts stubbing /api/v1/duplicate-reviews/** via page.route. Reuse synthetic fixtures (alphaOrganizationId, syntheticPair, syntheticDetail, reviewOpen, secondReviewerSubject) and duplicateMessages.en/ar from src/features/duplicates; add a catch-all synthetic 404 and scope every intercepted call to alphaOrganizationId. Mirror e2e/pharmacyflow/. This unblocks RESP-E2E-FEATURE-DUPLICATES. Do not request route registration: harnesses mount the component directly and BUILD-52 owns /duplicates.",
    "scope": [
      "apps/staff-web/e2e/duplicatesflow/duplicates-harness.html",
      "apps/staff-web/e2e/duplicatesflow/duplicates-harness.tsx",
      "apps/staff-web/e2e/duplicatesflow/synthetic-duplicates-api.ts"
    ],
    "depends": []
  },
  {
    "title": "Reconcile the GAP e2e spec-set tripwire with main",
    "details": "The tripwire at tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166 pins currentSpecs to 9 e2e spec paths, but 14 exist on main. Add the five already-merged specs (adminflow/admin-journey.spec.ts, financeflow/finance-journey.spec.ts, pharmacyflow/pharmacy-bilingual-journey.spec.ts, radflow/radiology-bilingual-journey.spec.ts, therapyflow/therapy-journey.spec.ts) so bash apps/staff-web/check.sh is green again, and leave the tripwire active. When responsive-duplicates.spec.ts lands, add it to the same list in that wave.",
    "scope": [
      "apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"
    ],
    "depends": []
  }
]
```
