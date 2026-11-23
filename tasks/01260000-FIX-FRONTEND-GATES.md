- [x] FIX-FRONTEND-GATES · Stabilize Facilities RTL assertions and restore mobile lint startup.
  - pr: #1356
  - attempts: 1
  - requirements: WEB-07, UX-01, OPS-06
  - scope: apps/staff-web/tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx, apps/patient-mobile/eslint.config.mjs
  - details:
    Agent brief. Resume from the current claim and latest handoff evidence.
    Start from the declared scope and establish the real entry point before planning edits.
    Start with the existing assertions in `apps/staff-web/tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx` and add a failing case for the acceptance gap.
    Run `bash apps/staff-web/check.sh` and confirm 100% coverage on changed source files.
    Run `bash apps/patient-mobile/check.sh` and confirm 100% coverage on changed source files.

    Wait for asynchronously loaded rows before RTL assertions. Preserve Expo and TypeScript lint rules with one plugin registration.
  - evidence: Prior full staff-web logs show the RTL assertion failing on a table with an empty tbody. Awaiting both real fixture rows preserved the content and layout assertions. The frontend specialist confirmed the render timing and resolver fix.
  - evidence: Mobile ESLint 9.39.5 rejected duplicate plugin registration before the fix. Normalizing plugin identity and resolving Expo's installed TypeScript resolver preserved the rule presets and produced zero lint diagnostics. Mobile typecheck and Jest coverage passed (1,264 tests).
  - evidence: Staff-web typecheck, ESLint, generated API check and all 2,613 Vitest tests passed. Formatting, secret scanning and whitespace checks passed.
  - pending: Staff-web coverage fails at the unchanged 100% threshold for 19 source files. npm production audit was blocked by registry DNS. Repository fetch was blocked by read-only Git metadata.
  - follow-up evidence: At a09317a7 mobile lint reproduced an unused _shared binding. Copying the plugin map and deleting its duplicate key preserves the config without an unused binding. Mobile lint, typecheck and config formatting passed. The current Facilities test passed all 17 cases.
