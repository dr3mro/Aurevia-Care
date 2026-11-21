- [x] RESP-MANIFEST · Staff web: web app manifest so the portal can be added to a home screen (no service worker)
  - pr: #535
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/public/manifest.webmanifest, apps/staff-web/tests/theme/manifest.test.ts
  - depends: RESP-DECISION
  - details:
    1. Read `apps/staff-web/public` and `docs/decisions/0004-responsive-staff-web.md`.
    2. Write `manifest.webmanifest`: name, short name, `display: standalone`, `start_url: "/"`, `scope: "/"`, `theme_color`, `background_color`, `dir: "auto"`, `lang: "en"`, no icons that do not exist in the repository. No service worker, no caching of clinical data and no offline mode: the portal must stay online-only.
    3. Test: valid JSON, required members, no `serviceWorker`, `start_url` is relative and holds no patient data.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

