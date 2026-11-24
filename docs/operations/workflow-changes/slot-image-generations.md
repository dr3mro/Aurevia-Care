# Slot image generations — proposed workflow patch

- Status: **proposed only, not applied**. A swarm worker cannot change files under `.github/workflows` (the swarm token has no workflow permission), so this document is the hand-off: a person with workflow-file permission applies the diff below.
- Date: 2026-11-21
- Requirement IDs: OPS-06
- Risk class: cross-boundary (delivery pipeline and the worker image supply chain)
- Target files: [`.github/workflows/swarm-images.yml`](../../../.github/workflows/swarm-images.yml) (publish) and [`.github/workflows/swarm.yml`](../../../.github/workflows/swarm.yml) (`plan` and `worker` jobs).
- Reviewed, **not changed**: [`.github/workflows/agent-image.yml`](../../../.github/workflows/agent-image.yml), [`.github/workflows/swarm-worker.yml`](../../../.github/workflows/swarm-worker.yml), `the current swarm slot configuration at `.opencode/config/slots.json`, and the generation helpers in [`scripts/swarm.py`](../../../scripts/swarm.py) added by WFAUDIT-02.

## Problem (evidence from the current files)

1. **Workers pull a moving pointer.** `swarm.yml:160` passes
   `image: ${{ needs.plan.outputs.image_prefix }}-${{ matrix.slot }}:latest` to
   `swarm-worker.yml`, whose `work` job starts that image (`swarm-worker.yml:34`).
   `swarm-images.yml:58-59` builds and pushes `:latest` directly. A cancelled or
   partially completed publish therefore changes what a wave boots; a worker can
   start on a half-replaced image with no record of which bits it ran.
2. **The plan already knows how to choose a generation but is not told one.**
   WFAUDIT-02 added `generation_tag()`, `pick_generation()`, `last_good()` and
   `wave_generation()` in `scripts/swarm.py:1015-1084` and the `--generations`
   CLI input (`scripts/swarm.py:2887,2963`). `images_ready_command()` emits
   `generation` and `generation_source` (`scripts/swarm.py:1115-1126`), but
   `swarm.yml:109` still calls `images-ready` with no arguments: generations are
   `unmanaged`, the extra outputs are empty, and the worker cannot use them.
3. **No immutable artifact or provenance is published.** `swarm-images.yml`
   records no digest and no per-generation record, so neither a person nor the
   plan job can prove which slot image a wave used or fall back to the previous
   good generation when a publish is interrupted.

## Design

### Publish — `swarm-images.yml`

The build step becomes two phases inside one job (the existing `swarm-images`
concurrency group, `cancel-in-progress: false`, serializes publishes):

1. Compute `generation = g<run>-<sha12>` by calling the same
   `swarm.generation_tag()` helper the plan uses, so the tag format cannot drift.
2. Build and push **only** `<slot>:<generation>` for every slot. Verify each tag
   resolves to a real `sha256:` manifest with `docker buildx imagetools inspect`
   (the same check `agent-image.yml:54-55` already uses). Record one
   `{slot, exists: true, digest}` entry per slot.
3. Only after every digest is verified, move the two moving pointers:
   `<slot>:last-good` receives the previously live image and `<slot>:latest`
   receives the new generation. An `ERR`/`TERM` trap restores the old `:latest`
   manifest if phase 2 fails or the job is cancelled part way through, and the
   job fails closed. The generation value is printed to the step log as well as
   recorded as a step output.
4. Upload a `slot-image-generations` artifact containing `generations.json`
   (newest first, capped at 20 entries) in exactly the shape WFAUDIT-02 expects:
   `{"tag": "g<run>-<sha12>", "run_number": N, "sha": "...",
   "slots": [{"slot": 1, "exists": true, "digest": "sha256:..."}, ...]}`.
   The previous ledger is taken from the newest run that actually carries the
   artifact, not the newest run by status (a run whose jobs were skipped is green
   but uploads nothing). A missing or expired ledger becomes `[]`, never a
   guessed record.

No immutable tag is ever deleted or overwritten: a new run gets a new
`g<run>-<sha12>` tag. Only `:latest` and `:last-good` move.

### Plan and worker — `swarm.yml`

The `plan` job scans the newest `swarm-images.yml` runs for one that actually
carries a usable `slot-image-generations` artifact (a status filter is not
enough: a run whose jobs were skipped is green but uploads nothing) and
passes it to `images-ready --generations`. Its outputs `generation` and
`generation_source` become job outputs; `plan` logs the choice for auditability.
The `worker` call then starts
`image: <prefix>-<slot>:${{ needs.plan.outputs.generation || 'latest' }}` —
the generation the plan chose, with `:latest` only as the unmanaged fallback.

**Fail closed:** an empty ledger means no complete generation, so WFAUDIT-02's
`wave_generation()` returns `none`, `images-ready` reports `ready=false` and the
wave starts no workers. A wave never starts from a half-written pointer.

## Synthetic verification (tool evidence, before the patch was applied)

The exact shell of the patched publish step was extracted from the scratch copy
and run with stubbed `docker` and `gh` and synthetic values only (`owner/repo`,
run 42, sha `abcdef123456…`, digests `sha256:old<n>` / `sha256:new<n>`). The
`gh` stub returned a newer run (99) with no artifact before one (100) with an
artifact, and placed the ledger in `DIR/slot-image-generations/` to match the
real CLI extraction layout. The harness lived outside the repository
(`/tmp/opencode/wfaudit03/`) and is **not** part of this change.

| Scenario | Observed result |
|---|---|
| A. All ten digests verify | exit 0; ten `g42-abcdef123456` tags pushed; ten `:last-good` tags point to the old digests; all ten `:latest` point to the new digests; ledger has the complete new record plus the prior generation |
| B. One digest check fails (slot 2) | exit 1; only two generation tags pushed; **all ten `:latest` unchanged**; no `:last-good`; no ledger uploaded |
| C. Failure during the pointer move (slot 3 `:latest` push) | exit 1; ERR trap restores **all ten `:latest`** to their old digests; no ledger uploaded |
| D. First publish, failure during the pointer move (no prior `:latest`) | exit 1; the two slots already pushed keep their new `:latest`, the other eight have none; no `:last-good`, no ledger uploaded — see Residual risks |

The patched plan `ledger` step was also run with the stubs: it skipped run 99
(no artifact) and read the ledger from run 100's nested artifact directory.

The plan side was exercised with the real `scripts/swarm.py` helper and the same
stubs: a complete newest record reports `generation=g42-abcdef123456`,
`generation_source=newest`, `ready=true`; an incomplete newest record falls back
to `generation=g41-111111111111`, `generation_source=last-good`; an empty ledger
reports `generation_source=none`, `ready=false` and the wave waits.

`bash -n` and `shellcheck -s bash` were clean on every changed shell block,
`yaml.safe_load` parsed both patched files, and `git apply --check` accepted the
diff below against the pristine repository files.

## The patch

The diff below is the exact output of `diff -u` between the current files and
scratch copies, with stable `a/` and `b/` labels. It contains exactly one
`diff` block and applies against the current tree.

```diff
--- a/.github/workflows/swarm-images.yml
+++ b/.github/workflows/swarm-images.yml
@@ -15,6 +15,8 @@
 permissions:
   contents: read
   packages: write
+  # Reading the previous generation ledger is an Actions artifact read.
+  actions: read
 # Queue: a cancelled slot-image publish leaves workers on a stale base.
 concurrency:
   group: swarm-images
@@ -39,22 +41,96 @@
             .github/agent-image
       # One runner builds every slot: the slot layer is tiny and the base image
       # is pulled once, so ten separate runners only added startup time.
-      - name: Build and push the slot images
+      - name: Build, verify and publish one immutable generation
+        id: publish
         env:
           GH_TOKEN: ${{ github.token }}
+          GENERATION_RUN: ${{ github.run_number }}
+          GENERATION_SHA: ${{ github.sha }}
         run: |
           set -euo pipefail
           base="ghcr.io/${GITHUB_REPOSITORY,,}-agent:latest"
           printf '%s' "$GH_TOKEN" | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
           docker pull "$base"
+          # Same helper the plan job uses, so the tag format cannot drift.
+          generation=$(python3 -I -c 'import sys; sys.path.insert(0, "scripts"); from swarm import generation_tag; print(generation_tag(sys.argv[1], sys.argv[2]))' "$GENERATION_SHA" "$GENERATION_RUN")
+          echo "generation=$generation"
+          echo "generation=$generation" >> "$GITHUB_OUTPUT"
           GITHUB_OUTPUT=slots.out python3 -I scripts/swarm.py slot-matrix
           slots=$(sed -n 's/^matrix=//p' slots.out | jq -r '.slot[]')
+          # The ledger is newest first. An empty ledger means no complete
+          # generation has been published yet, so the wave waits until one has.
+          # A run can be green with all jobs skipped (no artifact), so select the
+          # newest run that actually carries a usable ledger, not a status filter.
+          echo '[]' > previous.json
+          for run_id in $(gh run list --workflow swarm-images.yml --limit 10 --json databaseId --jq '.[].databaseId'); do
+            if gh run download "$run_id" --name slot-image-generations --dir previous >/dev/null 2>&1; then
+              ledger_file=$(find previous -type f -name generations.json -print -quit)
+              if [[ -n "$ledger_file" && -s "$ledger_file" ]]; then
+                cp "$ledger_file" previous.json
+                break
+              fi
+            fi
+            rm -rf previous
+          done
+          # Phase 1: immutable tags only. Nothing a wave reads is moved here, so
+          # a cancellation or a failed digest check cannot change what runs.
+          # Remember what each moving pointer referenced so phase 2 can be
+          # rolled back if it fails or the job is cancelled part way through.
+          declare -A previous_latest
+          : > slot-records.jsonl
           for slot in $slots; do
             image="ghcr.io/${GITHUB_REPOSITORY,,}-agent-${slot}"
             config=$(python3 -I scripts/swarm.py slot-config --slot "$slot")
             docker build -f .github/agent-image/Dockerfile.slot \
               --build-arg "BASE=$base" --build-arg "SLOT=$slot" --build-arg "SLOT_CONFIG=$config" \
               --label "org.opencontainers.image.source=https://github.com/$GITHUB_REPOSITORY" \
-              -t "$image:latest" .github/agent-image
+              --label "org.opencontainers.image.revision=$GENERATION_SHA" \
+              -t "$image:$generation" .github/agent-image
+            docker push "$image:$generation"
+            # Verify the immutable tag resolves to a real manifest before any
+            # moving pointer is touched; fail closed when no digest is recorded.
+            digest=$(docker buildx imagetools inspect "$image:$generation" \
+              --format '{{json .Manifest}}' | jq -er .digest)
+            [[ "$digest" == sha256:* ]] || { echo "no digest for $image:$generation" >&2; exit 1; }
+            jq -nc --argjson slot "$slot" --arg digest "$digest" \
+              '{slot: $slot, exists: true, digest: $digest}' >> slot-records.jsonl
+            # Keep the previously live image locally so :last-good can be moved
+            # only after every digest in this generation has been verified.
+            if previous=$(docker buildx imagetools inspect "$image:latest" --format '{{json .Manifest}}' 2>/dev/null | jq -er .digest); then
+              previous_latest["$slot"]="$previous"
+              docker pull "$image:latest" >/dev/null 2>&1 || true
+              docker tag "$image:latest" "$image:last-good"
+            fi
+          done
+          # Phase 2: every digest is verified, so move the moving pointers.
+          # Restore the old :latest manifest if any step here fails or the job
+          # is cancelled, so a partial move is never left behind.
+          restore_latest() {
+            for slot in "${!previous_latest[@]}"; do
+              image="ghcr.io/${GITHUB_REPOSITORY,,}-agent-${slot}"
+              docker buildx imagetools create --tag "$image:latest" "$image@${previous_latest[$slot]}" || true
+            done
+            exit 1
+          }
+          trap 'restore_latest' ERR TERM
+          for slot in $slots; do
+            image="ghcr.io/${GITHUB_REPOSITORY,,}-agent-${slot}"
+            if docker image inspect "$image:last-good" >/dev/null 2>&1; then
+              docker push "$image:last-good"
+            fi
+            docker tag "$image:$generation" "$image:latest"
             docker push "$image:latest"
           done
+          trap - ERR TERM
+          # Record the generation for the plan job's wave_generation choice.
+          jq -s --arg tag "$generation" --argjson run "$GENERATION_RUN" --arg sha "$GENERATION_SHA" \
+            '{tag: $tag, run_number: $run, sha: $sha, slots: .}' slot-records.jsonl > record.json
+          # Newest first, bounded so the artifact cannot grow without limit.
+          jq -s '.[0:1] + .[1]' record.json previous.json | jq '.[0:20]' > generations.json
+      - uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7
+        with:
+          name: slot-image-generations
+          path: generations.json
+          if-no-files-found: error
+          retention-days: 90
--- a/.github/workflows/swarm.yml
+++ b/.github/workflows/swarm.yml
@@ -70,6 +70,8 @@
       matrix: ${{ steps.plan.outputs.matrix }}
       count: ${{ steps.plan.outputs.count }}
       image_prefix: ${{ steps.image.outputs.prefix }}
+      generation: ${{ steps.images.outputs.generation }}
+      generation_source: ${{ steps.images.outputs.generation_source }}
     steps:
       - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
         with:
@@ -102,11 +104,33 @@
       # a wave is blocked only while a build is queued/in progress or the newest
       # completed build failed. With no run history at all (cleaned or expired),
       # the slot images in GHCR decide instead of an empty `gh run list`.
+      # The publish workflow records each complete generation newest first.
+      # A missing or expired ledger becomes [] so images-ready finds no complete
+      # generation and the wave waits rather than pulling a half-written :latest.
+      - id: ledger
+        if: steps.budget.outputs.start == 'true'
+        env:
+          GH_TOKEN: ${{ github.token }}
+        run: |
+          set -euo pipefail
+          echo '[]' > generations.json
+          # A successful run can have every job skipped and no artifact. Take
+          # the newest run that actually carries a usable ledger instead.
+          for run_id in $(gh run list --workflow swarm-images.yml --limit 10 --json databaseId --jq '.[].databaseId'); do
+            if gh run download "$run_id" --name slot-image-generations --dir ledger >/dev/null 2>&1; then
+              ledger_file=$(find ledger -type f -name generations.json -print -quit)
+              if [[ -n "$ledger_file" && -s "$ledger_file" ]]; then
+                cp "$ledger_file" generations.json
+                break
+              fi
+            fi
+            rm -rf ledger
+          done
       - id: images
         if: steps.budget.outputs.start == 'true'
         env:
           GH_TOKEN: ${{ github.token }}
-        run: python3 -I scripts/swarm.py images-ready
+        run: python3 -I scripts/swarm.py images-ready --generations "$(cat generations.json)"
       # Marks every task it starts as [-] in tasks/ and pushes that to main
       # (GITHUB_TOKEN pushes do not trigger another run).
       - id: plan
@@ -118,6 +142,8 @@
           REPAIR_RUN: ${{ inputs.repair_run }}
           IMAGES_READY: ${{ steps.images.outputs.ready }}
           IMAGES_REASON: ${{ steps.images.outputs.reason }}
+          GENERATION: ${{ steps.images.outputs.generation }}
+          GENERATION_SOURCE: ${{ steps.images.outputs.generation_source }}
         run: |
           if [[ "$IMAGES_READY" != true ]]; then
             echo "Prepared images not ready: $IMAGES_REASON; skipping this wave." >&2
@@ -125,6 +151,7 @@
             echo 'count=0' >> "$GITHUB_OUTPUT"
             exit 0
           fi
+          echo "Slot-image generation: $GENERATION ($GENERATION_SOURCE)"
           python3 -I scripts/swarm.py ensure-labels
           # Never plan more workers than the remaining monthly budget can pay for.
           if [[ "$BUDGET_WORKERS" =~ ^[0-9]+$ ]] && (( BUDGET_WORKERS < WORKERS )); then
@@ -157,7 +184,9 @@
     uses: ./.github/workflows/swarm-worker.yml
     secrets: inherit
     with:
-      image: ${{ needs.plan.outputs.image_prefix }}-${{ matrix.slot }}:latest
+      # Workers start from the immutable generation the plan step chose. An
+      # empty generation only occurs on an unmanaged publish and keeps :latest.
+      image: ${{ needs.plan.outputs.image_prefix }}-${{ matrix.slot }}:${{ needs.plan.outputs.generation || 'latest' }}
       slot: ${{ format('{0}', matrix.slot) }}
       task: ${{ toJSON(matrix) }}
   # Integration runs inside the wave, after its workers: no workflow_run chain.
```

## How to apply

A person with workflow-file permission, from the repository root:

```bash
# Extract the single diff block from this document.
awk '/^```diff$/{in_block=1; next} /^```$/{in_block=0} in_block' \
  docs/operations/workflow-changes/slot-image-generations.md \
  > /tmp/slot-image-generations.patch

git apply --check /tmp/slot-image-generations.patch
git apply /tmp/slot-image-generations.patch
git diff --check

# Local gates the same way CI runs them (install actionlint if absent).
python3 -m venv "$TMPDIR/actionlint" && "$TMPDIR/actionlint/bin/pip" install -q actionlint-py==1.7.12.25 && "$TMPDIR/actionlint/bin/actionlint"
python3 -I scripts/check-docs.py
python3 -I -m unittest discover -s scripts/tests -q

git add .github/workflows/swarm-images.yml .github/workflows/swarm.yml
git commit -m "Publish slot images under immutable generation tags"
```

**Bootstrap:** after this lands, dispatch `Swarm slot images` once *before*
expecting a wave; until the first ledger artifact exists, the plan job
correctly refuses to plan any workers.

## How to verify on GitHub after the push

Run the workflows by hand with synthetic inputs; this document does not
authorize a release or a production deployment.

1. **Swarm slot images** — `Actions → Swarm slot images → Run workflow` (a
   `workflow_dispatch` on `main`). Green looks like: the `Slot images` job logs
   `generation=g<run>-<sha12>`; each slot logs its `imagetools inspect` digest
   check; the run ends with an uploaded `slot-image-generations` artifact whose
   `generations.json` has one complete entry with ten `slots`. Inspect the
   registry from a machine logged in to GHCR:

   ```bash
   image=ghcr.io/<owner>/<repo>-agent-1
   docker buildx imagetools inspect "$image:g<run>-<sha12>" --format '{{json .Manifest}}' | jq -er .digest
   docker buildx imagetools inspect "$image:latest" --format '{{json .Manifest}}' | jq -er .digest
   docker buildx imagetools inspect "$image:last-good" --format '{{json .Manifest}}' | jq -er .digest
   ```

   The first two digests must match; `last-good` must be the previously live
   digest (absent on the first ever publish).

2. **Agent swarm** — dispatch with `workers=1` and an open synthetic
   documentation task. Green looks like: the plan job logs
   `Slot-image generation: g<run>-<sha12> (newest|last-good)`, the `images` step
   reports `ready=true`, and the `Swarm worker` job input `image` ends with
   `:g<run>-<sha12>`, **not** `:latest`. The worker's own `work` job opens its
   usual pull request.

3. **Negative publish** — dispatch `Swarm slot images` with a deliberately
   broken Dockerfile layer (a synthetic edit reverted after the run, or a
   temporary stub) so one digest check fails. Green for this control looks
   like: the run is red, `:latest` and `:last-good` digests are byte-for-byte
   unchanged, no new generation record is uploaded, and the next swarm plan
   still starts workers from the previous complete generation.

4. **Negative plan** — with no ledger artifact available (for example after a
   clean run history and an expired artifact), the plan job logs `no complete
   slot-image generation and no last good generation, so the wave waits` and
   starts zero workers. Re-run `Swarm slot images` to restore the ledger.

5. **Cancellation** — cancel a `Swarm slot images` run mid-publish on a registry
   that already has slot images and confirm no `:latest` pointer moved and the
   ledger is unchanged; then re-dispatch to complete a new generation. On a fresh
   registry there is no prior pointer to restore — see Residual risks.

Record the run URLs and the three digests on the task's notes and in the
verification checklist (WFAUDIT-09). Nothing here is verified on GitHub until a
person performs these steps.

## Rollback

```bash
git apply -R /tmp/slot-image-generations.patch
git diff --check
git add .github/workflows/swarm-images.yml .github/workflows/swarm.yml
git commit -m "Revert immutable slot-image generation publishing"
```

Push and re-run both workflows: `Swarm slot images` must build and push
`:latest` directly again, and `Agent swarm` must pass `image: ...:latest` to the
worker. **Caveat:** the rollback restores the old workflow behaviour, not the
old images. Immutable `g<run>-<sha12>` tags and the ledger already published are
left in place (tags are treated as immutable); a later corrected publish must
use a new generation tag, and the plan job will ignore the old ledger once the
reverted workflow stops producing it. Workflow files are protected: only a
person performs this rollback.

## Constraint mapping

| Requirement / constraint | How the patch satisfies it | Evidence |
|---|---|---|
| Immutable generation tags | `g<run>-<sha12>` built via the WFAUDIT-02 helper, never deleted or overwritten | Dry-run A (ten generation tags, digests recorded) |
| Verify every digest before moving the pointer | Per-slot `imagetools inspect` in phase 1; pointer moves are a separate phase 2 | Dry-run B (failed digest → pointers unchanged) |
| A cancelled or failed publish does not move an existing pointer | `ERR`/`TERM` trap restores the old `:latest` manifests; failure before phase 2 touches nothing; waves use the ledger, not the pointer. On a first publish there is no old pointer to restore — see Residual risks | Dry-runs B and C (all ten `:latest` unchanged) |
| `:latest` stays a moving pointer | Phase 2 retags each slot to `:latest`; `:last-good` keeps the prior live image | Dry-run A (`:latest` = new, `:last-good` = old) |
| Workers start from the generation the plan chose | `plan` exposes `generation`; `worker` uses it and falls back to `:latest` only when unmanaged | Real-helper plan dry-runs (newest, last-good, none) |
| Reuse existing domain code | No new script functions; calls `generation_tag()` and `images-ready --generations` from WFAUDIT-02 | File reads of `scripts/swarm.py:1015-1126` |

## Residual risks and findings for the person

- **Base image still floats.** Slot images are built on `ghcr.io/<repo>-agent:latest` (`swarm-images.yml:47`). The generation tag pins the *resulting slot manifest* by digest, so a later base rebuild cannot change an already published generation, but two generations may be built on different bases. Pinning the base to the digest recorded in the `agent-image-published` artifact is a separate follow-up (OPS-CW-003-02); it is not needed for worker reproducibility within a generation.
- **`:last-good` is a convenience pointer, not the fallback source.** Waves fall back through the ledger's `last_good()` on immutable tags. A partially pushed `:last-good` set (dry-run C) never affects a wave. A person may use it for manual recovery only after inspecting the digests.
- **First publish has no pointer to restore.** On a brand-new registry a failure during phase 2 leaves the already pushed slots with a new `:latest` (dry-run D); the ERR trap can only restore pointers that existed. No ledger is uploaded, so no wave uses those tags. The person re-dispatches `Swarm slot images` to complete the generation, or removes the partial tags before a re-run.
- **Artifact retention is the safety net.** The ledger lives in an Actions artifact retained 90 days. If it expires before a publish, the plan job waits (fail closed) rather than guessing a generation. Re-dispatch `Swarm slot images` to rebuild the ledger.
- **Cancellation is best effort at the OS level.** GitHub may SIGKILL the step before the `TERM` trap runs. The authoritative guarantee is that the ledger is uploaded only after phase 2 completes, so waves never consume a partially moved pointer even in that case.
- **No workflow file is changed by the worker's pull request.** This document is the only file added; no GitHub run was performed because this task holds no token and no push access.

## Evidence provenance

Most evidence in this document came from executed tool commands and direct file
reads: `git apply --check`, `yaml.safe_load`, `bash -n`, `shellcheck`, synthetic
stub Docker/gh dry-runs and the real `scripts/swarm.py` helper. A bounded
`aurevia-github-workflow` specialist review (file reads only, no commands)
identified four issues — the artifact extraction path, status-only run
selection, the first-publish restore gap and a log line written only to
`$GITHUB_OUTPUT` — all addressed in the current diff and text above. No
simulated user, human participant or clinical evidence is claimed, and no GitHub
workflow was run.

## Approval

Not applied and not verified on GitHub. A person with workflow-file permission
must apply, run and verify it; the checks in "How to verify on GitHub" remain
undone until then.
