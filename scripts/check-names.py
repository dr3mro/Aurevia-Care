"""Fail when a folder or Java package is named after a story, task or gate ID.

CODING_STYLE.md says to name packages and folders by business domain, never by
an ID such as s_g1_08, x_01, DLV-02 or CW-004. Workers kept copying the task ID,
so the rule is enforced here. There are no repository exemptions: the old
allow-list was removed once the last story-ID folders were renamed. The
``legacy`` argument is only a fixture seam for the comparison, not a way to
tolerate a path in this repository.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ID_NAME = re.compile(
    r"^(s[-_]?g\d+[-_]?\d+[a-z]?|x[-_]?\d{2}|dlv[-_]?\d+|cw[-_]?\d+|aud[-_]?\d+|"
    r"fix[-_]?\d+|imp[-_]?\d+|refactor[-_]?\w+[-_]\d+|story[-_]?\d+|task[-_]?\d+)$",
    re.IGNORECASE)
JAVA_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
SKIP = {".git", "node_modules", ".agent", "target", "__pycache__"}
SKIP_PREFIXES = ("docs/archive/", "docs/swarm/")


def tracked_files(root):
    try:
        out = subprocess.run(["git", "-C", root, "ls-files"], capture_output=True, text=True, check=True)
        return [f for f in out.stdout.splitlines() if f]
    except (OSError, subprocess.CalledProcessError):
        found = []
        for base, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in SKIP]
            found += [os.path.relpath(os.path.join(base, f), root) for f in files]
        return found


def violations(root, files=None, legacy=()):
    bad = set()
    for path in files if files is not None else tracked_files(root):
        if path.startswith(SKIP_PREFIXES) or SKIP & set(path.split("/")):
            continue
        parts = path.split("/")[:-1]
        for i, part in enumerate(parts):
            folder = "/".join(parts[: i + 1])
            if ID_NAME.match(part) and folder not in legacy:
                bad.add(folder)
        if path.endswith(".java"):
            try:
                text = Path(root, path).read_text(encoding="utf-8")
            except OSError:
                continue
            match = JAVA_PACKAGE.search(text)
            if match:
                for segment in match.group(1).split("."):
                    if ID_NAME.match(segment) and not any(
                            seg_path.endswith("/" + segment) for seg_path in legacy):
                        bad.add(f"{path}: package segment '{segment}'")
    return sorted(bad)


def main(root="."):
    bad = violations(root)
    for item in bad:
        print(f"check-names: {item} is named after a story/task ID; use a business-domain name "
              "(see CODING_STYLE.md)", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
