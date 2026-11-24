"""Fail when files contain credentials or identifiers that look like real PHI.

The repository is for synthetic data only (AGENTS.md). This is a cheap local
guard, not a replacement for a dedicated scanner in CI. A line can opt out with
the marker `synthetic-ok` when a fixture must contain a lookalike value.
"""
import re
import subprocess
import sys
from pathlib import Path

# ``python3 -I scripts/<tool>.py`` omits the repository root from sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from process_env import child_environment

ROOT = Path(__file__).resolve().parents[1]
MARKER = "synthetic-ok"
SKIP_DIRS = {".git", "__pycache__", "plan", "build", "node_modules", "target", "dist", "coverage", ".expo"}
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
    "AWS access key id": re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "Slack token": re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"),
    "JSON web token": re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
    "password assignment": re.compile(
        r"(?i)\b(?:password|passwd|secret|api[_-]?key)\b\s*[:=]\s*['\"][^'\"\s]{8,}['\"]"),
    "US social security number": re.compile(r"\b(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b"),
    "hard-coded NVD credential": re.compile(
        r"nvdApiKey(?:EnvironmentVariable)?\s*>\s*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
        r"[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
    "NVD credential in note": re.compile(
        r"(?i)nvd[ _-]?key.{0,80}\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
        r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"),
}


def candidate_files(root):
    """Tracked files when inside a git checkout, otherwise a directory walk."""
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others",
             "--exclude-standard"], check=True, capture_output=True,
            env=child_environment(read_only=True)).stdout
        names = [n for n in out.decode().split("\0") if n]
    except (OSError, subprocess.CalledProcessError):
        names = [str(p.relative_to(root)) for p in root.rglob("*")
                 if p.is_file() and not SKIP_DIRS & set(p.relative_to(root).parts)]
    return [root / n for n in names if not SKIP_DIRS & set(Path(n).parts)]


def scan(root=ROOT):
    """Return (path, line number, label) for each finding under root."""
    findings = []
    for path in candidate_files(root):
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if MARKER in line:
                continue
            for label, pattern in PATTERNS.items():
                if pattern.search(line):
                    findings.append((path.relative_to(root), number, label))
    return findings


def main():
    findings = scan()
    for path, number, label in findings:
        print(f"check-secrets: {path}:{number}: possible {label}", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
