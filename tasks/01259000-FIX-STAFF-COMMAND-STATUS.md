- [x] FIX-STAFF-COMMAND-STATUS · Preserve lab success notices during queue reload and stabilize pharmacy detail assertions.
  - evidence: Git history confirms PR #1350 merged into main at c36d04d1.
  - pr: #1350
  - attempts: 1
  - requirements: LAB-01, RX-02
  - scope: apps/staff-web/src/features/labcollection, apps/staff-web/src/features/pharmacyinbox/PharmacyItemPage.test.tsx
  - details:
    Agent brief. Resume from the current claim and latest handoff evidence.
    Current starting points to inspect are `apps/staff-web/src/features/labcollection/CollectionDeskPage.tsx`.
    Start with the existing assertions in `apps/staff-web/src/features/pharmacyinbox/PharmacyItemPage.test.tsx` and add a failing case for the acceptance gap.
    Run `bash apps/staff-web/check.sh` and confirm 100% coverage on changed source files.

    Confirm collection and rejection after refreshing to an empty queue. Verify pharmacy flags after the detail reload commits.
  - evidence: Both lab tests failed before the fix with an explicitly empty reload. The staff React specialist reviewed the fallback announcement and pharmacy assertions. Focused Vitest with V8 passed 50 tests and reported 100% statements, branches, functions and lines for CollectionDeskPage.tsx. The page tests cover the no-rows branch with a success message, initial empty state with no message, and the normal rendered screen with rows.
  - evidence: The pharmacy tests still verify command routing and request data and now wait for both domain flags after the refreshed detail renders.
  - evidence: Staff-web typecheck, ESLint, Prettier and the secret scan passed locally.
  - pending: Full coverage run passed 2,612 of 2,613 tests. The separate Facilities page RTL test failed. npm audit could not resolve registry.npmjs.org, so the dependency scan is unverified.
