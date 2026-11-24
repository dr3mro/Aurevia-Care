# GitHub verification checklist for the swarm-quality changes

- Status: **proposed checklist — nothing here is verified.** Every box is unchecked.
- Date: 2026-11-23
- Requirement IDs: OPS-06
- Source tasks: WQUALITY-01 through WQUALITY-06 in [tasks/](../../../tasks/). All six are merged as script, test or documentation changes, but none of the GitHub behaviour described below has been exercised for this checklist.
- Evidence for this checklist: file reads of the six task rows, the six changed symbols and their tests, the pending OPS-WORKER-QUALITY wording, the sibling [GitHub verification checklist](github-verification-checklist.md), the [failure classification guide](../../swarm/failure-classification.md), and the job names in the Delivery workflow. No GitHub workflow was run, no log was produced, no reviewer or human participant was involved. All values in the examples below are synthetic (`<owner>/<repo>`, `<sha12>`, `g<run>-<sha12>`, `<digest>` and token-shaped placeholders); there is no real SHA, credential, patient datum or release authorization in this document.

## Purpose and rules

This checklist is the hand-off from the six swarm-quality workers to a person with workflow-file permission and a GitHub token. It names the exact workflow or command that exercises each merged change, describes what green looks like, records the observation that proves the change works, and gives the rollback. It also carries the Docker image rebuild and exact-SHA checks that the audit still lists as pending.

Rules for the person performing it:

1. Change only the files named by a change's rollback; the six changes are already merged. Confirm each pull request's head or merge commit is an ancestor of the SHA under test instead of re-applying a patch.
2. Use a synthetic task, a synthetic draft pull request and a synthetic fix pull request. Never use real patient data, a credential or a production release. Nothing here authorizes a release; a release tag needs the separate authority in [README-SETUP.md](../../../.opencode/config/README-SETUP.md).
3. Record the workflow run URL, exact SHA and proving observation in the result table before ticking a box; record any image digest in that row's Proving observation cell (the table has no digest column). A local command, a stubbed dry run, a simulated review and this document's file reads are **not** GitHub verification and must not tick a box.
4. Run the required checks on the exact SHA. The required checks are `Policy and tooling`, `Delivery tooling tests`, `Frontend standards`, `Java and database verification`, `Developer reference`, the `amd64` and `arm64` matrix legs of `Verify and smoke image`, and `Delivery gate`. The workflow declares the job once with a matrix; the leg names are displayed with the architecture in parentheses, and the `arm64` leg runs on tag events only. Never force-push or bypass a protection. Workflow files and `scripts/` are protected: only a person performs a rollback.
5. Where a change has no dedicated workflow job today, the tooling unit tests are the CI evidence and the end-to-end observation is explicitly listed as pending. Do not rename a job or invent a check to close that gap.
6. If a prerequisite is missing, leave its box unchecked and write the missing item in the result table.

## Verification items

### WQUALITY-01 — tell failed, pending, missing and approval-required Delivery apart (merged script change)

- [ ] **Merged tooling change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_delivery_state.py`, PR `#673`.

  **Apply.** No apply step. Confirm the pull request is an ancestor of the SHA under test and that `delivery_state`, `delivery_gate`, `delivery_runs` and `markdown_only` are present in `scripts/swarm.py`. WQUALITY-02 depends on `delivery_state`, so verify this change first.

  **Workflow on GitHub.** `Delivery` → `Policy and tooling` and `Delivery tooling tests`. The end-to-end behaviour is observed through `Agent swarm` and `Swarm integrator` while judging a synthetic pull request, and by reading the comment or issue the integrator opens.

  **Green looks like.** Both jobs succeed and the tooling tests report `OK` with no `FAILED`. On GitHub a current-head `passed` run lets the integrator merge; `pending` waits with no label; `failed` goes to the fix workers; `missing` and `approval-required` ask for a fresh run and never merge.

  **Proving log line.** In the integrator comment on a synthetic pull request: `Delivery is <state> for <sha12>; a fresh run was requested on this branch. Nothing merges until it reports passed.` The `<state>` is one of `passed`, `failed`, `pending`, `missing` or `approval-required`; a failing run carries the label `Delivery failed on the current head`. In the tooling tests: `test_pending_waits_without_a_label`, `test_missing_code_change_requests_a_run_and_never_merges` and `test_approval_required_requests_a_run_and_never_merges`.

  **Negative control.** Open a synthetic draft pull request whose branch has a Delivery run only for an older SHA: `delivery_state` reports `missing`, so the integrator must request a fresh run and not merge. Cancel another synthetic run: cancelled counts as `failed` and goes to the fix workers. A run waiting for a first-time contributor or an environment approval must be `approval-required` and never merge. A skipped Markdown-only run follows the documented documentation exception; a skipped code change does not. These are person-run negative controls, not evidence produced by this checklist.

  **Rollback.** Revert PR `#673` in `scripts/swarm.py` and its test file on a branch with workflow permission, then re-run the two jobs and a synthetic integrator pass to confirm the previous coarse policy returns. No stored state is unwound. The task row records that the integrator opens an oversight issue for merging a protected script file; read that issue before merging the revert.

### WQUALITY-02 — recover ordinary draft pull requests (merged script change)

- [ ] **Merged tooling change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_drafts.py`, PR `#816`. Depends on WQUALITY-01.

  **Apply.** No apply step. Confirm `draft_recovery`, the decisions `recover`, `fix` and `leave`, and `RECONCILER_TITLE` are present in `scripts/swarm.py`.

  **Workflow on GitHub.** `Delivery` → `Delivery tooling tests`. The end-to-end proof comes from `Agent swarm` judging a set of synthetic drafts: an ordinary passing draft, a still-failing draft, a reconciler instruction draft, a labelled draft and a repair draft.

  **Green looks like.** The ordinary draft with Delivery `passed` and no `needs-fix` or `needs-human` label is marked ready for review and queued. The still-failing draft is sent to one fix worker for its head SHA and then reaches `needs-human`. The reconciler, repair, labelled and still-waiting drafts remain drafts.

  **Proving log line.** The draft pull request changes state from draft to ready for review with no blocking label, and the plan step queues it. For a failing draft the integrator comment reads `Draft pull request: local checks failed` and no fix worker is duplicated for the same head. In the tooling tests: `test_recoverable_draft_is_marked_ready_and_queued`, `test_still_failing_draft_goes_to_a_fix_worker`, `test_reconciler_draft_is_left_for_a_person` and `test_labelled_draft_is_untouched`.

  **Negative control.** A synthetic draft whose branch has only an older-SHA Delivery run must be left alone, not recovered, because stale evidence is not `passed`. A draft titled `instruction updates for review` stays a draft for a person. A draft carrying `needs-fix` or `needs-human` is skipped before recovery. These are person-run negative controls.

  **Rollback.** Revert PR `#816` and its test file; ordinary drafts are no longer auto-recovered and a fix worker must undraft them explicitly. Re-run the tooling tests and one synthetic `Agent swarm` pass. Read the integrator's oversight issue for this protected script change before merging the revert.

### WQUALITY-03 — validate scope and structured hand-offs on the pinned candidate (merged script change)

- [ ] **Merged tooling change.** Source: `scripts/worker_gate.py`, `scripts/tests/test_worker_gate.py`, PR `#403`.

  **Apply.** No apply step. Confirm `validate_candidate` and `candidate_from_env` are present and that the rules `scope`, `tracker`, `notes-heading`, `notes-empty`, `notes-placeholder`, `notes-unfinished` and `candidate-changed` are reported.

  **Workflow on GitHub.** `Swarm worker run` has no dispatch trigger, so a synthetic `Agent swarm` run exercises the gate on a real worker candidate through the `Swarm worker` job and its `CHECK_SCRIPT` path into `worker-check.sh`, not a direct workflow command. `Delivery` → `Policy and tooling` and `Delivery tooling tests` cover lint and unit cases. Read the worker-gate stdout in the worker job.

  **Green looks like.** A clean pinned candidate prints an empty violation list and the worker publishes its pull request. A candidate with a violation exits non-zero and no pull request is published.

  **Proving log line.** Clean candidate: `{"violations": []}`. A file outside the declared scope produces `{"violations": [{"file": "<path>", "rule": "scope"}]}`; a candidate whose HEAD moved after validation produces `{"violations": [{"file": "", "rule": "candidate-changed"}]}`. In the tooling tests: `test_clean_candidate_passes`, `test_file_outside_scope_is_reported`, `test_every_tracker_file_is_reported` and `test_candidate_sha_mismatch_is_reported`.

  **Negative control.** In a synthetic worker candidate, change a file outside the scope line to get `scope`; change tasks/ to get `tracker`; remove `## Test evidence` or `## Coverage` to get `notes-heading`; leave a `<placeholder>` in a section to get `notes-placeholder`; stop early without `## Why this is not finished` to get `notes-unfinished`; move HEAD between sampling points to get `candidate-changed`. Each must fail the gate. These are person-run negative controls; no tracker file is edited by this checklist.

  **Rollback.** Revert PR `#403` and its test file on a branch with workflow permission; the worker gate stops rejecting moved candidates and malformed hand-offs. Re-run the tooling tests and one synthetic worker pass. Read the integrator's oversight issue for this protected script change before merging the revert.

### WQUALITY-04 — add production dependency scanning to the frontend check (merged script change)

- [ ] **Merged tooling change.** Source: `scripts/frontend-check.sh`, `scripts/tests/test_frontend_check.py`, PR `#383`.

  **Apply.** No apply step. Confirm the `npm audit --omit=dev --audit-level=high` step and the explicit `FRONTEND_AUDIT=skip` branch are present in `scripts/frontend-check.sh`. No `package.json` or lock file was changed by this task.

  **Workflow on GitHub.** `Delivery` → `Frontend standards` runs the shared check for both frontend apps; `Policy and tooling` covers shell lint and `Delivery tooling tests` covers the stubbed `npm` cases.

  **Green looks like.** Both frontends pass typecheck, lint and tests and the production audit is clean. A finding in development dependencies only is printed as a warning and does not fail the job.

  **Proving log line.** A high or critical production finding prints `FAIL <module>: high or critical vulnerability in production dependencies`; a development-only finding prints `WARN <module>: high or critical vulnerability in development dependencies only (does not fail the gate)`; a registry outage prints `INFRASTRUCTURE <module>: npm audit could not reach the registry (failure, not a pass)`; an explicit local skip prints `NOTICE FRONTEND_AUDIT=skip: npm dependency audit DISABLED; high/critical vulnerabilities in production dependencies will NOT be detected. Use only for local development.` In the tooling tests: `test_high_finding_fails`, `test_moderate_finding_passes`, `test_dev_only_finding_warns`, `test_registry_error_is_infrastructure` and `test_skip_prints_notice_and_skips_audit`.

  **Negative control.** With a stub `npm` on `PATH`, a high production finding fails the job, a moderate finding passes, a dev-only finding warns, and a registry error is reported as infrastructure rather than a pass. `FRONTEND_AUDIT=skip` is for local development only; an enforced skip in Delivery would void the gate and must not be used to make a red run green. These are person-run negative controls using synthetic findings.

  **Rollback.** Revert PR `#383` and its test file; the audit step is removed and the Java OWASP gate remains the dependency check. Re-run `Frontend standards` and the tooling tests. Read the integrator's oversight issue for this protected script change before merging the revert.

### WQUALITY-05 — validate the runtime OpenCode profile separately (merged script change)

- [ ] **Merged tooling change.** Source: `scripts/gen-opencode.py`, `scripts/tests/test_opencode_profile.py`, PR `#412`.

  **Apply.** No apply step. Confirm `--check-runtime`, `check_runtime`, the runtime profile path `.opencode/opencode.ci.json` and the model allowlist `.opencode/model-pricing.json` are present. The existing `--check` behaviour is unchanged. The runtime profile no longer reads a model from `.opencode/config/slots.json`: slot images pin no model and the router selects one at run time.

  **Workflow on GitHub.** `Delivery` → `Delivery tooling tests` runs the tooling tests that execute `--check-runtime` against both temporary profiles and the repository profile. The local command is `python3 -I scripts/gen-opencode.py --check-runtime`. No workflow job calls this flag directly in the tree today; the end-to-end proof that a worker image loads the validated profile is part of the pending Docker image rebuild below. Do not add a job name that does not exist.

  **Green looks like.** The command exits 0 with no output and the repository profile passes. A drift prints a violation naming the offending agent or the profile.

  **Proving log line.** A clean run prints nothing. A drift prints one of `agent <name>: read-only reviewer has edit permission`, `agent <name>: editing agent has no bash permission list`, `agent <name>: write permission targets <protected> via <pattern>`, `runtime profile: instruction file <path> does not exist`, `<label>: model '<model>' is not allowed by .opencode/model-pricing.json` or `agent <name>: contains a <label>`. In the tooling tests: `test_repository_runtime_profile_passes`, `test_reviewer_with_edit_permission_is_reported`, `test_agent_writing_protected_paths_is_reported`, `test_check_runtime_flag_passes_on_repository_profile` and `test_check_runtime_flag_reports_violations_and_fails`.

  **Negative control.** Copy the profile and break one rule at a time in a temporary fixture: a reviewer with an allow rule, an editing agent with no bash list, a write rule reaching a protected path, a missing instruction file, a model absent from `model-pricing.json` and a token-shaped value. Each must fail with the message naming the agent. A clean generated `.opencode/opencode.json` under `--check` must not excuse a bad runtime profile. These are person-run negative controls; no real token is written anywhere.

  **Rollback.** Revert PR `#412` and its test file; the runtime profile is no longer validated independently of the generated config. Re-run the tooling tests and one synthetic worker run after the image rebuild. Read the integrator's oversight issue for this protected script change before merging the revert.

### WQUALITY-06 — classify swarm pull request failures and name the catching layer (merged function, guide and tests)

- [ ] **Merged tooling and documentation change.** Source: `scripts/failure_classes.py`, `scripts/tests/test_failure_classes.py`, [failure-classification.md](../../swarm/failure-classification.md), PR `#667`.

  **Apply.** No apply step. Confirm `classify`, the five classes and the five catch layers are present, and that the guide contains the empty review-record table. The classifier is deterministic, offline and side-effect free.

  **Workflow on GitHub.** `Delivery` → `Delivery tooling tests` runs the fixture-per-class tests; `Developer reference` builds the guide; `Policy and tooling` checks links and requirement IDs. No dispatch workflow calls the classifier directly in the tree today, so this is unit-level CI evidence plus a person review of the guide.

  **Green looks like.** The tooling tests report `OK`, each synthetic fixture maps to its own class and catch layer, the developer-reference artifact contains the guide, and the review-record table is still empty.

  **Proving log line.** A parity fixture returns `{"class": "check-parity", "catch_layer": "integrator-gate"}`; an unrecognised log returns `{"class": "implementation", "catch_layer": "unit test"}`. The layers are `task template`, `worker-check`, `integrator-gate`, `unit test` and `contract test`. In the tooling tests: `test_a_fixture_log_returns_its_class_and_catch_layer`, `test_every_class_has_one_catch_layer_and_no_layer_is_unused`, `test_policy_signals_win_over_an_ordinary_assertion_failure`, `test_parity_signals_win_over_a_coverage_signal` and `test_empty_and_missing_inputs_default_without_raising`.

  **Negative control.** A log with a policy signal wins over an ordinary assertion failure; a parity signal wins over a coverage signal; empty or missing inputs default to `implementation` without raising; the classifier imports no network client. After five swarm pull requests, a person applies the review rule in the guide, classifies the failures and adds one regression at the layer that should have caught each new class, then records the row in the table. This is a person-run review, not evidence produced by this checklist.

  **Rollback.** Revert PR `#667`, its test file and the guide; the five-class review rule is no longer documented or tested. Re-run the tooling tests and the developer reference build. Read the integrator's oversight issue for this protected script change before merging the revert.

## Pending baseline prerequisites

- [ ] **Docker image rebuild.** OPS-WORKER-QUALITY in [tasks/](../../../tasks/) records that the Docker image rebuild and exact-SHA GitHub checks are pending. The local patch isolates the test fixture and provisions isolated Python imports in the worker image and hosted runner setup; the worker image must be rebuilt so those changes are present, then one synthetic worker run must show no fixture leakage and a working `yaml` import. Docker is unavailable locally, so this is a GitHub-only step. The result table stays unfilled and its digest reference is a synthetic placeholder until a person runs the rebuild; that person then records the real image digest in the row's Proving observation cell. No production release is authorized here.

- [ ] **Exact-SHA GitHub checks.** Run the affected workflows on the exact SHA and confirm `Delivery`, the dispatchable workflows `Agent swarm`, `Swarm slot images` and `Swarm integrator`, and `Fix PR gate` on a synthetic fix pull request are green as applicable. `Fix PR gate` is pull-request-event only and must be reached by opening a synthetic fix pull request; it cannot be dispatched by hand. Record each run URL and exact SHA in the result table.

- [ ] **Integrator oversight issues.** Each of WQUALITY-01 through WQUALITY-06 records that the integrator opens an oversight issue when merging a protected `scripts/` file. Read the issue opened for each PR and confirm it records the reviewed exception. This is a person read of GitHub state; no issue was created by this checklist.

## Final gate

- [ ] **Clean `Delivery` run on the final `main` commit.** Dispatch `Delivery` on the final SHA and confirm every required check listed in [Purpose and rules](#purpose-and-rules) is green. Record the run URL and SHA in the result table. Nothing is verified until this run is green.

## Result table

All cells are intentionally unfilled; a person fills them after running each workflow.

| Task | Date | Run URL | Exact SHA | Proving observation | Result |
|---|---|---|---|---|---|
| WQUALITY-01 | not run | not run | not run | not run | unchecked |
| WQUALITY-02 | not run | not run | not run | not run | unchecked |
| WQUALITY-03 | not run | not run | not run | not run | unchecked |
| WQUALITY-04 | not run | not run | not run | not run | unchecked |
| WQUALITY-05 | not run | not run | not run | not run | unchecked |
| WQUALITY-06 | not run | not run | not run | not run | unchecked |
| Pending Docker image rebuild | not run | not run | not run | not run | unchecked |
| Pending exact-SHA checks | not run | not run | not run | not run | unchecked |
| Integrator oversight issues | not run | not run | not run | not run | unchecked |
| Final `Delivery` | not run | not run | not run | not run | unchecked |

## Findings for the person

1. Nothing in this checklist has been run on GitHub. The evidence behind it is file reads of the six task rows, the changed symbols, their tests and the sibling checklist; there is no GitHub run URL, log line produced by a run, simulated user, human participant or clinical evidence.
2. WQUALITY-02 consumes `delivery_state` from WQUALITY-01, so confirm WQUALITY-01 before judging a draft with WQUALITY-02.
3. `--check-runtime` and `failure_classes.classify` are exercised by Python unit tests and local commands in the tree today; no workflow job invokes either directly. Treat the tooling tests as the CI evidence and the worker-image behaviour as pending the Docker rebuild. Do not add a job name that does not exist in the Delivery workflow.
4. `FRONTEND_AUDIT=skip` is for local development. An enforced skip in Delivery would silently remove the production dependency gate from WQUALITY-04.
5. All six changes touch protected `scripts/` files; their task rows say the integrator opens an oversight issue. Confirm those issues exist before treating the merges as reviewed, and read each one before merging a revert.
6. The draft-fails, draft-passes, stale-SHA and integrator-issue cases in the sections above are documented negative controls for a person to run. They are not executed evidence and must not tick a box.

## Approval

Not applied and not verified. Every box remains unchecked until a person with workflow and script-file permission performs the GitHub steps above and records the evidence. The evidence behind this checklist is file reads and the repository checks named in the source notes; there is no simulated-user, human-participant or clinical evidence, and no GitHub run was performed by this worker.
