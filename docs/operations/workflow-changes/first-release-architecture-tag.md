# First release must not fail for a missing architecture tag

- Status: proposed patch, **not applied**. A worker cannot push a workflow change; a person with workflow permission must apply the diff below.
- Date: 2026-11-21
- Requirement IDs: OPS-06, WEB-11
- Risk class: cross-boundary (release pipeline and publication credentials)
- Target file: [`.github/workflows/delivery.yml`](../../../.github/workflows/delivery.yml), `release` job, step "Publish exact tested image and release" (lines 186–224).
- Reviewed, **not changed**: [`.github/workflows/release-app-image.yml`](../../../.github/workflows/release-app-image.yml). It already creates its local architecture tag before pushing (lines 60–61) and gates `:latest` to stable tags (line 86), so it is not part of the patch.

## Problems found

### 1. First release fails: the local architecture tag does not exist (blocking)

The `verify` job saves the smoke-tested image as `aurevia:test` only (line 156). The `release` job loads that tar (line 196) and computes `child="$image-$arch"` (line 200) — for example `ghcr.io/<owner>/<repo>:v1.0.0-amd64` — but never creates that tag locally.

On the **first** release of a version the child tag is absent from the registry, so:

1. `docker buildx imagetools inspect "$child"` (line 201) fails, so the `else` branch runs.
2. `docker push "$child"` (line 205) resolves the **reference name**, not the image bits. There is no local `ghcr.io/<owner>/<repo>:v1.0.0-amd64` tag, only `aurevia:test`.
3. Docker reports `An image does not exist locally with the tag: ...`; `set -euo pipefail` (line 191) aborts the step, so no image and no GitHub release are published.

This is exactly the "first release fails for a missing local architecture tag" defect. The `docker pull` on line 202 would create the local tag, but that branch only runs when the tag already exists — i.e. only on a re-run, never on the first release.

### 2. A re-run of the same release fails on image `.Id` (blocking)

[`scripts/verify.sh`](../../../scripts/verify.sh) uses plain `docker build` with no `SOURCE_DATE_EPOCH` pin ([Dockerfile](../../../Dockerfile)). A rebuild of the same commit therefore gets a fresh image-config `created` time and a different `.Id`, even though the `org.opencontainers.image.version` and `org.opencontainers.image.revision` labels (fixed by build args) are unchanged.

On a re-run the child tag *does* exist, so line 202 pulls it and line 203 compares the previously published `.Id` with the freshly rebuilt `.Id`. They differ, `test` fails, and the release fails. The two label assertions on lines 198–199 already identify the release correctly; the `.Id` equality is both too strict and the wrong identity measure.

### 3. `inspect` failure is treated as "tag absent" (overwrite risk)

Lines 201 and 209 discard all diagnostics with `>/dev/null 2>&1`. A transport error, GHCR 5xx, rate limit or token-scope problem is indistinguishable from a genuine not-found. With the missing `docker tag` added naively, a transient `inspect` failure would authorize a `docker push` that **overwrites an existing version tag with different content** — violating WEB-11's "build once and promote the same artifact" and OPS-06's reproducibility requirement.

### 4. `RepoDigests[0]` is assumed to exist

Line 207 indexes the first repo digest without checking it. A push that succeeds without recording a digest, or a stale digest restored from the tar, either fails the step or feeds a wrong digest into `imagetools create`.

## Failing sequence shown by a dry run with stubbed Docker

The exact shell of the `release` step was run against a stub `docker`/`gh` that models: a registry tag set, a local tag set, a fresh image `.Id` per rebuild, `push` requiring a local tag, `pull` creating the local tag, and `imagetools inspect` succeeding only for registry tags. All values are synthetic (`owner/repo`, `v1.0.0`, `REV_A`, `REV_B`). The harness was written outside the repository (`/tmp/opencode/wfaudit04/dryrun.sh`) and is **not** part of this change.

Harness entry point (run from the workspace root):

```bash
bash /tmp/opencode/wfaudit04/dryrun.sh
```

Observed output:

```text
===== A. CURRENT / first release (empty registry) =====
docker: An image does not exist locally with the tag: ghcr.io/owner/repo:v1.0.0-amd64
RESULT: FAILURE (exit 1)

===== A2. CURRENT / re-run of same release (rebuilt ids differ) =====
RESULT: FAILURE (exit 1)

===== B. PATCHED / first release (empty registry) =====
PATCHED OK: manifest ghcr.io/owner/repo:v1.0.0 from 2 child refs
RESULT: SUCCESS

===== C. PATCHED / re-run of same release (rebuilt ids differ) =====
PATCHED OK: manifest ghcr.io/owner/repo:v1.0.0 from 2 child refs
RESULT: SUCCESS

===== D. PATCHED / same version tag, different revision (must not overwrite) =====
RESULT: FAILURE (exit 1)

Registry children after D: ghcr.io/owner/repo:v1.0.0-arm64 ghcr.io/owner/repo:v1.0.0 ghcr.io/owner/repo:v1.0.0-amd64
===== E. PATCHED / push with no RepoDigest (must fail closed) =====
no registry digest for ghcr.io/owner/repo:v1.0.0-amd64
RESULT: FAILURE (exit 1)

===== F. PATCHED / transient pull failure, tag exists (must fail closed) =====
cannot pull existing ghcr.io/owner/repo:v1.0.0-amd64; refusing to overwrite
RESULT: FAILURE (exit 1)
```

Scenario A reproduces the reported first-release failure. A2 reproduces the re-run `.Id` failure (silent `test` failure). B and C are the required successes. D fails the provenance check and leaves every registry child unchanged. E fails closed when no digest is recorded. F fails closed on a transient pull error while the tag is published, so no existing child is overwritten.

## The patch

The diff below was produced from a scratch copy of the current file and checked with `git apply --check` against a pristine copy: it applies cleanly. It changes only the `release` step:

1. `docker tag aurevia:test "$child"` creates the missing local tag in **both** branches.
2. Existence is decided by `docker pull`: success means the tag is published; the existing image's version and revision labels are then verified. A failed pull is cross-checked with `imagetools inspect`; if a manifest is nevertheless visible the step fails closed instead of pushing.
3. The brittle `.Id` equality is replaced by stable provenance-label checks, so a re-run of the same commit succeeds. Different content under the same version fails before any write.
4. The digest is validated before it is used.
5. No `:latest` tag is added anywhere in this workflow, so pre-release versions never receive `:latest` from it. `release-app-image.yml` keeps its existing `[[ "$VERSION" == *-* ]] || ...` gate for its separate `-app` image.

```diff
--- a/.github/workflows/delivery.yml
+++ b/.github/workflows/delivery.yml
@@ -191,20 +191,40 @@
           set -euo pipefail
           image="ghcr.io/${GITHUB_REPOSITORY,,}:$VERSION"
           printf '%s' "$GH_TOKEN" | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
+          # Version and revision labels are fixed by --build-arg and are the
+          # stable identity of a release. A rebuilt image has a new .Id (the
+          # image config records a fresh "created" time) even for the same SHA.
+          version_label='{{index .Config.Labels "org.opencontainers.image.version"}}'
+          revision_label='{{index .Config.Labels "org.opencontainers.image.revision"}}'
           refs=()
           for arch in amd64 arm64; do
             docker load -i "smoke-tested-image-$arch/aurevia-image.tar"
             test "$(docker image inspect aurevia:test --format '{{.Architecture}}')" = "$arch"
-            test "$(docker image inspect aurevia:test --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" = "$GITHUB_SHA"
-            test "$(docker image inspect aurevia:test --format '{{index .Config.Labels "org.opencontainers.image.version"}}')" = "$VERSION"
+            test "$(docker image inspect aurevia:test --format "$revision_label")" = "$GITHUB_SHA"
+            test "$(docker image inspect aurevia:test --format "$version_label")" = "$VERSION"
             child="$image-$arch"
-            if docker buildx imagetools inspect "$child" >/dev/null 2>&1; then
-              docker pull --platform "linux/$arch" "$child"
-              test "$(docker image inspect "$child" --format '{{.Id}}')" = "$(docker image inspect aurevia:test --format '{{.Id}}')"
+            # docker load restores only aurevia:test. The architecture tag
+            # must exist locally before it can be pushed: on the first release
+            # there is no local $child tag and `docker push "$child"` fails.
+            docker tag aurevia:test "$child"
+            if docker pull --platform "linux/$arch" "$child" >/dev/null 2>&1; then
+              # Tag already published. Reuse it only when its provenance is
+              # this exact release; never overwrite a version tag with other
+              # content.
+              test "$(docker image inspect "$child" --format "$version_label")" = "$VERSION"
+              test "$(docker image inspect "$child" --format "$revision_label")" = "$GITHUB_SHA"
             else
+              # A failed pull means the tag is absent. Fail closed if a
+              # manifest is nevertheless visible (a transient pull error).
+              if docker buildx imagetools inspect "$child" >/dev/null 2>&1; then
+                echo "cannot pull existing $child; refusing to overwrite" >&2
+                exit 1
+              fi
               docker push "$child"
             fi
-            refs+=("$(docker image inspect "$child" --format '{{index .RepoDigests 0}}')")
+            digest="$(docker image inspect "$child" --format '{{index .RepoDigests 0}}')"
+            [[ -n "$digest" && "$digest" == *@sha256:* ]] || { echo "no registry digest for $child" >&2; exit 1; }
+            refs+=("$digest")
           done
           if ! docker buildx imagetools inspect "$image" >/dev/null 2>&1; then
             docker buildx imagetools create --tag "$image" "${refs[@]}"
```

## How to apply

A person with workflow permission, from the repository root:

```bash
# The document contains exactly one ```diff block. Extract it to a patch file.
awk '/^```diff$/{in_block=1; next} /^```$/{in_block=0} in_block' \
  docs/operations/workflow-changes/first-release-architecture-tag.md \
  > /tmp/first-release-architecture-tag.patch

git apply --check /tmp/first-release-architecture-tag.patch
git apply /tmp/first-release-architecture-tag.patch
git diff --check
git add .github/workflows/delivery.yml
git commit -m "Fix first release of a version when the architecture tag is absent"
```

Before pushing, run the repository gates the same way CI does:

```bash
python3 -m venv "$TMPDIR/actionlint" && "$TMPDIR/actionlint/bin/pip" install -q actionlint-py==1.7.12.25 && "$TMPDIR/actionlint/bin/actionlint"
bash -n <(sed -n '/set -euo pipefail/,/gh release create/p' .github/workflows/delivery.yml)
python3 -I scripts/check-docs.py
python3 -I -m unittest discover -s scripts/tests -q
```

The exact shell block was checked locally with `bash -n` and `shellcheck -s bash` (both clean); the person should repeat `actionlint` on the applied YAML.

## How to verify on GitHub after the push

1. Push the applied workflow change and open a pull request; confirm the required checks on the **exact SHA**: `Policy and tooling` (includes `actionlint`), `Delivery tooling tests`, `Frontend standards`, `Java and database verification`, `Developer reference`, both per-architecture smoke checks and `Delivery gate`. Merge only when all are green.
2. From a green default-branch commit, create and push an **authorized annotated tag** for a pre-release, for example `v0.1.0-rc.<n>` (release policy in [README-SETUP.md](../../../README-SETUP.md) still applies; this document does not authorize a release).
3. Watch both `Delivery` and `Release app image` for that tag. Green for this patch looks like:
   - `Delivery` → `verify (amd64)` and `verify (arm64)` succeed and upload `smoke-tested-image-*`;
   - `Delivery` → `release` succeeds; its log contains **no** `An image does not exist locally with the tag`, no `.Id` mismatch, and no `refusing to overwrite`; each child tag is pushed exactly once on a first release;
   - the log ends with a `Smoke-tested image:` digest line and `gh release create ... --prerelease` (pre-release marker present).
4. Confirm the multi-architecture manifest and the first-release path from a machine logged in to GHCR:

   ```bash
   image=ghcr.io/<owner>/<repo>:<tag>
   docker buildx imagetools inspect "$image"          # lists linux/amd64 and linux/arm64
   docker buildx imagetools inspect "$image" --raw | jq -r '.manifests[].platform'
   ```

5. **Re-run the same tag's `Delivery` workflow** (Actions → run → Re-run all jobs). It must succeed a second time. Compare the manifest digest before and after:

   ```bash
   docker buildx imagetools inspect "$image" --format '{{json .Manifest}}' | jq -er .digest
   ```

   The digest must be identical after the re-run. That is the observable proof that the re-run reused the published children and did not overwrite the version tag with different content. Scenario D above is the negative proof: same version tag with a different revision fails without touching the registry.

6. **`:latest` on a pre-release**: `docker buildx imagetools inspect ghcr.io/<owner>/<repo>:latest` must not resolve to this pre-release (this workflow never publishes `:latest`; `release-app-image.yml` publishes `:latest` only for its separate `-app` image when the version has no `-`). Do not manually create `:latest` for a pre-release.

7. Record the workflow run URLs, the manifest digest and both re-run results on the task's notes and in the verification checklist (WFAUDIT-09). Nothing here is verified until a person runs it.

## Rollback

```bash
git apply -R /tmp/first-release-architecture-tag.patch
git diff --check
git add .github/workflows/delivery.yml
git commit -m "Revert first-release architecture tag fix"
```

Then push and re-run the workflow to confirm the old behaviour is restored. **Caveat:** rolling back reintroduces the blocking defect, so do not attempt a new release while the patch is reverted. Tags and manifests already published are immutable under the release policy; a rollback does not delete them, and a corrected release must use a new tag rather than overwriting an existing one.

## Constraint mapping

| Constraint | How the patch satisfies it | Evidence |
|---|---|---|
| First release succeeds | `docker tag aurevia:test "$child"` creates the local tag before the push | Dry-run A (fails) → B (succeeds) |
| Re-run of the same release succeeds | Provenance labels replace the rebuild-dependent `.Id` check | Dry-run A2 (fails) → C (succeeds) |
| Never overwrite an existing version tag with different content | Existing children are pulled and label-verified, never pushed; pull/inspect ambiguity fails closed; manifest creation stays guarded | Dry-run D (fails, registry unchanged); fail-closed branch in the patch |
| `:latest` off pre-release versions | This workflow publishes no `:latest`; `release-app-image.yml` keeps its own `VERSION == *-*` gate and is unchanged | File reads of both workflows; no `:latest` line added |

## Residual risks and findings for the person

- The parent manifest `imagetools inspect` guard (line 209 of the original) still conflates "absent" with "transport error"; a transient failure there could re-create the manifest from the same verified refs. A stricter classification of its diagnostic output is a follow-up, not part of this minimal fix.
- Content identity is proxied by the immutable `version` and `revision` labels. A registry-side rebuild with the same labels but different bytes would be accepted; WEB-12's digest-pinned base images and the fixed build args make that unlikely, but it is not a byte-for-byte guarantee.
- The `release` job runs on `ubuntu-latest` and pulls the arm64 child with `--platform linux/arm64`; `docker image inspect` works without executing it, as in the current workflow. No new runtime dependency is introduced.
- `.github/workflows/release-app-image.yml`'s `publish` job calls `imagetools create` unconditionally, so it can overwrite its own `-app` manifest. It targets a different image and is out of scope here.
- This is a proposed patch only: no workflow file is modified by the worker's pull request, and no release was created or tagged.

## Approval

Not applied and not verified on GitHub. A person with workflow permission must apply, run and verify it; every checkbox in the verification checklist remains unchecked until then. No simulated user, human or clinical evidence is claimed here — the evidence is file reads and executed synthetic stub commands (tool evidence).
