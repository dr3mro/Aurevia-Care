# GitHub verification checklist for the swarm quality changes

- Status: **proposed checklist — nothing here is verified.** Every box is unchecked.
- Date: 2026-11-22
- Requirement IDs: OPS-06, WEB-12
- Source tasks: WQUALITY-01 through WQUALITY-06 (merged script and documentation changes) and WQUALITY-07 (this checklist). The two pending audit checks come from OPS-WORKER-QUALITY (`TODO.md:14916-14924`, with the pending note at `:14922` and its context at `:14919`); the digest-pin finding AUD-20261121-18 (PR `#477`) is a related prerequisite for a reproducible rebuild, not the source of the pending phrase.
- Evidence for this checklist: file reads of the six changed scripts or guides, their task rows in `TODO.md`, their notes under `.agent/pr-notes/`, and the sibling [GitHub verification checklist](github-verification-checklist.md). No GitHub workflow was run, no log was produced, no reviewer or human participant was involved. All example values are synthetic (`owner/repo`, `v0.1.0-rc.<n>`, `g<run>-<sha12>`, `<sha12>`, `<digest>`). This document does not authorize a release.

## Purpose and rules

This checklist is the hand-off from the quality workers to a person who has workflow-file permission and a GitHub token. It orders the six quality changes and the two still-pending audit checks, names the workflow that proves each, describes green, records the log line that proves the change works, and gives a rollback.

Rules for the person performing it:

1. Change only workflow files by applying an approved patch; do not hand-edit a proposed workflow. The six quality changes are already merged as script or documentation changes — confirm their commits are on `main` rather than re-applying them. Scripts and guides are protected files: only a person rolls them back.
2. Use a synthetic task, a synthetic swarm pull request, a synthetic draft, a synthetic fix pull request and a synthetic pre-release tag. Never use real patient data, a real credential or a production release. A release tag requires the separate release authority in [README-SETUP.md](../../../README-SETUP.md); this document does not authorize a release.
3. Record the workflow run URL, the exact SHA, the proving log line and any image digest in the result table before ticking a box. A local `git apply --check`, a stubbed dry run, a pytest fixture or a simulated review is **not** GitHub verification and must not tick a box. A stubbed or scratch-copy run may serve as the deterministic red proof that a check can fail, but it never supplies the green evidence that ticks a box.
4. Run the required checks on the exact SHA. Required checks are `Policy and tooling`, `Delivery tooling tests`, `Frontend standards`, `Java and database verification`, `Developer reference`, both `Verify and smoke image` jobs and `Delivery gate`. Never force-push or bypass a protection. Workflow files are protected: only a person applies or rolls back these patches.
5. If a step cannot be performed because a prerequisite is missing, leave its box unchecked and write the missing item in the result table.
6. Keep every example synthetic. Do not put a token, a real image digest, a real identifier or patient data into a pull request, an issue, a log or a tracker. The placeholder `<token>` stands for any credential; if a real-looking value appears, mask it before recording evidence.

## Apply order and dependencies

1. **Confirm the merged quality baseline** (WQUALITY-01 through WQUALITY-06) is on `main` with green `Delivery` checks on its exact SHAs. There is no apply step for these six changes. WQUALITY-02 depends on WQUALITY-01's `delivery_state`, and WQUALITY-06 depends on WFAUDIT-06's `diagnose`; verify both predecessors are present before proving their dependants.
2. **Prove the integrator and worker changes first** (WQUALITY-01, WQUALITY-02, WQUALITY-03), because the remaining scenarios use a synthetic swarm pull request that must be correctly accepted or rejected by those gates.
3. **Prove the tooling checks** (WQUALITY-04, WQUALITY-05) through the `Delivery` jobs that call them; WQUALITY-05's `--check-runtime` is independent of the generated-config `--check` and must be observed separately.
4. **Prove the classification review** (WQUALITY-06) only after five swarm pull requests have been judged, using the review rule in [failure-classification.md](../../swarm/failure-classification.md).
5. **Finish with the two audit-pending checks** — the Docker image rebuild and the exact-SHA verification — then one clean `Delivery` run on the final `main` commit. Record every run URL in the result table.

## Verification items

### WQUALITY-01 — distinguish Delivery states in the integrator (script change)

- [ ] **Merged tooling change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_delivery_state.py`, PR `#673`, commit `f48b8b7`.

  **Apply.** No apply step. Confirm `delivery_state(runs, head_sha)`, `delivery_gate(pr, head, runs)` and `delivery_runs(head)` are present on `main`, and that the trusted integrator passes `GITHUB_ACTIONS == "true"` so the Delivery evidence is required.

  **Workflow on GitHub.** Dispatch `Swarm integrator` with one ready synthetic pull request whose head SHA has a green `Delivery` run, then a second whose only green run is for an older SHA, then a third whose current-head run was cancelled, and a fourth whose job waits on a first-time-contributor approval. Inspect the PR comments and labels after each dispatch; inspect `Delivery` on each exact SHA.

  **Green looks like.** The first pull request merges (decision `merge`). The older-SHA pull request is not merged: the integrator comments and asks for a fresh run (decision `request`). The cancelled run is treated as failed and the pull request goes to the fix workers (decision `fix`). The approval-gated run is not merged and a fresh run is requested (decision `request`). A queued or running run leaves the pull request untouched with no label (decision `wait`). A documentation-only pull request with no run merges through the documented local-docs exception.

  **Proving log line.** In the integrator output: `Delivery is missing for <sha12>; a fresh run was requested on this branch. Nothing merges until it reports passed.` Only the current-head `success` conclusion yields `merge`; `failed`, `pending`, `missing` and `approval-required` yield `fix`, `wait`, `request` and `request` respectively.

  **Negative control.** A green run whose `headSha` is not the pull request head must be reported as `missing`, never `passed`, and must not merge. A re-run that succeeds supersedes an earlier failure. A skipped run is not evidence on its own; the docs-only exception is applied from the changed files, not from the run label.

  **Rollback.** Revert commit `f48b8b7` only after reverting WQUALITY-02 (it calls `delivery_state`). The integrator stops requiring current-head Delivery evidence; no stored state is changed. Workflow files are protected: a person performs the revert.

### WQUALITY-02 — recover ordinary passing swarm drafts (script change)

- [ ] **Merged tooling change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_drafts.py`, PR `#816`, commit `bbaf144`. Depends on WQUALITY-01.

  **Apply.** No apply step. Confirm `draft_recovery(pr_info, runs)`, the constants `DRAFT_RECOVER`, `DRAFT_FIX`, `DRAFT_LEAVE` and `RECONCILER_TITLE`, and that `plan_integration` calls `gh pr ready` for a recoverable draft are present on `main`.

  **Workflow on GitHub.** Dispatch `Agent swarm` (or `Swarm integrator`) with four synthetic open drafts labelled `swarm`: an ordinary story draft whose head has a green exact-SHA Delivery run and no `needs-fix` or `needs-human` label; a second whose current-head checks still fail; a reconciler draft titled `instruction updates for review`; and a fourth carrying `needs-human`. Watch the plan step and the pull requests.

  **Green looks like.** The first draft is marked ready for review (`gh pr ready`) and queued; the second is sent to one fix worker for that head SHA; the reconciler and the labelled draft stay drafts and are not queued.

  **Proving log line.** The plan step returns `recover` for the passing ordinary draft and `fix` for the failing one; the failing draft carries the comment `Draft pull request: local checks failed`. The reconciler and blocked drafts return `leave` and are never queued. The observable GitHub signals are a draft that becomes ready for review and enters the integration queue, a failing draft that gets one fix pass and that comment, and drafts that remain drafts with no fix pass.

  **Negative control.** A still-failing draft is given one fix attempt per head SHA and then reaches `needs-human`; it is never undrafted by the plan. A reconciler instruction draft or a draft with `needs-fix`, `needs-human` or `autofix` remains a draft even when its checks pass — a person owns it.

  **Rollback.** Revert commit `bbaf144` before reverting WQUALITY-01 (`f48b8b7`), because the draft recovery calls `delivery_state`; drafts are no longer recovered by the plan and need a person or fix worker to undraft them. No stored state is changed.

### WQUALITY-03 — validate scope and hand-offs on the pinned candidate (script change)

- [ ] **Merged tooling change.** Source: `scripts/worker_gate.py`, `scripts/tests/test_worker_gate.py`, PR `#403`, commit `4f40d4d`.

  **Apply.** No apply step. Confirm `validate_candidate(changed, scope, notes, notes_path, finished, candidate_sha, head_sha)` and `candidate_from_env` are present on `main`, and that the worker job passes the private Surefire report directory.

  **Workflow on GitHub.** Dispatch `Agent swarm` with a synthetic task whose scope declares one documentation path: first a worker whose diff is entirely inside scope and whose notes carry non-empty `## Test evidence` and `## Coverage` sections; then synthetic variants that touch a file outside scope, touch `TODO.md`, omit a required heading, leave a `<placeholder>` in a section, and stop early without `## Why this is not finished`; finally a variant that moves HEAD between validation calls. Read the worker `validate` job output for each.

  **Green looks like.** The clean candidate passes the gate and prints `{"violations": []}`. Each bad candidate fails the `validate` job and prints one violation per fault with the expected rule name.

  **Proving log line.** `{"violations": []}` for the clean candidate; `{"violations": [{"file": "<path>", "rule": "scope"}]}` for the out-of-scope file, and `tracker`, `notes-heading`, `notes-empty`, `notes-placeholder`, `notes-unfinished` and `candidate-changed` for the other variants. The empty-SHA case never counts as a pinned candidate.

  **Negative control.** A worker that changes a tracker file is rejected even when the file is otherwise inside scope; a finished worker's missing `## Why this is not finished` is not a violation, but an unfinished worker's is. A candidate whose HEAD moved after validation is rejected with `candidate-changed` rather than reported clean.

  **Rollback.** Revert commit `4f40d4d`; the gate returns to integration-failure triage only and stops checking scope, notes and the pinned SHA. No stored state is changed.

### WQUALITY-04 — frontend production dependency scanning (script change)

- [ ] **Merged tooling change.** Source: `scripts/frontend-check.sh`, `scripts/tests/test_frontend_check.py`, PR `#383`, commit `458d3ce`.

  **Apply.** No apply step. Confirm the `npm audit --omit=dev --audit-level=high` step, the `FRONTEND_AUDIT=skip` notice, and the `audit_is_infrastructure` classifier are present on `main`, and that `package.json` and lock files are unchanged by the change.

  **Workflow on GitHub.** Dispatch `Delivery` on an exact SHA with clean production dependencies and with a development-only finding; then, on a scratch branch with a stubbed `npm` on `PATH`, run the worker check for a high production finding, a moderate finding, a development-only finding, a registry outage, and with `FRONTEND_AUDIT=skip`. Read the `Frontend standards` job and the worker output.

  **Green looks like.** `Frontend standards` is green for clean production dependencies. A development-only finding prints a warning and still passes. A high or critical production finding fails the gate; a registry outage fails as infrastructure, never as a pass; `FRONTEND_AUDIT=skip` prints a loud notice.

  **Proving log line.** `WARN <module>: high or critical vulnerability in development dependencies only (does not fail the gate)` for the dev-only case; `FAIL <module>: high or critical vulnerability in production dependencies` for the high case; `INFRASTRUCTURE <module>: npm audit could not reach the registry (failure, not a pass)` for the outage; and `NOTICE FRONTEND_AUDIT=skip: npm dependency audit DISABLED; high/critical vulnerabilities in production dependencies will NOT be detected. Use only for local development.` for the skip case.

  **Negative control.** A moderate production finding passes; a high finding fails. A registry error signature is classified as infrastructure before severity, so an outage cannot be mistaken for a clean result. The skip variable is the only way to bypass the scan, and it prints the notice on every run. These stubbed cases are local deterministic proofs that the check can fail; they are not GitHub evidence. Tick this box only from a green `Delivery` dispatch on the exact SHA.

  **Rollback.** Revert commit `458d3ce`; `frontend-check.sh` no longer runs `npm audit` and the gate returns to typecheck, lint and test only. No dependency or lock file changes with this revert.

### WQUALITY-05 — validate the runtime OpenCode profile (check change)

- [ ] **Merged tooling change.** Source: `scripts/gen-opencode.py`, `scripts/tests/test_opencode_profile.py`, PR `#412`, commit `9aaa67e`.

  **Apply.** No apply step. Confirm `check_runtime(root)` and the `--check-runtime` flag are present on `main`, and that the existing `--check` behaviour is unchanged.

  **Workflow on GitHub.** Dispatch `Delivery` on an exact SHA and read `Policy and tooling`; then, on scratch copies of `.github/opencode.ci.json`, run `python3 -I scripts/gen-opencode.py --check-runtime` with each violation in turn: an editing agent with no bash permission list, a read-only reviewer with an edit permission, a write rule targeting a protected file, a model not in `.github/swarm/slots.json`, an instruction file that does not exist, and a credential-shaped value.

  **Green looks like.** The repository profile exits 0 with no output and `Policy and tooling` is green. Each scratch violation exits non-zero and prints one line naming the offending agent or the runtime profile.

  **Proving log line.** `agent <name>: editing agent has no bash permission list`, `agent <name>: read-only reviewer has edit permission`, `agent <name>: write permission targets TODO.md via <pattern>`, `<name>: model '<model>' is not allowed by .github/swarm/slots.json` (or `runtime profile: model '<model>' is not allowed by .github/swarm/slots.json` for the top-level model), `runtime profile: instruction file <path> does not exist`, and `agent <name>: contains a <label>` on stderr.

  **Negative control.** A stale generated config does not excuse the runtime profile: `--check` and `--check-runtime` are independent and both must be green. A model that is valid for one profile but absent from `slots.json` is rejected. A credential placeholder such as `<token>` is inert; a real-looking value is rejected by the pattern rules and must never be written into a file or log. The scratch-profile cases are local deterministic red proofs only; tick this box only from the green `Policy and tooling` job on the exact SHA.

  **Rollback.** Revert commit `9aaa67e`; `--check-runtime` is removed and only the generated-config `--check` remains. The CI profile under `skip-worktree` is orchestrator-owned and must not be regenerated or committed by a worker.

### WQUALITY-06 — classify swarm failures and name the catch layer (script and guide change)

- [ ] **Merged tooling change.** Source: `scripts/failure_classes.py`, `scripts/tests/test_failure_classes.py`, `docs/swarm/failure-classification.md`, PR `#667`, commit `5b47f7d`. Depends on WFAUDIT-06's `diagnose`.

  **Apply.** No apply step. Confirm `classify(log, task_text, changed_files)` returns `{"class": ..., "catch_layer": ...}` with the five classes and their fixed layers, and that the review table in [failure-classification.md](../../swarm/failure-classification.md) is empty.

  **Workflow on GitHub.** After five swarm pull requests have been judged (merged, `needs-fix` or `needs-debug`), read their failure logs, task texts and changed files, run the classifier on each failure, and hold the review: for every class that occurred, add one regression at its catch layer and record the date, pull requests, class and regression in the guide's review table. Dispatch `Delivery` on the documenting commit.

  **Green looks like.** `Delivery tooling tests` is green and `Developer reference` builds the updated guide. Each failure is assigned one of the five classes and the layer that should have caught it, and the review table gains one row per new regression. This item stays unchecked until five swarm pull requests have actually been judged; the concrete assertion is that the guide's review table has exactly one new row for each class that produced a new regression and none for a repeat with no regression.

  **Proving log line.** For each example log the result is `{"class": "task-policy" | "missing-tests" | "check-parity" | "implementation" | "infrastructure", "catch_layer": "task template" | "worker-check" | "integrator-gate" | "unit test" | "contract test"}`; the class map is `task-policy → task template`, `missing-tests → worker-check`, `check-parity → integrator-gate`, `implementation → unit test`, `infrastructure → contract test`.

  **Negative control.** An unrecognised log is the catch-all `implementation` with layer `unit test` — there is no `unknown` class without an owner. An infrastructure signature such as `connection refused` or a timeout is classified before a code signature, and a `passes locally, fails in delivery` log is `check-parity`, not a missing test. A repeated class with no new regression adds no table row; that absence is the assertion, so compare the table before and after the review and confirm no row was added for the repeat.

  **Rollback.** Revert commit `5b47f7d`; the classifier and the review guide are removed and `diagnose` continues to explain failures without assigning an owner. Immutable `docs/swarm/` records are not rewritten by the revert.

### Pending audit — Docker image rebuild

- [ ] **Pending audit check, not run.** Source: `OPS-WORKER-QUALITY` in `TODO.md:14916-14924`, with the pending note at `:14922` and its PyYAML/fixture-isolation context at `:14919`. The related `AUD-20261121-18` (PR `#477`) pins the `Dockerfile.app` build stages by digest so that a rebuild of the same commit is reproducible.

  **Apply.** Confirm the worker image patch is on `main`: PyYAML is installed for the tooling imports and the synthetic fixture uses an isolated import path, not the shared Surefire directory. Confirm `Dockerfile.app` pins both build-stage base images by `sha256` digest rather than a mutable tag. Build the worker image from an exact green `main` SHA. No workflow patch is applied by this checklist; a person owns the image and the registry.

  **Workflow on GitHub.** Dispatch `Agent swarm` on a harmless synthetic documentation task so the rebuilt worker image runs a real tooling job, then dispatch `Delivery` on the exact SHA and watch both `Verify and smoke image (amd64)` and `Verify and smoke image (arm64)` for the application image.

  **Green looks like.** The worker tooling job is green with no `No module named 'yaml'`; the synthetic fixture runs in its isolated import path and does not leak into the shared environment; the fast-tier assertion is scoped to the current Surefire report. Both application smoke legs succeed and upload `smoke-tested-image-<arch>`, and the recorded digests are stable across a re-run of the same SHA.

  **Proving log line.** The worker tooling job finishes without `No module named 'yaml'` and the worker gate prints `integration tests passed` (or `{"violations": []}` for a clean candidate), not `integration run failed without fresh, usable test results (compile or setup error)`. A reproducible rebuild yields the same `sha256` image digest for the same source commit; `docker buildx imagetools inspect ghcr.io/<owner>/<repo>:<tag>` lists `linux/amd64` and `linux/arm64`.

  **Negative control.** A worker image built without the patch fails with `No module named 'yaml'` — the known worker-image false positive in AGENTS.md — and the synthetic fixture leaks into the shared Python imports when isolation is absent. Rebuilding from a floating build-stage tag yields a different toolchain digest for the same commit, proving the rebuild is not reproducible until the stages are pinned. A smoke leg that fails publishes no manifest.

  **Rollback.** Revert the worker-image patch and rebuild: the `No module named 'yaml'` failure returns and is again a known worker-image false positive. Revert the digest-pin commit and republish only under a new immutable tag; published tags and manifests are never overwritten. This checklist does not authorize a release, and deployment is a separate action.

### Pending audit — exact-SHA GitHub checks

- [ ] **Pending audit check, not run.** Source: `OPS-WORKFLOW-AUDIT` and `OPS-WORKER-QUALITY` in `TODO.md`.

  **Apply.** No apply step. Confirm the branch ruleset requires every check named in [Purpose and rules](#purpose-and-rules), that workflow concurrency queues rather than cancels, and that no commit in the verification set was force-pushed.

  **Workflow on GitHub.** Open a synthetic pull request from the exact SHA, observe every required check on that SHA, re-run `Delivery` on the same SHA, and inspect a second pull request whose head moved after its last green run. Inspect the `Delivery gate` job.

  **Green looks like.** Every required check is green on the exact SHA, `Delivery gate` passes, the re-run reproduces the same result and artifact digests, and the moved-head pull request is not merged on the strength of its older green run.

  **Proving log line.** `Delivery gate` green on the exact SHA and, for the moved-head case, `Delivery is missing for <sha12>; a fresh run was requested on this branch. Nothing merges until it reports passed.` A run for an older SHA is never accepted as evidence for the new head.

  **Negative control.** A green run for a superseded SHA must be classified as `missing`, not `passed`, by WQUALITY-01 and must not merge. A cancelled or approval-gated run must not be read as success. A re-run that changes the artifact digest for the same commit is a watch-for that invalidates the reproducibility claim; it is not a control a person can intentionally trigger.

  **Rollback.** Remove or adjust the branch protection only through the repository owner, then re-run the exact-SHA checks to confirm the old behaviour; never force-push or bypass a protection to make a red run green.

## Final gate

- [ ] **Clean `Delivery` run on the final `main` commit.** Dispatch `Delivery` on the final SHA and confirm every required check listed in [Purpose and rules](#purpose-and-rules) is green. Record the run URL and SHA in the result table. Nothing is verified until this run is green.

## Result table

All cells are intentionally unfilled; a person fills them after running each workflow.

| Task | Date | Run URL | Exact SHA | Proving log line | Result |
|---|---|---|---|---|---|
| WQUALITY-01 | not run | not run | not run | not run | unchecked |
| WQUALITY-02 | not run | not run | not run | not run | unchecked |
| WQUALITY-03 | not run | not run | not run | not run | unchecked |
| WQUALITY-04 | not run | not run | not run | not run | unchecked |
| WQUALITY-05 | not run | not run | not run | not run | unchecked |
| WQUALITY-06 | not run | not run | not run | not run | unchecked |
| Docker image rebuild | not run | not run | not run | not run | unchecked |
| Exact-SHA checks | not run | not run | not run | not run | unchecked |
| Final `Delivery` | not run | not run | not run | not run | unchecked |

## Findings for the person

1. WQUALITY-01 through WQUALITY-06 are merged as script or documentation changes; their GitHub behaviour has not been observed. This checklist records scenarios only and verifies nothing.
2. WQUALITY-02 calls `delivery_state` from WQUALITY-01, and WQUALITY-06 builds on WFAUDIT-06's `diagnose`. Prove the predecessors before their dependants.
3. The pending Docker rebuild is the worker-image verification from `OPS-WORKER-QUALITY` (`TODO.md:14919`, `:14922`): PyYAML and fixture isolation must be in the rebuilt image, proved by a green tooling job with no `No module named 'yaml'`. Separately, `AUD-20261121-18` (PR `#477`) digest-pins the `Dockerfile.app` build stages; a floating tag makes the same source commit produce a different toolchain, so a rebuild is not reproducible until it is pinned.
4. Published image tags and manifests are immutable. A corrected publish or a rollback uses a new tag; nothing is overwritten and this document does not authorize a release or a deployment.
5. `docs/swarm/` holds immutable merge records that skip the structural rules of `check-docs.py`; the regression table added by WQUALITY-06 lives there and is a person-review item, not a CI gate. Worker notes under `.agent/` are also not checked by CI.
6. This checklist is a documentation-only change. Delivery may skip it under the existing docs path rule; the integrator's local docs check is its evidence, and no required check is waived by that skip.

## Approval

Not applied and not verified. Every box remains unchecked until a person with workflow permission performs the apply and GitHub verification steps above and records the evidence. The evidence behind this checklist is file reads and the executed repository checks documented in the source notes; there is no simulated-user, human-participant or clinical evidence, and no GitHub run was performed by this worker.
