"""Execute the Delivery publish block against a stub registry to prove retry safety.

The workflow's YAML can look correct while the shell still publishes the wrong
image. These tests run the exact ``run:`` block of the release step with a
stubbed ``docker``/``gh`` that models image labels, local tags and registry
tags. All values are synthetic; no registry or credential is contacted.
"""
import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest

import yaml

from helpers import ROOT

DOCKER_STUB = r'''#!/usr/bin/env bash
set -euo pipefail
state="$STUB_STATE"
mkdir -p "$state/local" "$state/registry"
case "${1:-}" in
  login) exit 0 ;;
  load)
    archive="${3:-}"
    arch=$(printf '%s' "$archive" | sed -n 's|.*smoke-tested-image-\([^/]*\)/.*|\1|p')
    printf 'architecture=%s\nrevision=%s\nversion=%s\nid=%s\n' \
      "$arch" "$STUB_REVISION" "$STUB_VERSION" "$STUB_ID" > "$state/local/aurevia:test"
    echo "Loaded image: aurevia:test"
    ;;
  image)
    [[ "${2:-}" == inspect ]] || exit 1
    ref="$3"
    fmt="${5:-}"
    file="$state/local/$ref"
    [[ -f "$file" ]] || exit 1
    case "$fmt" in
      *Architecture*) sed -n 's/^architecture=//p' "$file" ;;
      *revision*) sed -n 's/^revision=//p' "$file" ;;
      *version*) sed -n 's/^version=//p' "$file" ;;
      *Id*) sed -n 's/^id=//p' "$file" ;;
      *RepoDigests*) echo "$ref@sha256:1111111111111111111111111111111111111111111111111111111111111111" ;;
      *) exit 1 ;;
    esac
    ;;
  tag)
    mkdir -p "$(dirname "$state/local/$3")"
    cp "$state/local/$2" "$state/local/$3"
    ;;
  pull)
    ref="${@: -1}"
    [[ "${STUB_PULL_FAIL:-0}" != 1 ]] || exit 1
    [[ -f "$state/registry/$ref" ]] || exit 1
    mkdir -p "$(dirname "$state/local/$ref")"
    cp "$state/registry/$ref" "$state/local/$ref"
    ;;
  push)
    ref="$2"
    mkdir -p "$(dirname "$state/registry/$ref")"
    cp "$state/local/$ref" "$state/registry/$ref"
    echo "Pushed $ref"
    ;;
  buildx)
    [[ "${2:-}" == imagetools ]] || exit 1
    action="$3"
    case "$action" in
      inspect)
        ref="$4"
        [[ -f "$state/registry/$ref" ]] || exit 1
        flag=""
        for arg in "${@:5}"; do
          case "$arg" in --*) flag="$arg" ;; esac
        done
        case "$flag" in
          --format) echo '{"digest":"sha256:2222222222222222222222222222222222222222222222222222222222222222"}' ;;
          --raw) echo '{"manifests":[{"digest":"sha256:2222222222222222222222222222222222222222222222222222222222222222"}]}' ;;
        esac
        ;;
      create)
        shift 3
        while [[ $# -gt 0 && "$1" == --tag ]]; do
          tag="$2"
          shift 2
          mkdir -p "$(dirname "$state/registry/$tag")"
          printf 'architecture=amd64\nrevision=%s\nversion=%s\nid=manifest\n' \
            "$STUB_REVISION" "$STUB_VERSION" > "$state/registry/$tag"
        done
        ;;
      *) exit 1 ;;
    esac
    ;;
  *) exit 1 ;;
esac
'''

GH_STUB = r'''#!/usr/bin/env bash
state="$STUB_STATE"
case "$1 $2" in
  "release view") [[ -f "$state/release" ]] ;;
  "release create") touch "$state/release"; echo "created release" ;;
  *) exit 1 ;;
esac
'''


def publish_shell():
    """Return a bash that supports the lowercase expansion used by the workflow."""
    for candidate in ("/bin/bash", "/opt/homebrew/bin/bash", "/usr/local/bin/bash"):
        if not pathlib.Path(candidate).is_file():
            continue
        probe = subprocess.run(
            [candidate, "-c", 'v=ABC; [[ "${v,,}" == abc ]]'],
            capture_output=True,
            check=False,
        )
        if probe.returncode == 0:
            return candidate
    raise AssertionError("no bash with ${var,,} support is installed")


def publish_block():
    """Return the exact ``run:`` script of the Delivery publish step."""
    doc = yaml.safe_load((ROOT / ".github/workflows/delivery.yml").read_text(encoding="utf-8"))
    step = next(
        item for item in doc["jobs"]["release"]["steps"]
        if item.get("name") == "Publish exact tested image and release"
    )
    return step["run"]


class ReleaseStepBehaviorTest(unittest.TestCase):
    """The publish block reuses matching children and refuses foreign revisions."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)
        self.state = root / "state"
        self.state.mkdir()
        bin_dir = root / "bin"
        bin_dir.mkdir()
        docker = bin_dir / "docker"
        docker.write_text(DOCKER_STUB, encoding="utf-8")
        docker.chmod(0o755)
        gh = bin_dir / "gh"
        gh.write_text(GH_STUB, encoding="utf-8")
        gh.chmod(0o755)
        for arch in ("amd64", "arm64"):
            (root / f"smoke-tested-image-{arch}").mkdir()
            (root / f"smoke-tested-image-{arch}" / "aurevia-image.tar").write_text("stub", encoding="utf-8")
        self.env = dict(os.environ)
        self.env.update(
            PATH=f"{bin_dir}:{self.env['PATH']}",
            STUB_STATE=str(self.state),
            STUB_REVISION="a" * 40,
            STUB_VERSION="v1.0.0",
            STUB_ARCH="amd64",
            STUB_ID="fresh-image-id",
            VERSION="v1.0.0",
            GITHUB_SHA="a" * 40,
            GITHUB_REPOSITORY="dr3mro/Aurevia-Care",
            GITHUB_ACTOR="dr3mro",
            GH_TOKEN="synthetic-token",
        )

    def seed_child(self, arch, revision, image_id="old-image-id", version="v1.0.0"):
        child = f"ghcr.io/dr3mro/aurevia-care:v1.0.0-{arch}"
        (self.state / "registry" / child).parent.mkdir(parents=True, exist_ok=True)
        (self.state / "registry" / child).write_text(
            textwrap.dedent(
                f"""\
                architecture={arch}
                revision={revision}
                version={version}
                id={image_id}
                """
            ),
            encoding="utf-8",
        )

    def run_publish(self):
        return subprocess.run(
            [publish_shell(), "-c", publish_block()],
            cwd=self.tmp.name,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_first_release_publishes_both_children_and_the_release(self):
        result = self.run_publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.state / "release").exists())
        child = self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0-amd64"
        self.assertTrue(child.is_file())
        self.assertTrue((self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0-arm64").is_file())
        notes = (pathlib.Path(self.tmp.name) / "release-notes.md").read_text(encoding="utf-8")
        self.assertIn("Smoke-tested image:", notes)
        self.assertIn("Commit: `aaaa", notes)

    def test_retry_of_same_revision_reuses_a_rebuilt_child(self):
        self.seed_child("amd64", "a" * 40, "different-built-id")
        result = self.run_publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("refusing to overwrite", result.stderr)
        self.assertNotIn("Pushed ghcr.io/dr3mro/aurevia-care:v1.0.0-amd64", result.stdout)
        self.assertTrue((self.state / "release").exists())

    def test_child_with_another_version_label_fails_closed_without_writes(self):
        self.seed_child("amd64", "a" * 40, version="v9.9.9")
        result = self.run_publish()
        self.assertEqual(result.returncode, 1)
        self.assertIn("has version v9.9.9, expected v1.0.0", result.stderr)
        self.assertNotIn("Pushed ghcr.io/dr3mro/aurevia-care:v1.0.0-amd64", result.stdout)
        self.assertFalse((self.state / "release").exists())
        self.assertFalse((self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0").exists())

    def test_child_from_another_revision_fails_closed_without_writes(self):
        self.seed_child("amd64", "b" * 40)
        result = self.run_publish()
        self.assertEqual(result.returncode, 1)
        self.assertIn("already published for revision", result.stderr)
        self.assertIn("use a new version tag", result.stderr)
        self.assertFalse((self.state / "release").exists())
        self.assertFalse((self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0").exists())

    def test_transient_pull_failure_on_a_published_child_fails_closed(self):
        self.seed_child("amd64", "a" * 40)
        self.env["STUB_PULL_FAIL"] = "1"
        result = self.run_publish()
        self.assertEqual(result.returncode, 1)
        self.assertIn("refusing to overwrite", result.stderr)
        self.assertFalse((self.state / "release").exists())

    def test_published_manifest_fails_before_any_child_push(self):
        manifest = self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0"
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text("published\n", encoding="utf-8")
        result = self.run_publish()
        self.assertEqual(result.returncode, 1)
        self.assertIn("version tags are immutable", result.stderr)
        self.assertFalse((self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0-amd64").exists())

    def test_published_release_fails_before_any_child_push(self):
        (self.state / "release").touch()
        result = self.run_publish()
        self.assertEqual(result.returncode, 1)
        self.assertIn("version tags are immutable", result.stderr)
        self.assertFalse((self.state / "registry" / "ghcr.io/dr3mro/aurevia-care:v1.0.0-amd64").exists())


if __name__ == "__main__":
    unittest.main()
