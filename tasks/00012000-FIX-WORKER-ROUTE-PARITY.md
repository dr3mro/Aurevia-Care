- [x] FIX-WORKER-ROUTE-PARITY · Block newly introduced API route gaps in swarm integration
  - pr: #1445
  - attempts: 1
  - requirements: OPS-06, WEB-05, ARC-04
  - scope: scripts/worker_gate.py, scripts/worker-check.sh, scripts/tests, apps/api/src/main/java/com/aurevia/smartclinic/it/contract, apps/api/src/test/java/com/aurevia/smartclinic/it/contract, contracts/openapi/missing-routes.txt
  - depends: FIX-WORKER-REPORTS
  - details:
    Agent brief. Maintainer briefing. Start in `scripts/worker_gate.py` at `related` and `decide`, existing cases in `scripts/tests/test_worker_gate.py`, and `OpenApiRouteParityTest#everyControllerRouteIsDocumentedOrBaselined`. The parity test lives under `src/test/java/com/aurevia/smartclinic/it/contract`.
    Write the red case for a controller-only diff and a failing parity report. Add separate assertions for baseline growth, a documented candidate, baseline shrinkage, and missing or unusable pinned-base evidence. Keep report handling for unrelated suites intact. A pre-existing parity exception must identify the exact method and normalized path.
    Protected tooling requires a maintainer. Verify Python line and branch coverage for the policy and exercise the real Spring parity suite plus the generated client check for the documented case.

    worker_gate.decide currently accepts failing OpenApiRouteParityTest with MailController.java as the changed file because names do not match. It does not compare the candidate to the base.
    Treat newly introduced parity failures as blocking, including route changes inside controllers and additions to the missing-route baseline. If allowing pre-existing parity failures, require exact route-level evidence from a pinned base and fail closed without it. Keep the normal merge gate.
    Acceptance: a synthetic candidate adding one undocumented route fails even with existing debt, adding it to the baseline still fails, adding its contract and generated client passes, and a documented repair shrinking debt can proceed. Run Python and Java coverage for affected branches. This touches protected tooling and needs maintainer review.
