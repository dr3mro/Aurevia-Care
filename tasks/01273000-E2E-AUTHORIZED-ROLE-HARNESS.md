- [ ] E2E-AUTHORIZED-ROLE-HARNESS · Prove WCAG 2.2 AA on the role-denied responsive routes with a synthetic authorized role
  - requirements: WEB-07, WEB-10, UX-01
  - scope: apps/staff-web/src/app/useRole.ts, apps/staff-web/src/fixtures/synthetic.ts, apps/staff-web/e2e/accessibilityScanResponsive.spec.ts
  - depends: RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE
  - details:
    Follow-up from issue #1603 and the merged PR #1602. Today
    `apps/staff-web/src/app/useRole.ts` returns the first synthetic role
    (reception), so the 16 routes that require a higher role render
    ForbiddenPage. Their responsive accessibility cells are `test.fixme` and
    the WCAG 2.2 AA verdict is therefore incomplete for WEB-10.
    Provide a synthetic authorized role plus endpoint-shaped API fixtures in
    the E2E harness, then enable those cells with the same per-route,
    per-project axe assertions used by the existing scans. Do not weaken a
    check or leave a suppression to make the gate pass. Every browser-backed
    route must name its route, project and measurement in a failure message.
    Verify with `bash apps/staff-web/check.sh` (typecheck, lint, 100% coverage)
    and a Playwright run on the exact SHA in CI if browsers are available.
    Record which routes and projects are actually proven and which remain
    blocked, with the tool evidence and the exact next step.
