- [x] RESP-APP-LAYOUT-HOOK · Patient app: window-size layout hook (new files)
  - pr: #561
  - attempts: 1
  - requirements: PAT-01, PAT-02, UX-01
  - scope: apps/patient-mobile/src/app/layout/useWindowClass.ts, apps/patient-mobile/src/app/layout/useWindowClass.test.ts
  - depends: RESP-APP-DECISION
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/patient-mobile/src/app/layout/useWindowClass.ts`.

    1. Read `apps/patient-mobile/src/app` and `docs/decisions/0005-patient-app-tablets.md`.
    2. Add `useWindowClass()` using React Native `useWindowDimensions`: `compact` below 600, `regular` 600 to 839, `large` 840 and above, plus `isLandscape`, `fontScale` and safe-area insets (use the existing safe-area library if the app already has one; otherwise take insets as an optional argument, add no dependency). Export a pure `windowClassFor(width)`.
    3. Tests: boundary widths 599, 600, 839 and 840; landscape flag; the hook reacts to dimension changes.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

