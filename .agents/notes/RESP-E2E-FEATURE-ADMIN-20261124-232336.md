# RESP-E2E-FEATURE-ADMIN — Staff web end-to-end: the admin screens work on phone, tablet and desktop

## Summary

New synthetic browser acceptance spec for the four screens of the staff-web `admin` feature (facilities, staff, configuration, audit). Work in progress.

## Affected requirements

- WEB-07 — supported clients and responsive staff web down to compact widths; verified through the device matrix in `e2e/playwright.config.ts`.
- UX-01 — English and Arabic RTL, no hidden values, keyboard and touch reachability.

## Risk class

Cross-boundary (browser harness against the real shell, router, providers and versioned API boundary). No production, contract or data change.

## Review owner

Read-only specialists: aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Data changes

None. All identifiers and payloads in the synthetic boundary are opaque test values; no patient record, credential or real endpoint.

## Authorization boundaries and audit events

The browser substitutes the dev-only role source so the real tenant-admin screens mount; no production authorization changes and no audit events added.

## Migration and rollout considerations

None.

## Skills loaded

- `aurevia-delivery`: the story is a swarm worker task and must finish with a green staff-web check and reviewer verdicts. No additional skill matched the brief (new e2e spec only).

## Discovery evidence (tool)

- `git merge --no-edit origin/main`: `Already up to date.` Branch `swarm/RESP-E2E-FEATURE-ADMIN-1552`.
- `apps/staff-web/e2e/playwright.config.ts`: nine Chromium device projects (360–1920 px), plus installed WebKit/Firefox variants; base URL injectable through `E2E_BASE_URL`.
- `src/app/useRole.ts` fixes the shell role to synthetic `reception`; `canAccessRoute` denies `/admin/*` for that role. `e2e/darkModeResponsive.spec.ts:144-153` substitutes the dev-only role module with `page.route('**/src/app/useRole.ts*')`, the precedent for reaching these screens without editing production code.
- `e2e/adminflow/admin-harness.tsx` mounts only `ExportPage`; it cannot host the four admin screens.
- `main.tsx` rejects Playwright's `HeadlessChrome` token; a synthetic current-Chrome UA is required (precedent `rightToLeftResponsive.spec.ts:98-101`).
- Playwright browser binaries were absent in this image; `npx playwright install chromium` downloaded Chromium 159.0.8154.6 and the headless shell into `/github/home/.cache/ms-playwright` so the matrix can be exercised locally.

## Test evidence

_Pending._

## Coverage

_Pending._

## Review verdicts

_Pending._

## Findings

_Pending._

## Lessons for workers

_Pending._

## Why this is not finished

_Pending only if the task stops early._
