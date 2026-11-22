- [x] RESP-APP-CONFIG · Patient app: enable tablets and rotation in the Expo configuration
  - pr: #557
  - attempts: 1
  - requirements: PAT-01, PAT-02, UX-01
  - scope: apps/patient-mobile/app.json
  - depends: RESP-APP-DECISION
  - details:
    1. Read `apps/patient-mobile/app.json` and `docs/decisions/0005-patient-app-tablets.md`.
    2. Set `ios.supportsTablet` to true, `orientation` to `default`, keep every other key, and add `ios.requireFullScreen: false` so split view and slide over work. Do not add a plugin or a dependency.
    3. Run the JSON through `python3 -m json.tool`, then `bash apps/patient-mobile/check.sh`, and paste the results.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

