#!/usr/bin/env python3
"""Rewrite repository-relative source links in the generated developer reference.

Doxygen copies links from ``docs/reference`` verbatim into flat HTML pages at the
GitHub Pages site root, so a link such as ``../../../apps/api/.../Service.java``
resolves above the published site and returns 404. This tool rewrites those
links to permanent GitHub URLs pinned to the commit that built the reference.

Inputs are the generated HTML site, the checkout root (to tell a directory from
a file), an ``owner/repo`` pair and a 40-hex commit SHA. It rewrites only root
level ``*.html`` files, so the TypeDoc sub-sites and Doxygen's own links are
left untouched. ``--check`` reports any repository-relative link that could not
be rewritten and writes nothing. The invariant is that a source link in the
published reference always resolves to the exact source file or directory at
the built commit, never to a path outside the site.
"""
import argparse
import re
import sys
from pathlib import Path
from typing import Optional, Sequence, Union

SOURCE_LINK = re.compile(
    r"(?P<lead>href\s*=\s*)(?P<q>[\"'])"
    r"(?P<url>(?:\.\./)+"
    r"(?:apps|db|contracts|scripts|infra|\.opencode|docs)/[^\"'<>]*)"
    r"(?P=q)"
)
LEADING_PARENTS = re.compile(r"^(?:\.\./)+")
REPOSITORY_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*\Z")
COMMIT_SHA = re.compile(r"[0-9a-fA-F]{40}\Z")


def repository_arg(value: str) -> str:
    """Validate an ``owner/repo`` command-line value.

    Rejects anything that could be parsed as another option or produce a malformed
    GitHub URL; argparse turns the error into exit code 2 with an actionable line.
    """
    if not REPOSITORY_NAME.fullmatch(value):
        raise argparse.ArgumentTypeError(
            "--repository must be '<owner>/<repo>' (got %r)" % value)
    return value


def ref_arg(value: str) -> str:
    """Validate a full commit SHA so published links cannot drift with a branch."""
    if not COMMIT_SHA.fullmatch(value):
        raise argparse.ArgumentTypeError(
            "--ref must be a 40-hex commit SHA (got %r)" % value)
    return value


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    """Parse and validate the command line; raises SystemExit(2) on bad values."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--site", type=Path, required=True,
                        help="generated developer-reference HTML directory")
    parser.add_argument("--repo-root", type=Path, required=True,
                        help="checkout root, used to distinguish files from directories")
    parser.add_argument("--repository", type=repository_arg, required=True,
                        help="GitHub owner/repo for the permanent links")
    parser.add_argument("--ref", type=ref_arg, required=True,
                        help="40-hex commit SHA the reference was built from")
    parser.add_argument("--check", action="store_true",
                        help="report unresolved links without writing")
    return parser.parse_args(argv)


def split_target(url: str) -> tuple[str, str]:
    """Split a relative href into its repository path and its query/fragment tail.

    The tail is preserved byte for byte, including HTML entities, because it is
    part of the authored link rather than a URL to be decoded here.
    """
    stripped = LEADING_PARENTS.sub("", url)
    before_fragment, hash_mark, fragment = stripped.partition("#")
    path, question, query = before_fragment.partition("?")
    # A directory link may end in '/'; that is a valid GitHub tree URL, not an
    # empty path segment. Drop it before segment validation.
    return path.rstrip("/"), question + query + hash_mark + fragment


def safe_path(path: str) -> bool:
    """Reject empty, dot, parent-traversal or backslash path segments."""
    for part in path.split("/"):
        if part in ("", ".", "..") or "\\" in part:
            return False
    return True


def rewrite_links(text: str, repository: str, ref: str,
                  repo_root: Union[Path, str]) -> tuple[str, int, tuple]:
    """Rewrite repository-relative hrefs in one HTML document.

    Returns the new text, the number of rewritten links and ``(line, href)``
    pairs for candidates that were rejected as unsafe. Non-source hrefs are
    returned unchanged; the transformation is idempotent.
    """
    root = Path(repo_root)
    unresolved = []
    written = 0

    def replace(match: re.Match) -> str:
        nonlocal written
        url = match.group("url")
        path, suffix = split_target(url)
        if not safe_path(path):
            line = text.count("\n", 0, match.start()) + 1
            unresolved.append((line, url))
            return match.group(0)
        kind = "tree" if (root / path).is_dir() else "blob"
        target = f"https://github.com/{repository}/{kind}/{ref}/{path}{suffix}"
        written += 1
        return f"{match.group('lead')}{match.group('q')}{target}{match.group('q')}"

    result, _matches = SOURCE_LINK.subn(replace, text)
    return result, written, tuple(unresolved)


def unresolved_links(text: str) -> tuple:
    """Return ``(line, href)`` pairs for every repository-relative href in text.

    Uses the same whole-text matcher as the rewrite pass so ``--check`` can
    never disagree with what the writer considered a candidate.
    """
    found = []
    for match in SOURCE_LINK.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        found.append((line, match.group("url")))
    return tuple(found)


def process_site(site: Union[Path, str], repository: str, ref: str,
                 repo_root: Union[Path, str],
                 check: bool = False) -> tuple[int, int, tuple]:
    """Rewrite root-level generated pages, or only report unresolved links.

    Returns the number of rewritten links, the number of changed files and
    ``(filename, line, href)`` problems. Subdirectories are never visited, so
    TypeDoc output stays byte-identical.
    """
    total = 0
    changed = 0
    problems = []
    for path in sorted(Path(site).glob("*.html")):
        text = path.read_text(encoding="utf-8")
        if check:
            problems.extend((path.name, number, url)
                            for number, url in unresolved_links(text))
            continue
        result, count, skipped = rewrite_links(text, repository, ref, repo_root)
        problems.extend((path.name, number, url) for number, url in skipped)
        if count:
            path.write_text(result, encoding="utf-8")
            total += count
            changed += 1
    return total, changed, tuple(problems)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Run the rewrite or check and report actionable results on stdout/stderr."""
    args = parse_args(argv)
    if not args.site.is_dir():
        print(f"error: --site is not a directory: {args.site}", file=sys.stderr)
        return 1
    total, changed, problems = process_site(
        args.site, args.repository, args.ref, args.repo_root, args.check)
    if problems:
        for name, number, url in problems:
            print(f"{name}:{number}: unresolved repository-relative link: {url}",
                  file=sys.stderr)
        return 1
    if args.check:
        print("No unresolved repository-relative links.")
    else:
        print(f"Rewrote {total} link(s) in {changed} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
