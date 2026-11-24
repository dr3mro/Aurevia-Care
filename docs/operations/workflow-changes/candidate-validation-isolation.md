# Candidate validation isolation — proposed workflow patch

Status: **proposed only**. No file under `.github/workflows` is changed by this
pull request. The unified diff below was produced from a scratch copy outside the
repository and verified with `git apply --check` against the current files; a
person with workflow-file permission applies it.

Requirements: OPS-06, WEB-12. The goal is that code from a candidate pull request
is never executed in a job or step that also holds a token able to push, publish
an image or write to the repository.

## Problem (evidence from the current files)

Line numbers refer to the files as they exist before this patch.

| Workflow | Job | Candidate code it runs | Credential in the same job | Finding |
|---|---|---|---|---|
| `.github/workflows/swarm-worker.yml` | `work` (lines 27-248) | `scripts/agent-run.sh implement`, `merge-main` and `bundle`; `bundle` runs `scripts/worker-check.sh` on the candidate tree (`agent-run.sh:251-272`) | `OPENCODE_API_KEY` (agent model key) and `secrets.GITHUB_TOKEN` used only as a container pull credential; job permissions are `contents: read`, `packages: read`, `issues: read` | No repository write token, so not a push/publish boundary. The candidate's own `checks` output is self-reported by this untrusted job. |
| `.github/workflows/swarm-worker.yml` | `publish` (lines 249-292) | None directly — it checks out `main`, downloads the worker artifact and runs trusted `scripts/swarm.py publish` | `GH_TOKEN: secrets.SWARM_TOKEN \|\| github.token`, `contents: write`, `pull-requests: write`, `issues: write`, `actions: read` | `publish` pushes the **candidate bundle** (`swarm.py:1541-1564`) and trusts the unvalidated `checks` flag from `work`. There is no validation job and no SHA cross-check between validation and publication. |
| `.github/workflows/swarm-integrator.yml` | `integrate` (lines 48-118) | Merges PR branches into local `main` and runs the full gate `scripts/integrator-gate.sh` through `integrate-batch` (lines 113-118, `swarm.py:2399-2436`) | `GH_TOKEN: secrets.SWARM_TOKEN \|\| github.token`, `NVD_API_KEY`, `contents: write`, `pull-requests: write`, `issues: write`, `actions: write` | **Primary finding.** Candidate code is compiled and executed in the job that owns the write token. `run_gate` (`swarm.py:2387-2396`) removes `GH_TOKEN`, `GITHUB_TOKEN` and `SWARM_TOKEN` from the gate's environment, but that is not a boundary: the parent `swarm.py` process still holds `GH_TOKEN`, a test process runs under the same UID and can read `/proc/<parent-pid>/environ`, and runner-provided tokens are not in the strip list. `NVD_API_KEY` is also present at job level. |
| `.github/workflows/swarm-integrator.yml` | `resolve` (lines 124-173) | Agent merges main into conflicted candidate branches and runs the quick check via `agent-run.sh resolve` | `OPENCODE_API_KEY`, `secrets.GITHUB_TOKEN` as a read-only image pull credential; `contents: read`, `packages: read` | No write token; push is isolated in `resolve-publish`. Model-key exposure remains (see Risks). |
| `.github/workflows/swarm-integrator.yml` | `resolve-publish` (lines 174-208) | None — trusted scripts from `main` | Write permissions and `secrets.SWARM_TOKEN \|\| github.token` | Correctly isolated. |
| `.github/workflows/fix-pr-gate.yml` | `validate` (lines 23-85) | Checks out the PR merge commit and runs `scripts/check.sh` from that tree | `permissions: contents: read`; no write token, but `NVD_API_KEY` secret at job level (line 71) | Already the right job split for write access. Remove the secret so validation is credential-free; the pass flag has no SHA binding, so a PR could move between validation and handoff. |
| `.github/workflows/fix-pr-gate.yml` | `handoff` (lines 86-130) | None — sparse checkout of trusted `scripts` from `main` | `pull-requests: write`, `issues: write`, `actions: write`, `GH_TOKEN: github.token` | Correctly isolated, but acts on `needs.validate.outputs.passed` without re-checking that the PR's commit is still the one validated. |

`docs/SWARM.md:3-24` describes the intended arrangement: the agent job never
holds a write token, and untrusted output is acted on by a second job on a fresh
runner that checks out `main`'s scripts. The integrator's `integrate` job is the
one place that still mixes the two.

## The split

Three jobs change. In every case the validation job has `permissions:
contents: read` (plus read-only `pull-requests`/`actions` where it must list PR
heads), **no secrets, no registry login and no write token**; the publication job
gets the credentials, consumes only the validated commit SHA and manifest, and
never checks out or executes the candidate's code.

1. `swarm-worker.yml`: add `validate` between `work` and `publish`. It resolves
   the candidate commit from the worker bundle, checks out trusted scripts from
   `main`, materialises the candidate tree in a detached worktree, copies the
   trusted `scripts/` over it, and runs `scripts/worker-check.sh` there. It emits
   `passed` and `sha`. `publish` now depends on `both`, refuses to push when
   `passed != true`, re-fetches the bundle and fails unless its HEAD equals the
   validated SHA, and only then calls the unchanged trusted `swarm.py publish`.
2. `swarm-integrator.yml`: rename `integrate` to `validate`, drop all write
   permissions and the `SWARM_TOKEN` / `NVD_API_KEY` secrets, and call a new
   trusted `integrate-validate` subcommand that merges and gates but does not
   push. Add a `publish` job that holds the write token, downloads the validated
   manifest and bundle, and calls a new trusted `integrate-publish` subcommand
   that re-checks every SHA before pushing and reporting. `resolve` still follows
   `publish`, but reads `conflicted` from `validate` directly, so it also runs when
   `publish` is skipped (a batch with conflicts but no accepted PRs).
3. `fix-pr-gate.yml`: the existing `validate` job records `git rev-parse HEAD`
   as `sha` and loses its `NVD_API_KEY`. The `handoff` job re-reads the PR's
   current `merge_commit_sha` and aborts unless it still equals the validated
   SHA before it labels, comments or dispatches the integrator.

### Artifacts between the jobs

| From | Artifact / output | To | Check performed by the consumer |
|---|---|---|---|
| worker `work` | `worker-<slot>` artifact (`result.bundle`, `result`, `checks`, `branch`) | worker `validate` | Bundle must exist and be non-empty. |
| worker `validate` | outputs `passed`, `sha`; `validate-<slot>` log artifact | worker `publish` | `passed == true`, `sha` non-empty, bundle HEAD `== sha` before push. |
| integrator `pick` | outputs `queue`, `count`, `image` | integrator `validate` | Same queue the planner produced. |
| integrator `validate` | outputs `accepted`, `conflicted`; `validated-batch` artifact containing `manifest.json` and `main.bundle` | integrator `publish` and `resolve` (both read `validate`'s outputs directly) | Each PR's current `headRefOid == head_sha` and bundle HEAD `== merge_sha`; `resolve` uses `validate`'s `conflicted` output, not `publish`'s, so it is not skipped when `publish` has no accepted PRs. |
| fix `validate` | outputs `passed`, `sha`; `fix-pr-gate-log` artifact | fix `handoff` | PR `merge_commit_sha == sha` before any write. |

### Fix pass (2026-11-21)

The first draft of this patch had two defects in the `resolve` wiring; both are
corrected in the diff below and re-verified with `git apply --check` and a YAML
parse of the patched files:

- `resolve`'s `CONFLICTED` environment value still read
  `${{ needs.integrate.outputs.conflicted }}` after `integrate` was renamed to
  `validate`, so the conflict agent received an empty list. It now reads
  `${{ needs.validate.outputs.conflicted }}`.
- `resolve`'s `if` read `needs.publish.outputs.conflicted`, but `publish` is
  skipped when `accepted` is empty. A batch made only of conflicting PRs would
  skip `resolve` entirely. `resolve` now reads `validate`'s output directly and
  `publish`'s unused `conflicted` output was removed.

## Prerequisite (blocking) — trusted script change

The integrator part of the patch calls two small subcommands that do not exist
yet. They modify a protected file, so a person must add them first; the workflow
patch below is written against this contract and will fail at startup until they
land.

- `python3 -I scripts/swarm.py integrate-validate --queue <json> --gate <script>
  --out <dir>`: for each queued PR, read `headRefOid` with the read-only token,
  fetch the PR head, merge locally into `main`, reject a PR whose head moved
  after planning, run the gate with credentials stripped, write
  `manifest.json` entries `{pr, task_id, head_sha, merge_sha}` for accepted PRs,
  write `main.bundle` of the final gated tip, and emit `accepted` and
  `conflicted` outputs. It must not push, comment, label or open issues. Keep
  the existing `run_gate` credential stripping; it is now defence in depth.
- `python3 -I scripts/swarm.py integrate-publish --manifest <file> --bundle
  <file> --gate <script>`: on a fresh runner with the write token, re-verify each
  PR's current `headRefOid` and the bundle HEAD against the manifest, then
  perform the guard / comment / label / `record_merge` / push / concurrency
  reconciliation. It must never invoke the gate and never check out candidate
  branch code.

The worker and fix-PR parts need no script change: they reuse the existing
`swarm.py publish` and the existing inline shell primitives.

## Proposed patch

This is the exact output of `diff -u` between the current files and the scratch
copies. `git apply --check` against the current repository passes.

```diff
--- a/.github/workflows/swarm-worker.yml
+++ b/.github/workflows/swarm-worker.yml
@@ -246,9 +246,85 @@
           if-no-files-found: ignore
           # Kept for a week so a blocked or failed publish never loses the work.
           retention-days: 7
+  # Candidate validation is isolated from publication. This job runs the
+  # candidate code (the work job's bundle) with NO secrets, NO registry login and
+  # read-only contents. It emits the exact commit SHA it validated plus a pass
+  # flag; the publish job below only consumes those outputs, never branch code.
+  validate:
+    name: Validate candidate commit (no secrets)
+    needs: work
+    if: always()
+    runs-on: ubuntu-24.04
+    timeout-minutes: 45
+    permissions:
+      contents: read
+    outputs:
+      passed: ${{ steps.gate.outputs.passed }}
+      sha: ${{ steps.candidate.outputs.sha }}
+    services:
+      postgres:
+        image: postgres:17.12@sha256:b60e90042aa6b8dbecd58e224b3e36114dbf8ad754465264c2476ad58a86e45c
+        env:
+          POSTGRES_HOST_AUTH_METHOD: trust
+        ports:
+          - 5432:5432
+        options: >-
+          --health-cmd "pg_isready -U postgres"
+          --health-interval 10s --health-timeout 5s --health-retries 5
+    env:
+      OREVIA_PG_ADMIN: postgres
+    steps:
+      # Trusted scripts from main; the candidate source comes only from the bundle.
+      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
+        with:
+          ref: ${{ github.event.repository.default_branch }}
+          fetch-depth: 0
+          persist-credentials: false
+      - uses: actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880 # v6
+        with:
+          distribution: temurin
+          java-version: '21'
+          cache: maven
+      - uses: ./.github/actions/setup-tooling-python
+      - name: Download the worker bundle
+        uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8
+        with:
+          name: worker-${{ inputs.slot }}
+          path: results/worker-${{ inputs.slot }}
+      - name: Resolve the candidate commit and put trusted scripts in its tree
+        id: candidate
+        env:
+          SLOT: ${{ inputs.slot }}
+        run: |
+          set -euo pipefail
+          bundle="results/worker-$SLOT/result.bundle"
+          if [[ ! -s "$bundle" ]]; then echo "no candidate bundle" >&2; exit 1; fi
+          git fetch -q "$bundle" HEAD:refs/remotes/candidate
+          sha=$(git rev-parse refs/remotes/candidate)
+          echo "sha=$sha" >> "$GITHUB_OUTPUT"
+          git worktree add -q --detach candidate-tree "$sha"
+          cp -R scripts/. candidate-tree/scripts/
+      - name: Run the trusted gate on the candidate tree (no token, no secrets)
+        id: gate
+        env:
+          GATE: scripts/worker-check.sh
+        run: |
+          set +e
+          (cd candidate-tree && bash "$GATE") > validate.log 2>&1
+          code=$?
+          set -e
+          tail -80 validate.log
+          if [[ "$code" -eq 0 ]]; then echo "passed=true" >> "$GITHUB_OUTPUT"; fi
+      - uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7
+        if: always()
+        with:
+          name: validate-${{ inputs.slot }}
+          path: validate.log
+          if-no-files-found: ignore
+          retention-days: 3
   publish:
     name: Publish result
-    needs: work
+    needs: [work, validate]
     if: always()
     runs-on: ubuntu-latest
     timeout-minutes: 10
@@ -257,6 +333,12 @@
       pull-requests: write
       issues: write
       actions: read
+    # The publication job holds credentials but runs NO candidate code. It takes
+    # only the SHA that the validate job proved, and re-checks the bundle against
+    # it before any push. It never checks out a branch from the worker.
+    env:
+      VALIDATED_SHA: ${{ needs.validate.outputs.sha }}
+      VALIDATED: ${{ needs.validate.outputs.passed }}
     steps:
       # Trusted copy of the scripts: the work job never had a token and cannot
       # alter what runs here.
@@ -272,6 +354,27 @@
         with:
           name: worker-${{ inputs.slot }}
           path: results/worker-${{ inputs.slot }}
+      - name: Refuse to publish an unvalidated candidate
+        env:
+          PASSED: ${{ needs.validate.outputs.passed }}
+          SHA: ${{ needs.validate.outputs.sha }}
+        run: |
+          if [[ "$PASSED" != "true" || -z "$SHA" ]]; then
+            echo "::error::Candidate was not validated; no branch or pull request will be published."
+            echo "PUBLISH=skip" >> "$GITHUB_ENV"
+          fi
+      - name: Re-check that the bundle is exactly the validated commit
+        if: env.PUBLISH != 'skip'
+        env:
+          SLOT: ${{ inputs.slot }}
+        run: |
+          set -euo pipefail
+          bundle="results/worker-$SLOT/result.bundle"
+          test -s "$bundle"
+          git fetch -q "$bundle" HEAD:refs/heads/swarm-incoming
+          actual=$(git rev-parse refs/heads/swarm-incoming)
+          [[ "$actual" == "$VALIDATED_SHA" ]] || {
+            echo "bundle $actual is not the validated commit $VALIDATED_SHA" >&2; exit 1; }
       - name: Warn when SWARM_TOKEN is not configured
         env:
           HAS_SWARM_TOKEN: ${{ secrets.SWARM_TOKEN != '' }}
@@ -280,6 +383,7 @@
             echo "::warning title=SWARM_TOKEN missing::Falling back to the job token: it cannot push workflow files or open pull requests that trigger other workflows."
           fi
       - name: Push the branch and open the pull request
+        if: env.PUBLISH != 'skip'
         env:
           GH_TOKEN: ${{ secrets.SWARM_TOKEN \|\| github.token }}
           # Issues and labels use the job's own token, which always has issues: write.
--- a/.github/workflows/swarm-integrator.yml
+++ b/.github/workflows/swarm-integrator.yml
@@ -45,21 +45,25 @@
           GH_TOKEN: ${{ github.token }}
         run: python3 -I scripts/swarm.py plan-integration
 
-  integrate:
-    name: Integrate the ready batch
+  # Candidate validation is isolated from publication. This job runs the full
+  # gate on the merge of main with each PR branch, with NO write token and NO
+  # secrets. It emits the exact PR head SHAs and merge-tree SHA it validated and
+  # uploads them as a manifest plus a git bundle. Reporting, labels and the push
+  # live in the publish job below, which never runs candidate code.
+  validate:
+    name: Validate the ready batch (no secrets)
     needs: pick
     if: needs.pick.outputs.count != '0'
     runs-on: ubuntu-24.04
-    # Up to ten PRs, each with the full gate, so 45 minutes cut batches short.
+    # Up to ten PRs, each with the full gate, so 120 minutes cut batches short.
     timeout-minutes: 120
     outputs:
-      conflicted: ${{ steps.merge.outputs.conflicted }}
+      conflicted: ${{ steps.candidate.outputs.conflicted }}
+      accepted: ${{ steps.candidate.outputs.accepted }}
     permissions:
-      contents: write
-      pull-requests: write
-      # Labels are written through the Issues API, so PR write alone is not enough.
-      issues: write
-      actions: write
+      contents: read
+      pull-requests: read
+      actions: read
     services:
       postgres:
         image: postgres:17.12@sha256:b60e90042aa6b8dbecd58e224b3e36114dbf8ad754465264c2476ad58a86e45c
@@ -71,10 +75,12 @@
           --health-cmd "pg_isready -U postgres"
           --health-interval 10s --health-timeout 5s --health-retries 5
     env:
-      GH_TOKEN: ${{ secrets.SWARM_TOKEN \|\| github.token }}
+      # Read-only token for listing PR heads only: no push, label or comment.
+      # No SWARM_TOKEN and no NVD_API_KEY: OWASP uses the restored cache, which is
+      # the documented fallback when the key is absent.
+      GH_TOKEN: ${{ github.token }}
       GH_ISSUES_TOKEN: ${{ github.token }}
       OREVIA_PG_ADMIN: postgres
-      NVD_API_KEY: ${{ secrets.NVD_API_KEY }}
     steps:
       - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
         with:
@@ -107,15 +113,51 @@
           done
           exit 1
       - uses: ./.github/actions/setup-tooling-python
-      # One job for the batch. The trusted command keeps GH_TOKEN for guard,
-      # merge and finalize, and strips it from the gate subprocess so agent-written
-      # code never sees a token. Delivery re-verifies main after the push.
-      - name: Merge each PR, gate, then push once
-        id: merge
+      - name: Merge each PR locally and gate it without credentials
+        id: candidate
         env:
           QUEUE: ${{ needs.pick.outputs.queue }}
           GATE: scripts/integrator-gate.sh
-        run: python3 -I scripts/swarm.py integrate-batch --queue "$QUEUE" --gate "$GATE"
+        run: python3 -I scripts/swarm.py integrate-validate --queue "$QUEUE" --gate "$GATE" --out validated
+      - uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7
+        if: always()
+        with:
+          name: validated-batch
+          path: validated
+          if-no-files-found: error
+          retention-days: 1
+  publish:
+    name: Publish the validated batch
+    needs: [pick, validate]
+    if: needs.validate.outputs.accepted != '' && needs.validate.outputs.accepted != '[]'
+    runs-on: ubuntu-latest
+    timeout-minutes: 20
+    permissions:
+      contents: write
+      pull-requests: write
+      # Labels are written through the Issues API, so PR write alone is not enough.
+      issues: write
+      actions: write
+    env:
+      GH_TOKEN: ${{ secrets.SWARM_TOKEN \|\| github.token }}
+      GH_ISSUES_TOKEN: ${{ github.token }}
+    steps:
+      # Trusted scripts from main in a runner the candidate code never touched.
+      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
+        with:
+          ref: main
+          fetch-depth: 0
+          persist-credentials: false
+      - uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8
+        with:
+          name: validated-batch
+          path: validated
+      - uses: ./.github/actions/setup-tooling-python
+      - name: Push only the exact validated commit SHAs
+        id: publish
+        env:
+          GATE: scripts/integrator-gate.sh
+        run: python3 -I scripts/swarm.py integrate-publish --manifest validated/manifest.json --bundle validated/main.bundle --gate "$GATE"
 
   # A PR that conflicts with main is not failed right away: the agent merges main
   # into its branch and fixes the conflicts, then a separate trusted job pushes
@@ -123,8 +165,8 @@
   # to the workers. The agent and the push token never share a runner.
   resolve:
     name: Resolve merge conflicts
-    needs: [pick, integrate]
-    if: always() && needs.integrate.outputs.conflicted != '' && needs.integrate.outputs.conflicted != '[]'
+    needs: [pick, validate, publish]
+    if: always() && needs.validate.outputs.conflicted != '' && needs.validate.outputs.conflicted != '[]'
     runs-on: ubuntu-latest
     timeout-minutes: 90
     container:
@@ -139,7 +181,7 @@
       run:
         shell: bash
     env:
-      CONFLICTED: ${{ needs.integrate.outputs.conflicted }}
+      CONFLICTED: ${{ needs.validate.outputs.conflicted }}
       BASE_BRANCH: ${{ github.event.repository.default_branch }}
       MODEL: opencode-go/hy4-flash-free
       SWARM: '1'
--- a/.github/workflows/fix-pr-gate.yml
+++ b/.github/workflows/fix-pr-gate.yml
@@ -34,6 +34,7 @@
       contents: read
     outputs:
       passed: ${{ steps.gate.outputs.passed }}
+      sha: ${{ steps.source.outputs.sha }}
     services:
       postgres:
         image: postgres:17.12@sha256:b60e90042aa6b8dbecd58e224b3e36114dbf8ad754465264c2476ad58a86e45c
@@ -52,6 +53,9 @@
           ref: ${{ github.event.pull_request.merge_commit_sha }}
           fetch-depth: 0
           persist-credentials: false
+      - name: Record the exact commit under test
+        id: source
+        run: echo "sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"
       - uses: actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880 # v6
         with:
           distribution: temurin
@@ -65,10 +69,11 @@
           restore-keys: |
             owasp-nvd-${{ runner.os }}-${{ runner.arch }}-
       - uses: ./.github/actions/setup-tooling-python
-      - name: Run the full gate (no GitHub token in this job)
+      # No secrets in this job: OWASP uses the restored cache, the documented
+      # fallback when NVD_API_KEY is absent. The write-token handoff re-checks
+      # the SHA below before it acts on the pass flag.
+      - name: Run the full gate (no GitHub token, no secrets in this job)
         id: gate
-        env:
-          NVD_API_KEY: ${{ secrets.NVD_API_KEY }}
         run: |
           set +e
           bash scripts/check.sh > fix-pr-gate.log 2>&1
@@ -98,6 +103,7 @@
       GH_TOKEN: ${{ github.token }}
       PR: ${{ github.event.pull_request.number }}
       PASSED: ${{ needs.validate.outputs.passed }}
+      VALIDATED_SHA: ${{ needs.validate.outputs.sha }}
     steps:
       # Trusted copy of the scripts: never the pull request's version.
       - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
@@ -110,6 +116,16 @@
         with:
           name: fix-pr-gate-log
           path: gate
+      - name: Re-check the validated SHA before touching the repository
+        env:
+          SHA: ${{ needs.validate.outputs.sha }}
+          PR_NUMBER: ${{ github.event.pull_request.number }}
+        run: |
+          set -euo pipefail
+          [[ -n "$SHA" ]] || { echo "no validated SHA" >&2; exit 1; }
+          current=$(gh api "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER" --jq .merge_commit_sha)
+          [[ "$current" == "$SHA" ]] || {
+            echo "PR moved after validation: $current != $SHA" >&2; exit 1; }
       - name: Hand a green fix to the integrator, send a red one back
         run: |
           # The integrator runs its own full gate before main moves, so a forged
```

## How to apply

A person with workflow-file permission, on a branch off the current default branch:

1. Land the two trusted-script subcommands above first (`scripts/swarm.py` is
   protected; the integrator opens an oversight issue for such a merge).
2. Save the diff block above to a scratch file outside the worktree, for example
   `/tmp/candidate-validation-isolation.diff`.
3. Run `git apply --check /tmp/candidate-validation-isolation.diff`, then
   `git apply /tmp/candidate-validation-isolation.diff`.
4. Run the local gates before pushing: `actionlint` (if available),
   `bash -n` on each changed shell block, `python3 -I -m unittest discover -s
   scripts/tests`, and `python3 -I scripts/check-docs.py`. Expected: no findings.
5. Commit only the three workflow files and push the branch. Never include a
   changed workflow file in a swarm worker's bundle — this document is the only
   file this task adds.

## How to verify on GitHub after the push

Run each workflow by hand with `workflow_dispatch` using a **synthetic** task and
read the job list, not just the run conclusion.

1. **Agent swarm** (or a direct dispatch of `Swarm worker run`) with one harmless
   documentation task. Green looks like: the run shows the jobs `work`,
   `validate` and `publish`; `validate` logs `Run the trusted gate on the
   candidate tree (no token, no secrets)` exiting 0 and emits a non-empty `sha`;
   `publish` logs `Re-check that the bundle is exactly the validated commit`
   with no mismatch, pushes the branch and opens a normal (non-draft) PR.
   Negative check: empty the candidate bundle in a scratch dispatch and confirm
   `publish` logs `Candidate was not validated` and creates no branch or PR.
2. **Swarm integrator** with one ready synthetic PR. Green looks like: jobs
   `pick`, `validate`, `publish` (and `resolve` only if there is a conflict);
   `validate` shows the full gate passing with no `secrets.*` in its step, and
   `publish` shows the SHA re-check followed by a single push. The required
   Delivery checks on the pushed SHA must be green. Confirm the old job named
   `integrate` is gone. Negative checks: dispatch a batch whose every PR
   conflicts — `publish` is skipped but `resolve` still starts with a non-empty
   `CONFLICTED` value (this proves both wiring defects above are fixed); and grep
   the patched file to confirm no `needs.integrate` reference remains.
3. **Fix PR gate**: open a synthetic fix PR (a `FIX-` title or `autofix` label).
   Green looks like: `validate` is green with no `NVD_API_KEY` in its step,
   `handoff` logs `Re-check the validated SHA before touching the repository`
   with a matching SHA, then labels the PR and dispatches the integrator. A PR
   whose head moves between the two jobs must fail that step with `PR moved
   after validation`.

## Rollback

`git revert` the commit (or apply the same patch in reverse with `git apply -R
/tmp/candidate-validation-isolation.diff`), and revert the two trusted-script
subcommands so the workflows call `integrate-batch` again. Re-run the same three
dispatches and confirm the old job names (`integrate`) and behaviour are back.
Workflow files are protected: only a person performs this rollback.

## Candidate code that still runs with a credential after the patch

Checked across all three patched files. There is **no** place where candidate
code runs with a token able to push, publish an image or write to the repository:

- `swarm-worker.yml`: `validate` runs candidate code with `contents: read` and
  no secrets or registry login; `publish` holds the write token but runs only
  trusted `main` scripts and never checks out candidate code.
- `swarm-integrator.yml`: `validate` runs candidate code with read-only
  permissions and no secrets; `publish` and `resolve-publish` hold write tokens
  and run only trusted `main` scripts.
- `fix-pr-gate.yml`: `validate` runs candidate code with `contents: read` and no
  secrets; `handoff` holds write permissions and runs only trusted `main`
  scripts.

The following non-write credentials still coexist with code generation or
execution; they are named explicitly and tracked as risks, not as findings of
the push/publish class:

| Location | Credential | Why it is not a write boundary | Risk |
|---|---|---|---|
| `swarm-worker.yml` `work`, `swarm-integrator.yml` `resolve` | `OPENCODE_API_KEY` (slot-specific with a team fallback) and `secrets.GITHUB_TOKEN` as a read-only image-pull credential | The model key cannot write to GitHub; the job token's permissions are `contents: read`, `packages: read`, `issues: read` | A prompt-injection in candidate text could exfiltrate the model key. Mitigate with per-slot keys and rotation. |
| validation jobs that use `actions/setup-java` with `cache: maven` and `actions/cache` | Runner-provided cache/runtime tokens | Scoped to the Actions cache service, not the repository or registry | Candidate code could read runner environment variables that are not in any strip list; they confer no repo write access. Keep validation jobs off container registries. |

## Remaining risks

1. **OWASP NVD freshness.** Removing `NVD_API_KEY` from validation jobs means
   OWASP Dependency-Check uses the restored cache — the documented fallback in
   `README-SETUP.md`, still failing on CVSS 7 or above. If fresh NVD data is
   required, add a separate `nvd-cache` job that holds the key but runs **no**
   candidate code and refreshes the cache before validation; do not put the key
   back into a validation job.
2. **Failed worker validation.** After the patch, a worker whose validation
   fails produces no branch and no pull request (the bundle stays in the
   7-day artifact). A follow-up trusted-script change should have `publish`
   open a hand-off issue in that case; until then the watchdog is the backstop.
3. **Manifest integrity.** `integrate-publish` trusts its downloaded manifest.
   Its SHA re-checks are the control; they must cover every accepted PR and the
   bundle HEAD, not just one. Add a unit test for an incomplete manifest before
   the change is considered safe.
4. **Other workflows.** Image publication lives in `release-app-image.yml` and
   the multi-architecture step of `delivery.yml`, which are outside this task's
   declared reading scope and are covered by the separate WFAUDIT-04 patch.
   The WFAUDIT-09 checklist must verify those jobs against the same rule.
5. **Defence in depth.** Keep `run_gate`'s environment stripping even though
   validation jobs no longer hold a write token; a future refactor could re-add
   one and the stripping would then still block the simplest exfiltration path.

## Findings for the next worker

- The primary credential/candidate mixture is `swarm-integrator.yml` `integrate`,
  not the worker. The existing two-job pattern in `fix-pr-gate.yml` is the model
  to copy; the worker and fix-PR patches are workflow-only, the integrator patch
  is blocked on two new `scripts/swarm.py` subcommands.
- `run_gate`'s env stripping is not a security boundary on its own (same-UID
  `/proc` read, plus runner tokens outside the strip list). Job-level isolation
  is the fix; stripping is only defence in depth.
- Evidence sources in this document: direct file reads of the three workflows,
  `scripts/swarm.py` and `scripts/agent-run.sh`, `docs/SWARM.md`, a scratch-copy
  `diff -u`, `git apply --check`, and PyYAML parsing of the resulting files. No
  reviewer, simulated user or human participant evidence; no GitHub run was
  performed because this task does not hold a token or push access.
