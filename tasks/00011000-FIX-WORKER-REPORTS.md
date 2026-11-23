- [x] FIX-WORKER-REPORTS · Use fresh integration test reports for each swarm gate invocation
  - pr: #680
  - evidence: PR #680; integrator ran scripts/repair-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/FIX-WORKER-REPORTS.md; simulated review only
  - attempts: 1
  - requirements: OPS-06
  - scope: scripts/worker-check.sh, scripts/worker_gate.py, scripts/tests
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `scripts/worker_gate.py`, `scripts/worker-check.sh`.

    worker-check.sh runs Maven then worker_gate.py reads the shared Surefire directory. Earlier failing XML can affect the verdict of a later run.
    Give each run its own report directory and pass it explicitly to the report reader. A nonzero build with absent or unusable fresh results must fail. Preserve the explicit repair-task handling for valid fresh results.
    Acceptance: inject stale failing XML and prove it has no effect on a successful run or a compile/setup failure. Assert fresh valid failures still reach the classifier. Run shell lint and the Python branch-coverage gate. This touches protected delivery tooling and needs maintainer review.
