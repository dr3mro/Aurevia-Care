# GitHub verification checklist for the workflow-change audit

- Status: **proposed checklist — nothing here is verified.** Every box is unchecked.
- Date: 2026-11-22
- Requirement IDs: OPS-05, OPS-06, WEB-11, WEB-12
- Source tasks: WFAUDIT-01 through WFAUDIT-08 (all merged as documentation or tooling changes, but none of the proposed workflow patches has been applied to a workflow file).
- Evidence for this checklist: file reads of the three patch documents, the eight task files, their notes, `docs/SWARM.md`, and the workflow job definitions. No GitHub workflow was run, no log was produced, no reviewer or human participant was involved. All values in the examples below are synthetic (`owner/repo`, `v0.1.0-rc.<n>`, `g<run>-<sha12>`, digest placeholders).

## Purpose and rules

This checklist is the hand-off from the audit workers to a person who has workflow-file permission and a GitHub token. It orders the proposed patches and script changes, names the exact workflow to run for each, describes green, records the log line that proves the change works, and gives the rollback.

Rules for the person performing it:

1. Change only workflow files by applying the embedded diff from the named patch document; do not hand-edit a proposed workflow. Script and documentation changes are already merged — confirm the commit is on `main` rather than re-applying them.
2. Use a synthetic task, a synthetic fix pull request and a synthetic pre-release tag. Never use real patient data, a real credential or a production release. A release tag requires the separate release authority in [README-SETUP.md](../../../.opencode/config/README-SETUP.md); this document does not authorize a release.
3. Record the workflow run URL, the exact SHA, the proving log line and any image digest in the result table before ticking a box. A local `git apply --check`, a stubbed dry run or a simulated review is **not** GitHub verification and must not tick a box.
4. Run the required checks on the exact SHA. Required checks are `Policy and tooling`, `Delivery tooling tests`, `Frontend standards`, `Java and database verification`, `Developer reference`, both `Verify and smoke image` jobs and `Delivery gate`. Never force-push or bypass a protection. Workflow files are protected: only a person applies or rolls back these patches.
5. If a step cannot be performed because a prerequisite is missing, leave its box unchecked and write the missing item in the result table.

## Apply order and dependencies

1. **Confirm the merged tooling baseline** (WFAUDIT-02, WFAUDIT-05, WFAUDIT-06, WFAUDIT-07) and the reconciled documentation (WFAUDIT-08) are on `main` with green `Delivery` checks on their exact SHAs. They are already merged, so no apply step is needed. WFAUDIT-02 must be present before the WFAUDIT-03 workflow patch, because the patch calls `images-ready --generations` and `generation_tag()`.
2. **Apply the slot-image generation patch (WFAUDIT-03)** and dispatch `Swarm slot images` once to create the first ledger. Until the ledger exists the plan job intentionally starts zero workers. Verify both `Swarm slot images` and `Agent swarm` before moving on.
3. **Apply the first-release architecture-tag patch (WFAUDIT-04)** before attempting any release verification. It changes the `release` job of `delivery.yml` and is independent of the swarm patches.
4. **Land the two trusted `scripts/swarm.py` subcommands required by WFAUDIT-01** (see [Blocked prerequisite](#blocked-prerequisite)). The WFAUDIT-01 diff spans three workflow files and is applied as one patch; the integrator hunk calls subcommands that do not exist yet, so the whole patch must wait. Do not apply the integrator hunk alone.
5. **Apply the candidate-validation isolation patch (WFAUDIT-01)** and run its three GitHub dispatches. This is last because it renames the integrator's `integrate` job and changes the worker and fix-PR publication paths.
6. **Finish with one clean `Delivery` run on the final `main` commit** and record every run URL in the result table.

## Verification items

### WFAUDIT-02 — immutable slot-image generation helpers (script change)

- [ ] **Merged tooling change.** Source: `scripts/swarm.py`, `scripts/tests/test_slot_generations.py`, PR `#375`, commit `5c069bc0`.

  **Apply.** No apply step. Confirm the commit is an ancestor of the SHA under test and that the helpers `generation_tag`, `pick_generation`, `last_good`, `wave_generation` and the `--generations` input to `images-ready` are present in `main`.

  **Workflow on GitHub.** `Delivery` on the exact SHA: jobs `Policy and tooling` and `Delivery tooling tests`. The end-to-end behaviour is exercised after WFAUDIT-03 is applied, through `Agent swarm` (below).

  **Green looks like.** Both jobs succeed; `Delivery tooling tests` reports `OK` with no `FAILED`; `Policy and tooling` is green.

  **Proving log line.** In `Agent swarm` after WFAUDIT-03: `Slot-image generation: g<run>-<sha12> (newest|last-good)` and the `images` step reports `ready=true`. Without the ledger input, `images-ready` still emits the legacy `unmanaged` behaviour.

  **Negative control.** A complete newest generation wins; an incomplete newest falls back to `last-good`; an empty ledger reports `generation_source=none`, `ready=false` and the wave starts zero workers. These are asserted by `test_slot_generations.py` in `Delivery tooling tests`.

  **Rollback.** Revert commit `5c069bc0` only after reverting WFAUDIT-03 (it depends on this change). Removal restores the previous readiness logic; there is no state to unwind.

### WFAUDIT-05 — fail closed on agent-inventory drift (script change)

- [ ] **Merged tooling change.** Source: `scripts/check-pack.py`, `scripts/tests/test_check_pack.py`, PR `#380`, commit `2913b414`.

  **Apply.** No apply step. Confirm the commit is on `main`.

  **Workflow on GitHub.** `Delivery` → job `Policy and tooling` (it runs `python3 -I scripts/check-pack.py`), plus `Delivery tooling tests` for the new drift cases.

  **Green looks like.** `Policy and tooling` is green and `Delivery tooling tests` reports `OK`.

  **Proving log line.** `Delivery tooling validated: 33 agents, 33 skills` from `check-pack.py`. The six fail-closed cases are covered by `test_check_pack.py` (agent file absent from generated config, configured agent with no file, missing skill reference, skill without `SKILL.md`, duplicate agent name, named worker role with no agent).

  **Negative control.** Any of those six drifts in a synthetic fixture makes `check-pack.py` exit non-zero and name the offending file in `check-pack: <error>`.

  **Rollback.** Revert commit `2913b414`; the previous coarse set comparison is restored. No stored state is changed.

### WFAUDIT-06 — structured diagnostics instead of raw log tails (script change)

- [ ] **Merged tooling change.** Source: `scripts/swarm.py`, `scripts/tests/test_swarm_diagnostics.py`, PR `#553`, commit `72b6070c`.

  **Apply.** No apply step. Confirm the commit is on `main`.

  **Workflow on GitHub.** `Delivery` → `Delivery tooling tests` (unit coverage of `diagnose`) and `Policy and tooling`. The end-to-end proof comes from a deliberately failing synthetic swarm pull request: `Agent swarm`/`Fix PR gate` posts the handoff or stuck comment.

  **Green looks like.** `Delivery tooling tests` reports `OK`; in a failing synthetic run the PR comment or issue shows the structured summary before the masked, clipped raw tail.

  **Proving log line.** `**Diagnosis**` followed by the detected tool, failing step, exit code, up to five names and the class in the PR comment or issue, with the raw tail below it. In tests: `test_fail_puts_the_structured_summary_before_the_raw_tail`, `test_a_stuck_issue_puts_the_structured_summary_before_the_detail`, `test_a_handoff_puts_the_structured_summary_before_the_log_tail`.

  **Negative control.** A synthetic log containing a secret must show the masked value, not the raw token; an empty or huge log must still produce a summary and a clipped tail.

  **Rollback.** Revert commit `72b6070c`; `fail`, `report_pr_stuck` and `report_worker_handoff` post the raw tail again.

### WFAUDIT-07 — validate generated Markdown before commit (script change)

- [ ] **Merged tooling change.** Source: `scripts/check-docs.py`, `scripts/tests/test_check_docs.py`, PR `#384`, commit `9b6dc218`.

  **Apply.** No apply step. Confirm the commit is on `main`.

  **Workflow on GitHub.** `Delivery` → `Policy and tooling` (runs `python3 -I scripts/check-docs.py`) and `Delivery tooling tests`. The reconciler path is `Daily notes` → rules pull request, which runs the same check before opening the pull request.

  **Green looks like.** Both jobs green; `check-docs.py` prints nothing and exits 0; the new structural tests report `OK`.

  **Proving log line.** No `check-docs:` line in the `Policy and tooling` log. A malformed generated document reports `check-docs: <file>:<line>: unbalanced code fence` (or `heading jumps`, `table row has N cells`, `raw control character`, `unmasked token pattern`, `unclosed HTML comment or marker`). Existing link and requirement-ID checks are unchanged.

  **Negative control.** A synthetic fixture with an unbalanced fence, a heading jump, a ragged table, a control character, a token pattern or an unclosed HTML comment marker must make the check fail at the expected line. Immutable merge records under `docs/swarm/` remain exempt from the structural rules.

  **Rollback.** Revert commit `9b6dc218`; only the link and requirement-ID checks remain. Note the known immutable record `docs/swarm/BUILD-53.md` has an unclosed fence, which is why the structural rules skip `docs/swarm/`.

### WFAUDIT-08 — reconcile runbook and swarm guide (documentation change)

- [ ] **Merged documentation change.** Source: `docs/SWARM.md`, `docs/TEAM-ACTIONS.md`, PR `#382`, commit `894dd24a`.

  **Apply.** No apply step. Confirm the commit is on `main`.

  **Workflow on GitHub.** `Delivery` → `Developer reference` (runs `scripts/docs.sh`) and `Policy and tooling` (checks links and requirement IDs).

  **Green looks like.** Both jobs green; the `developer-reference` artifact is uploaded.

  **Proving log line.** `Developer reference` finishes with an uploaded `developer-reference` artifact containing `index.html`; `Policy and tooling` has no `check-docs:` error. The guide states the 5-attempt and 50-file limits in prose (the code constants are `MAX_ATTEMPTS` and `MAX_PR_FILES`), the `repair-gate.sh` path for repairs, `SWARM_ALLOW_PROTECTED` and the `protected-change` oversight issue.

  **Negative control.** A stale number or an absolute protected-file claim in either document is caught by reading the source lines cited in [the WFAUDIT-08 notes](../../../.agents/notes/WFAUDIT-08-20261121-153634.md) (notes are not themselves checked by CI; this is a person review).

  **Rollback.** Revert commit `894dd24a`; the previous wording is restored. No workflow behaviour changes with this documentation.

### WFAUDIT-03 — immutable slot-image generation tags (proposed workflow patch)

- [ ] **Proposed workflow patch, not applied.** Source document: [slot-image-generations.md](slot-image-generations.md). Target files: `.github/workflows/swarm-images.yml`, `.github/workflows/swarm.yml`. Depends on WFAUDIT-02.

  **Apply.** Extract the single `diff` block and apply it, then run the local gates:

  ```bash
  awk '/^```diff$/{in_block=1; next} /^```$/{in_block=0} in_block' \
    docs/operations/workflow-changes/slot-image-generations.md \
    > /tmp/slot-image-generations.patch
  git apply --check /tmp/slot-image-generations.patch
  git apply /tmp/slot-image-generations.patch
  git diff --check
  python3 -m venv "$TMPDIR/actionlint" && "$TMPDIR/actionlint/bin/pip" install -q actionlint-py==1.7.12.25 && "$TMPDIR/actionlint/bin/actionlint"
  python3 -I scripts/check-docs.py
  python3 -I -m unittest discover -s scripts/tests -q
  git add .github/workflows/swarm-images.yml .github/workflows/swarm.yml
  git commit -m "Publish slot images under immutable generation tags"
  ```

  **Workflow on GitHub.** Dispatch `Swarm slot images` (a `workflow_dispatch` on `main`) first — bootstrap. Then dispatch `Agent swarm` with `workers=1` and one open synthetic documentation task.

  **Green looks like.** `Swarm slot images`: job `Slot images` logs `generation=g<run>-<sha12>`, each slot logs its `imagetools inspect` digest check, and the run uploads a `slot-image-generations` artifact whose `generations.json` has one complete entry with ten slots. `Agent swarm`: `images` reports `ready=true`, the plan logs the chosen generation, and the `Swarm worker` job input `image` ends with `:g<run>-<sha12>`, not `:latest`.

  **Proving log line.** `generation=g<run>-<sha12>` in `Swarm slot images`; `Slot-image generation: g<run>-<sha12> (newest|last-good)` in the swarm `plan` job. On a registry logged in to GHCR, the digests of `<slot>:g<run>-<sha12>` and `<slot>:latest` match and `<slot>:last-good` is the previously live digest (absent on the first publish).

  **Negative control.** A deliberately broken Dockerfile layer that fails one digest check: the run is red, all ten `:latest` and `:last-good` digests are unchanged, no generation record is uploaded, and the next swarm plan still starts from the previous complete generation. With no ledger artifact, the plan logs `no complete slot-image generation and no last good generation, so the wave waits` and starts zero workers.

  **Rollback.**

  ```bash
  git apply -R /tmp/slot-image-generations.patch
  git diff --check
  git add .github/workflows/swarm-images.yml .github/workflows/swarm.yml
  git commit -m "Revert immutable slot-image generation publishing"
  ```

  Re-run both workflows: `Swarm slot images` pushes `:latest` directly again and `Agent swarm` passes `image: ...:latest`. Caveat: published immutable generation tags and the ledger are not deleted; a corrected publish must use a new generation tag.

### WFAUDIT-04 — first release must not fail for a missing architecture tag (proposed workflow patch)

- [ ] **Proposed workflow patch, not applied.** Source document: [first-release-architecture-tag.md](first-release-architecture-tag.md). Target file: `.github/workflows/delivery.yml`, `release` job. Requires WEB-11 release authority for the tag verification step.

  **Apply.** Extract the single `diff` block and apply it, then run the local gates:

  ```bash
  awk '/^```diff$/{in_block=1; next} /^```$/{in_block=0} in_block' \
    docs/operations/workflow-changes/first-release-architecture-tag.md \
    > /tmp/first-release-architecture-tag.patch
  git apply --check /tmp/first-release-architecture-tag.patch
  git apply /tmp/first-release-architecture-tag.patch
  git diff --check
  python3 -m venv "$TMPDIR/actionlint" && "$TMPDIR/actionlint/bin/pip" install -q actionlint-py==1.7.12.25 && "$TMPDIR/actionlint/bin/actionlint"
  bash -n <(sed -n '/set -euo pipefail/,/gh release create/p' .github/workflows/delivery.yml)
  python3 -I scripts/check-docs.py
  python3 -I -m unittest discover -s scripts/tests -q
  git add .github/workflows/delivery.yml
  git commit -m "Fix first release of a version when the architecture tag is absent"
  ```

  **Workflow on GitHub.** Open a pull request and confirm every required check on the exact SHA is green, then from a green default-branch commit create and push an authorized annotated pre-release tag such as `v0.1.0-rc.<n>`. Watch both `Delivery` and `Release app image` for that tag.

  **Green looks like.** `Delivery` → the `verify` job's two matrix legs (`Verify and smoke image (amd64)` and `Verify and smoke image (arm64)`) succeed and upload `smoke-tested-image-*`; `Delivery` → `release` succeeds; the multi-architecture manifest lists `linux/amd64` and `linux/arm64`. A re-run of the same tag's `Delivery` workflow also succeeds and produces the same manifest digest.

  **Proving log line.** In `release`: no `An image does not exist locally with the tag`, no `.Id` mismatch, no `refusing to overwrite`; the step writes `Smoke-tested image: <digest>` to `release-notes.md` (it is not printed to the log) and runs `gh release create ... --prerelease`, so confirm the digest in the GitHub release notes and the pre-release marker on the release. `docker buildx imagetools inspect ghcr.io/<owner>/<repo>:latest` must not resolve to the pre-release.

  **Negative control.** A same version tag with a different revision fails before any registry write (dry-run scenario D in the source document); a push with no recorded `RepoDigest` fails closed with `no registry digest for <child>`; a transient pull failure for an already published child fails closed with `cannot pull existing <child>; refusing to overwrite`.

  **Rollback.**

  ```bash
  git apply -R /tmp/first-release-architecture-tag.patch
  git diff --check
  git add .github/workflows/delivery.yml
  git commit -m "Revert first-release architecture tag fix"
  ```

  Re-run the workflow to confirm the old behaviour is restored. Caveat: rolling back reintroduces the blocking first-release defect; do not attempt a release while reverted. Published tags and manifests are immutable, so use a new tag for any corrected release.

### WFAUDIT-01 — candidate validation isolation (proposed workflow patch, blocked)

- [ ] **Proposed workflow patch, not applied.** Source document: [candidate-validation-isolation.md](candidate-validation-isolation.md). Target files: `.github/workflows/swarm-worker.yml`, `.github/workflows/swarm-integrator.yml`, `.github/workflows/fix-pr-gate.yml`. Requirements OPS-06, WEB-12. **Blocked** until the prerequisite below lands.

  **Prerequisite.** Land the two trusted `scripts/swarm.py` subcommands specified in the source document: `integrate-validate --queue <json> --gate <script> --out <dir>` (merge and gate locally, emit `accepted` and `conflicted`, write `manifest.json` and `main.bundle`, never push, comment or label) and `integrate-publish --manifest <file> --bundle <file> --gate <script>` (re-check every SHA then push and report; never invoke the gate or check out candidate code). Both must be covered by tests under `scripts/tests` before the workflow patch is applied.

  **Apply.** Once the prerequisite exists, extract the single `diff` block and apply it:

  ```bash
  awk '/^```diff$/{in_block=1; next} /^```$/{in_block=0} in_block' \
    docs/operations/workflow-changes/candidate-validation-isolation.md \
    > /tmp/candidate-validation-isolation.diff
  git apply --check /tmp/candidate-validation-isolation.diff
  git apply /tmp/candidate-validation-isolation.diff
  git diff --check
  python3 -m venv "$TMPDIR/actionlint" && "$TMPDIR/actionlint/bin/pip" install -q actionlint-py==1.7.12.25 && "$TMPDIR/actionlint/bin/actionlint"
  python3 -I -m unittest discover -s scripts/tests -q
  python3 -I scripts/check-docs.py
  python3 -I scripts/check-secrets.py
  git add .github/workflows/swarm-worker.yml .github/workflows/swarm-integrator.yml .github/workflows/fix-pr-gate.yml
  git commit -m "Isolate candidate validation from publication credentials"
  ```

  **Workflow on GitHub.** Three dispatches with synthetic inputs:

  1. `Agent swarm` with `workers=1` and one harmless documentation task. `Swarm worker run` is reusable (`workflow_call`) and has no `workflow_dispatch` trigger, so it must be reached through `Agent swarm`.
  2. `Swarm integrator` with one ready synthetic pull request.
  3. `Fix PR gate` — open a synthetic fix pull request (`FIX-` title or `autofix` label); this workflow is pull-request-event only, so it cannot be dispatched by hand.

  **Green looks like.** The worker run shows jobs `work`, `validate`, `publish`. The integrator run shows `pick`, `validate`, `publish` and `resolve` only if there is a conflict — the old job named `integrate` is gone. The fix-PR run shows `validate` green with no `NVD_API_KEY` in its step and `handoff` writing after a SHA match. Required `Delivery` checks on every pushed SHA are green.

  **Proving log line.** `Run the trusted gate on the candidate tree (no token, no secrets)` exits 0 and emits a non-empty `sha`; `Re-check that the bundle is exactly the validated commit` reports no mismatch; the integrator `publish` shows the SHA re-check followed by a single push; the fix `handoff` logs `Re-check the validated SHA before touching the repository` with a matching SHA.

  **Negative control.** Emptying the candidate bundle in a scratch dispatch makes `publish` log `Candidate was not validated` and create no branch or pull request. A batch whose every pull request conflicts skips `publish` but still starts `resolve` with a non-empty `CONFLICTED` value. A grep of the patched integrator file finds no `needs.integrate` and no `needs.publish.outputs.conflicted`. A fix pull request whose head moves between jobs fails with `PR moved after validation`.

  **Rollback.**

  ```bash
  git apply -R /tmp/candidate-validation-isolation.diff
  git diff --check
  git add .github/workflows/swarm-worker.yml .github/workflows/swarm-integrator.yml .github/workflows/fix-pr-gate.yml
  git commit -m "Revert candidate validation isolation"
  ```

  Revert the two subcommands so the workflows call `integrate-batch` again, then re-run the three dispatches and confirm the old `integrate` job name and behaviour return. Workflow files are protected: only a person performs this rollback.

## Blocked prerequisite

- [ ] **No task currently covers the two `scripts/swarm.py` subcommands required by WFAUDIT-01.** A repository search for `integrate-validate` and `integrate-publish` returned no matches in `scripts/swarm.py`. The WFAUDIT-01 document specifies their contract, and the task itself records that the integrator hunk must not be applied alone. A person must implement and test them (or raise an audit finding for them) before the WFAUDIT-01 box above can be ticked. This checklist records the gap; it does not implement the subcommands.

## Final gate

- [ ] **Clean `Delivery` run on the final `main` commit.** Dispatch `Delivery` on the final SHA and confirm all required checks listed in [Purpose and rules](#purpose-and-rules) are green. Record the run URL and SHA in the result table. Nothing is verified until this run is green.

## Result table

All cells are intentionally unfilled; a person fills them after running each workflow.

| Task | Date | Run URL | Exact SHA | Proving log line | Result |
|---|---|---|---|---|---|
| WFAUDIT-02 | not run | not run | not run | not run | unchecked |
| WFAUDIT-05 | not run | not run | not run | not run | unchecked |
| WFAUDIT-06 | not run | not run | not run | not run | unchecked |
| WFAUDIT-07 | not run | not run | not run | not run | unchecked |
| WFAUDIT-08 | not run | not run | not run | not run | unchecked |
| WFAUDIT-03 | not run | not run | not run | not run | unchecked |
| WFAUDIT-04 | not run | not run | not run | not run | unchecked |
| WFAUDIT-01 prerequisite | not run | not run | not run | not run | unchecked |
| WFAUDIT-01 | not run | not run | not run | not run | unchecked |
| Final `Delivery` | not run | not run | not run | not run | unchecked |

## Findings for the person

1. The WFAUDIT-01 integrator patch is blocked on two trusted `scripts/swarm.py` subcommands that do not exist in the tree; no task among WFAUDIT-01 through WFAUDIT-08 implements them. This is the only hard prerequisite gap in the audit set.
2. The WFAUDIT-03 patch depends on WFAUDIT-02's `--generations` input; applying the workflow patch without the script change leaves the ledger ignored and the safety rule dormant.
3. Removing `NVD_API_KEY` from the WFAUDIT-01 validation jobs leaves OWASP Dependency-Check using the restored cache, which is the documented fallback. A credentialed cache-refresh job that runs no candidate code is the recommended follow-up; do not put the key back into a validation job.
4. WFAUDIT-04 leaves the parent-manifest `imagetools inspect` guard conflating "absent" with a transport error, and WFAUDIT-03 cannot restore `:latest` on a first publish if phase 2 fails midway. Both are recorded as residual risks in their source documents and are not fixed by this checklist.
5. `docs/swarm/BUILD-53.md` has an immutable unclosed code fence, which is why WFAUDIT-07's structural rules skip `docs/swarm/`. A maintainer should repair that record in a separate change before the skip is removed.

## Approval

Not applied and not verified. Every box remains unchecked until a person with workflow permission performs the apply and GitHub verification steps above and records the evidence. The evidence behind this checklist is file reads and the executed repository checks documented in the source notes; there is no simulated-user, human-participant or clinical evidence, and no GitHub run was performed by this worker.
