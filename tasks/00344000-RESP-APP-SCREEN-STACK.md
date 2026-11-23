- [x] RESP-APP-SCREEN-STACK · Patient app: tablet, landscape and large-text layout for `ScreenStack` (app)
  - pr: #610
  - attempts: 1
  - requirements: PAT-01, PAT-02, UX-01
  - scope: apps/patient-mobile/src/app/ScreenStack.tsx, apps/patient-mobile/src/app/ScreenStack.tablet.test.tsx
  - depends: RESP-APP-LAYOUT-HOOK, RESP-APP-CONFIG
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/patient-mobile/src/app/layout/useWindowClass.ts`, `apps/patient-mobile/src/app/ScreenStack.tsx`.

    1. Read the component, its existing test, `docs/decisions/0005-patient-app-tablets.md` and `src/app/layout/useWindowClass.ts`.
    2. Make it work on compact, regular and large windows, portrait and landscape, with font scale 1.0, 1.5 and 2.0: content has a maximum readable width and is centred on large windows; lists and detail use two panes on large windows where the screen is a list plus a detail (inbox and thread, appointments and detail, reports and detail); touch targets are at least 44 by 44 points; nothing is clipped at 200 percent text; safe-area insets are respected; focus order and screen-reader labels are unchanged; right-to-left mirrors; no persistent clinical cache is added (PAT-02).
    3. Add a new test file (named in `scope`) that renders the component at each window class and font scale and asserts the structure, the accessibility labels and the right-to-left case. Existing tests must still pass.
    4. Do not change behaviour, text, API calls or exported names, and do not add a dependency.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

