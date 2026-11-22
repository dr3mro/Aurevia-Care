# GitHub verification checklist for the swarm quality changes

- Status: **proposed checklist — nothing here is verified.** Every box is unchecked.
- Date: 2026-11-22
- Requirement IDs: OPS-06, WEB-12
- Source tasks: WQUALITY-01 through WQUALITY-06 (all six merged as script or documentation changes; none has been verified on GitHub).
- Evidence for this checklist: file reads of the six WQUALITY entries in `TODO.md`, their six notes files under `.agent/pr-notes/`, the merged scripts (`scripts/swarm.py`, `scripts/worker_gate.py`, `scripts/frontend-check.sh`, `scripts/gen-opencode.py`, `scripts/failure_classes.py`), `docs/swarm/failure-classification.md`, `docs/WORKFLOW.md`, `docs/SWARM.md`, `.github/agent-image/Dockerfile` and the workflow job definitions. One bounded `smartclinic-github-workflow` specialist review corrected the required-check and image-digest statements below (recorded in Findings). No GitHub workflow was run to verify the six changes, no proving log was produced, and no simulated user or human participant was involved. Every value below is synthetic (`owner/repo`, `g<run>-<sha12>`, `<sha>`, digest placeholders and invented task titles).

## Purpose and rules

This checklist is the hand-off from the six quality workers to a person who has workflow-file permission and a GitHub token. Each change is already merged; this document says how a person proves it actually works on GitHub, names the exact observation that proves it, and gives the rollback. It intentionally leaves every box unchecked: a local `unittest` run, a stubbed `npm`, a read of a worker note and a simulated review are not GitHub verification.

Rules for the person performing it:

1. These are merged changes, not proposed patches. Confirm each cited commit is an ancestor of the SHA under test; do not re-apply a diff or re-run a worker to "verify" it.
2. Use a synthetic task, a synthetic draft pull request, a synthetic fix pull request and synthetic fixture data. Never use real patient data, a real credential or a production release. A release tag needs the separate release authority in [README-SETUP.md](../../../README-SETUP.md); this document does not authorize a release.
3. Record the workflow run URL, the exact head SHA and the proving log line in the [result table](#result-table) before ticking a box. One box per change; a box is ticked only when the stated observation was seen in a GitHub run.
4. Run the checks on the exact SHA. The single branch-protection check is `Delivery gate`: it fails when any verification job fails or is cancelled and accepts jobs skipped on purpose ([delivery.yml](../../../.github/workflows/delivery.yml) `gate`). The `Delivery gate` rollup covers `Policy and tooling`, `Delivery tooling tests`, `Frontend standards`, `Java and database verification`, `Developer reference`, `Verify and smoke image` and `Release`; record their results as evidence, not as separate required status checks. On a `main` push or a pull request only `Verify and smoke image (amd64)` exists, and it is skipped entirely on pull requests; `Verify and smoke image (arm64)` exists only on a `v*` tag run, which this checklist does not authorize ([delivery.yml](../../../.github/workflows/delivery.yml) `verify`). Never force-push or bypass a protection.
5. If a prerequisite is missing, leave the box unchecked and write the missing item in the result table. Two such gaps are already recorded in [Findings for the person](#findings-for-the-person).

## Apply order and dependencies

1. **Confirm the six merges are on `main` with green Delivery checks on their own SHAs:** WQUALITY-01 (`#673`), WQUALITY-02 (`#816`), WQUALITY-03 (`#403`), WQUALITY-04 (`#383`), WQUALITY-05 (`#412`), WQUALITY-06 (`#667`). WQUALITY-02 depends on WQUALITY-01 (`delivery_state`); confirm WQUALITY-01's function is present before testing draft recovery. No further apply step is needed.
2. **Rebuild the worker tooling before trusting any worker-run evidence.** Run `Agent image` (`.github/workflows/agent-image.yml`) on the final `main` SHA, then `Swarm slot images` (`.github/workflows/swarm-images.yml`) so every slot is rebuilt from the freshly published base. Record the one digest `Agent image` publishes in `published-image.txt`; `Swarm slot images` pushes mutable `:latest` tags and publishes no digest. This is the pending item in the OPS-WORKER-QUALITY audit entry: until it is done, a worker may run older tooling even though the scripts in the checkout are current.
3. **Verify the queue-level changes first** (WQUALITY-01 integrator classification, WQUALITY-02 draft recovery), because they decide which synthetic pull requests reach the worker and integrator gates. Then verify the gate-level changes (WQUALITY-03, WQUALITY-04, WQUALITY-05, WQUALITY-06).
4. **Finish with one clean `Delivery` run on the final `main` commit** and record its run URL and SHA in the result table.

## Verification items

### WQUALITY-01 — integrator tells failed, pending, missing and approval-required Delivery apart

- [ ] **Merged script change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_delivery_state.py`, PR `#673`, note `.agent/pr-notes/WQUALITY-01-20261122-001721.md`. Functions `delivery_state`, `delivery_runs`, `delivery_gate` and `markdown_only`.
  **Apply.** No apply step. Confirm the functions are on `main` and that `integrate-batch` calls `delivery_gate`.
  **Workflow on GitHub.** `Swarm integrator` (`integrate-batch`) driven with synthetic ready pull requests; `Delivery` on each exact head SHA.
  **Draft that fails a check.** Open a synthetic swarm PR whose head SHA has a `failed`, `cancelled` or timed-out Delivery run. Observation that proves it: the integrator classifies the state as `failed`, routes the PR to the fix workers, applies `needs-fix` and does not create a merge commit.
  **Draft that passes.** Open a synthetic swarm PR with a `passed` Delivery run for its exact head SHA. Observation that proves it: the integrator merges it and no `needs-fix` label appears.
  **Stale Delivery run.** Push a new commit to a synthetic PR after its last Delivery run, so the run's `headSha` is older than the PR head. Observation that proves it: the integrator classifies the state as `missing`, comments asking for a run, dispatches a fresh Delivery run for the new head and never merges.
  **Approval-required.** Use a synthetic run or job that is `action_required` or waiting for a first-time-contributor or environment approval. Observation that proves it: the integrator classifies the state as `approval-required`, comments and dispatches, and does not merge.
  **Issue the integrator opens.** Protected scripts are mergeable by this swarm; the integrator opens an oversight issue when it merges one. Read it and confirm it names WQUALITY-01 / PR `#673` and the script paths.
  **Negative control.** A documentation-only PR with a skipped or absent Delivery run still merges under the documented exception; a code PR with a skipped run does not.
  **Rollback.** Revert the merged commit in a new reviewed change. Re-run the stale-SHA scenario and confirm the old behaviour returns (merge on a green rollup regardless of head SHA). Do not merge ordinary PRs while reverted, because current-head gating is gone.

### WQUALITY-02 — recover ordinary draft pull requests

- [ ] **Merged script change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_drafts.py`, PR `#816`, note `.agent/pr-notes/WQUALITY-02-20261122-113140.md`. Function `draft_recovery`, constants `DRAFT_RECOVER`, `DRAFT_FIX`, `DRAFT_LEAVE`, wired into `plan_integration`.
  **Apply.** No apply step. Confirm `draft_recovery` reuses `delivery_state` from WQUALITY-01.
  **Workflow on GitHub.** `Agent swarm` → the `plan-integration` step, with synthetic draft pull requests in the queue.
  **Draft that fails a check.** Open an ordinary swarm draft whose head SHA has a `failed` Delivery run. Observation that proves it: `draft_recovery` returns `DRAFT_FIX`, the PR stays a draft, a fix worker is dispatched (deduplicated by `fix-\d` labels up to `MAX_FIX`, which is 3) and a `needs-fix`/`fix-N` label is applied.
  **Draft that passes.** Open an ordinary swarm draft with a `passed` Delivery run and no blocking label. Observation that proves it: `draft_recovery` returns `DRAFT_RECOVER`, the plan step runs `gh pr ready`, the PR is no longer a draft and it is queued for integration.
  **Stale Delivery run.** Open an ordinary draft whose Delivery evidence is `pending`, `missing` or `approval-required`. Observation that proves it: `draft_recovery` returns `DRAFT_LEAVE`, the PR is not marked ready, no fix worker is sent and no label changes.
  **Reconciler, repair and labelled drafts.** Open synthetic reconciler (`instruction updates for review`), `FIX-`/`DLV-`/`autofix` and `needs-fix`/`needs-human` labelled drafts. Observation that proves it: each stays a draft for a person.
  **Issue the integrator opens.** Read the oversight issue for PR `#816` and confirm it names WQUALITY-02 and `scripts/swarm.py`.
  **Rollback.** Revert the merged commit. Re-run the passing-draft scenario and confirm drafts stay drafts until a fix worker undrafts them again.

### WQUALITY-03 — validate scope and structured hand-offs on the pinned candidate

- [ ] **Merged script change.** Source: `scripts/worker_gate.py`, `scripts/tests/test_worker_gate.py`, PR `#403`, note `.agent/pr-notes/WQUALITY-03-20261121-153620.md`. Functions `parse_scope`, `validate_candidate`, `candidate_from_env`.
  **Apply.** No apply step. Confirm `.github/workflows/swarm-worker.yml` still sets `CHECK_SCRIPT: scripts/worker-check.sh` (consumed by `scripts/agent-run.sh`, which runs it), and that `worker-check.sh` calls `python3 -I scripts/worker_gate.py`.
  **Workflow on GitHub.** `Agent swarm` → `Swarm worker run` → the worker-gate step of `scripts/worker-check.sh`, with synthetic worker candidates.
  **Draft that fails a check.** Use a synthetic candidate that changes a file outside its declared scope, changes a tracker, omits `## Test evidence` or `## Coverage`, is unfinished without `## Why this is not finished`, contains a `<placeholder>` value, or whose head SHA moves after validation. Observation that proves it: the gate exits non-zero and prints a structured `{"violations": [{"file": ..., "rule": ...}]}` result naming the offending file and rule; the PR is not published as ready.
  **Draft that passes.** Use a synthetic in-scope candidate whose notes have non-empty required sections. Observation that proves it: the gate exits zero and the worker PR is published normally.
  **Stale Delivery run.** Not applicable — this gate does not read Delivery state. The equivalent control is a candidate whose head SHA changes between validation and publication: observation that proves it is a reported `candidate-changed` violation (the head SHA moved) and a failed gate.
  **Issue the integrator opens.** Read the oversight issue for PR `#403` and confirm it names WQUALITY-03 and `scripts/worker_gate.py`.
  **Rollback.** Revert the merged commit. Re-run the out-of-scope candidate and confirm scope and tracker violations no longer fail worker publication.

### WQUALITY-04 — frontend dependency scanning in the frontend check

- [ ] **Merged script change.** Source: `scripts/frontend-check.sh`, `scripts/tests/test_frontend_check.py`, PR `#383`, note `.agent/pr-notes/WQUALITY-04-20261121-153622.md`. Requirement WEB-12.
  **Apply.** No apply step. Confirm `scripts/check.sh:24`, `scripts/worker-check.sh:11` and `.github/workflows/delivery.yml:79` all call `scripts/frontend-check.sh`.
  **Workflow on GitHub.** `Delivery` → job `Frontend standards` on the exact SHA, and `Swarm worker run` → `worker-check.sh` for the synthetic PR.
  **Draft that fails a check.** Open a synthetic PR that adds a high or critical production dependency to either frontend module. Observation that proves it: `npm audit --omit=dev --audit-level=high` is non-zero, the step reports the finding and `Frontend standards` is red, so the PR is not mergeable.
  **Draft that passes.** Use dependencies with no high or critical production findings; moderate findings are allowed. Observation that proves it: `Frontend standards` is green and both modules are audited with the required flags.
  **Registry outage.** Force the audit to fail with a registry/CLI error against a synthetic fixture. Observation that proves it: the step prints `INFRASTRUCTURE <module>: npm audit could not reach the registry (failure, not a pass)` and the job is red, never a pass. A dev-only high finding prints a warning but does not fail the job.
  **Skip bypass.** Set `FRONTEND_AUDIT=skip`. Observation that proves it: a loud notice is printed once and only the audit is skipped; any other value still audits.
  **Stale Delivery run.** Not applicable — this gate reads dependencies, not Delivery state. The equivalent control is a PR head that moves after the gate ran: Delivery must re-run `Frontend standards` on the new exact SHA before merging.
  **Issue the integrator opens.** Read the oversight issue for PR `#383` and confirm it names WQUALITY-04 and `scripts/frontend-check.sh`.
  **Rollback.** Revert the merged commit. Re-run the high-finding scenario and confirm high findings no longer fail the frontend job (the WEB-12 gap returns).

### WQUALITY-05 — validate the runtime OpenCode profile separately from the generated config

- [ ] **Merged script change.** Source: `scripts/gen-opencode.py`, `scripts/tests/test_opencode_profile.py`, PR `#412`, note `.agent/pr-notes/WQUALITY-05-20261121-154553.md`. Function `check_runtime`, flag `--check-runtime`.
  **Apply.** No apply step — but note that no workflow or shell script calls `--check-runtime` yet (see [Findings for the person](#findings-for-the-person)). For this verification a person must invoke `python3 -I scripts/gen-opencode.py --check-runtime` on a GitHub runner through a temporary dispatch or job and record its output, then remove that temporary wiring.
  **Workflow on GitHub.** `Delivery` → `Policy and tooling` for the unchanged `--check` drift check, plus a temporary runner invocation of `--check-runtime`.
  **Draft that fails a check.** Use a synthetic CI profile in which an editing agent has no bash permission list, a reviewer has an edit permission, an agent may write `.github/*`, `TODO.md` or `.opencode/*`, an instruction file is missing, a model is absent from `.github/swarm/slots.json`, or a token string appears. Observation that proves it: the command prints a violation naming the agent (or `runtime profile`) and exits 1.
  **Draft that passes.** Run the command against the repository's `.github/opencode.ci.json`. Observation that proves it: exit 0 with no output, and the existing `--check` mode still passes against the committed local `opencode.json`.
  **Stale Delivery run.** Not applicable — this check reads the profile and slots files, not Delivery state. The equivalent control is profile drift between validation and publication: the unchanged `--check` reports the generated file as stale.
  **Issue the integrator opens.** Read the oversight issue for PR `#412` and confirm it names WQUALITY-05 and `scripts/gen-opencode.py`.
  **Rollback.** Revert the merged commit. Confirm `--check-runtime` is gone and the temporary runner wiring is removed.

### WQUALITY-06 — classify swarm pull request failures and name the catching layer

- [ ] **Merged script and documentation change.** Source: `scripts/failure_classes.py`, `scripts/tests/test_failure_classes.py`, `docs/swarm/failure-classification.md`, PR `#667`, note `.agent/pr-notes/WQUALITY-06-20261122-001725.md`. Function `classify`.
  **Apply.** No apply step. Confirm `classify` returns one of `task-policy`, `missing-tests`, `check-parity`, `implementation`, `infrastructure` and a `catch_layer`.
  **Workflow on GitHub.** `Delivery` → `Delivery tooling tests` runs `tests/test_failure_classes.py`; `Delivery` → `Policy and tooling` runs `python3 -I scripts/check-docs.py` over the guide.
  **Draft that fails a check.** Change a fixture log so it no longer matches its class, or break a link or requirement ID in the guide. Observation that proves it: the targeted test fails and `check-docs.py` prints `check-docs: <file>: broken link ...` or `check-docs: <file>: unknown requirement ID ...`, so `Delivery tooling tests` or `Policy and tooling` is red. Do this only in a synthetic fixture, never in the merged file.
  **Draft that passes.** Observation that proves it: the ten classifier tests are green and `check-docs.py` is silent on the guide.
  **Five-pull-request review rule.** After five swarm pull requests, a person fills the empty `Review record` table in [failure-classification.md](../../swarm/failure-classification.md) with the date, pull requests, class and regression added. Observation that proves it: one regression exists at the named `catch_layer` for each new class.
  **Stale Delivery run.** Not applicable — the classifier is offline and reads only caller-supplied strings. The equivalent control is that the review-record table stays empty until a person performs the five-PR review.
  **Issue the integrator opens.** Read the oversight issue for PR `#667` and confirm it names WQUALITY-06, `scripts/failure_classes.py` and the new guide.
  **Rollback.** Revert the three merged files. Confirm the classifier and guide are gone; no CI behaviour changes because the module has no production caller.

## Pending Docker image rebuild and exact-SHA checks

- [ ] **Worker image rebuild and exact-SHA checks still pending in the audit.** Source: the `OPS-WORKER-QUALITY` and `OPS-WORKFLOW-AUDIT` entries in `TODO.md` ("Docker image rebuild and exact-SHA GitHub checks are pending"; "GitHub verification is pending"). The broader workflow-patch checklist is [github-verification-checklist.md](github-verification-checklist.md); this section covers the quality changes only.
  **Workflow on GitHub.** Dispatch `Agent image` on the final `main` SHA, then `Swarm slot images`, then `Delivery` on that same exact SHA.
  **Observation that proves the rebuild.** `Agent image` is green with the pinned base tag, its log shows `opencode --version` reporting `2.3.2` and the `python3 -I -c 'import yaml'` step succeeding, and it uploads `agent-image-published` containing one `image@sha256:…` line from `docker buildx imagetools inspect`. Record that digest in the result table. `Swarm slot images` is green and its log shows a build and push for each of the ten slots to `<slot>:latest`; it publishes no digest, so record the run URL instead.
  **Observation that proves exact-SHA checks.** `Delivery gate` is green on the final `main` SHA, with `Policy and tooling`, `Delivery tooling tests`, `Frontend standards`, `Java and database verification`, `Developer reference` and `Verify and smoke image (amd64)` green as the evidence it rolls up; each of the six merged commits has its own green `Delivery gate` verdict. A re-run of `Delivery` on the same SHA yields the same result. Do not wait for an `arm64` verdict on a `main` or PR SHA: that matrix entry exists only for `v*` tags, which this checklist does not authorize.
  **Rollback.** Worker images are published to mutable `:latest` tags, so a corrected rebuild overwrites them; record the new `Agent image` digest when you rebuild and re-run `Swarm slot images` so every slot is rebuilt from it. Immutability and pull-by-digest apply to `v*` release tags ([README-SETUP.md](../../../README-SETUP.md)), not to worker images. Reverting a script commit requires a new commit plus a fresh rebuild; do not treat an `Agent image` digest produced from a stale checkout as proof of the current scripts.

## Result table

All cells are intentionally unfilled; a person fills them after running each GitHub verification.

| Task | Date | Run URL | Exact SHA | Proving log line | Result |
|---|---|---|---|---|---|
| WQUALITY-01 | not run | not run | not run | not run | unchecked |
| WQUALITY-02 | not run | not run | not run | not run | unchecked |
| WQUALITY-03 | not run | not run | not run | not run | unchecked |
| WQUALITY-04 | not run | not run | not run | not run | unchecked |
| WQUALITY-05 | not run | not run | not run | not run | unchecked |
| WQUALITY-06 | not run | not run | not run | not run | unchecked |
| Agent image rebuild | not run | not run | not run | not run | unchecked |
| Slot image generation | not run | not run | not run | not run | unchecked |
| Exact-SHA Delivery (final main) | not run | not run | not run | not run | unchecked |

## Findings for the person

1. **`--check-runtime` has no CI caller.** A repository search found no call in `.github/` or any shell script. Until an owner wires it into `Delivery` or a dispatch, WQUALITY-05 can only be verified by a manual runner invocation; its rule set exists in the script but is not a standing gate.
2. **`classify()` has no production caller.** WQUALITY-06 is proven by its unit tests and the guide. Its benefit to swarm triage depends on a later story calling it from the debugger or daily-notes reconciliation.
3. **WQUALITY-03's unfinished-reason rule is dormant until wired.** No producer exports `NOTES_FILE` or `WORKER_FINISHED`; the gate derives the notes path from `STORY_ID` but only activates the "did not finish" rule when the trusted pipeline exports `WORKER_FINISHED=0`.
4. **Worker-image measurement artifacts are known false positives.** In a cloud worker `git ls-files -v opencode.json` reports `S` (the CI profile under `skip-worktree`); measure pack results against `git show HEAD:opencode.json`. This is OPS-CW-003-01, not a defect of these changes.
5. **The agent image carries tooling, not the checkout scripts.** Its Dockerfile installs Java, Maven, Node/npm, Python with PyYAML, Git/gh and PostgreSQL, and warms the Maven repository; the scripts are checked out from `main` per job. The rebuild proves the tooling the gates need is current, but each gate must still run against the exact checkout SHA.
6. **No release is authorized here.** A pre-release tag would need the separate release authority in [README-SETUP.md](../../../README-SETUP.md); this checklist only covers verification of the quality changes.
7. **Repository docs disagree on branch-protection checks.** [README-SETUP.md](../../../README-SETUP.md) step 5 says to require both `Verify and smoke image` checks, while [delivery.yml](../../../.github/workflows/delivery.yml) `gate` says to require only `Delivery gate` and nothing else. This checklist follows the workflow, which is authoritative at runtime: `verify` is skipped on pull requests and emits `arm64` only on tags. An owner should reconcile the two documents; this checklist does not edit either.

## Lessons for workers

- A checklist for already-merged changes must say "confirm the commit is on `main`", not "apply the patch"; the sibling workflow-patch checklist uses apply blocks, but these six tasks do not.
- Name the exact discovering observation, not just the job name: a log line, a label, a comment, an exit code or a digest is what a person can record in the result table.
- Mark scenarios that cannot apply to a change (for example a stale Delivery run for an offline classifier) as not applicable and give the equivalent control, instead of inventing a scenario the code cannot produce.

## Approval

Not applied and not verified by this worker. Every box remains unchecked until a person with workflow permission performs the GitHub steps above and records the evidence. The evidence behind this checklist is file reads and the executed repository checks documented in the six source notes; no GitHub run, simulated-user, human-participant or clinical evidence was produced here.
