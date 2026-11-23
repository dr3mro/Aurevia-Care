- [x] RESP-SUPPORT · Staff web: phones and tablets count as supported widths
  - pr: #530
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/src/features/system/browserSupport.ts, apps/staff-web/tests/features/system/browserSupport.test.ts
  - depends: RESP-DECISION
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/staff-web/src/features/system/browserSupport.ts`.

    1. Read `browserSupport.ts`, its test and `docs/decisions/0004-responsive-staff-web.md`. `MIN_SUPPORTED_WIDTH` is 768 today, so a phone gets the plain notice and no data.
    2. Lower the floor to 320 px and keep the browser-version rule. Add the mobile engines: iOS Safari and Chrome on iOS (they report `CriOS` and `FxiOS` on WebKit; judge them by the iOS version), Chrome on Android, Firefox on Android and Edge on Android, each within two majors of its baseline. Keep Internet Explorer and unknown engines unsupported. Update the doc comments.
    3. Update the test matrix: user agents for iPhone, iPad, Android phone and Android tablet at 320, 360, 390, 768, 820 and 1024 px are supported; a width of 319 px is not; old versions on each platform are not.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

