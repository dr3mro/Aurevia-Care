#!/usr/bin/env bash
# Build the developer reference from Doxygen and the per-module TypeDoc hooks,
# then rewrite checkout-relative source links to permanent GitHub permalinks.
# Inputs are the checkout, Doxygen and, in CI, GITHUB_REPOSITORY/GITHUB_SHA;
# locally those are derived from the origin remote and HEAD. Output is
# build/developer-reference/html. The invariant is that no published source
# link resolves outside the exact commit that produced the reference.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v doxygen >/dev/null || { printf 'Install Doxygen to build the developer reference.\n' >&2; exit 1; }
export DOC_PROJECT_NAME
DOC_PROJECT_NAME=$(python3 scripts/project-name.py displayName)
mkdir -p build/developer-reference
doxygen docs/Doxyfile

site="$PWD/build/developer-reference/html"
for module in staff-web patient-mobile; do
  app="apps/$module"
  dest="$site/$module"
  mkdir -p "$dest"
  if [[ -f "$app/docs.sh" ]]; then
    (cd "$app" && bash docs.sh "$dest")
    test -s "$dest/index.html"
  elif find "$app" \( -name node_modules -o -name dist -o -name coverage -o -name .expo -o -name .git \) -prune -o -type f \( -name '*.ts' -o -name '*.tsx' \) -print | awk 'END { exit NR == 0 }'; then
    printf 'Missing TypeDoc build hook: %s/docs.sh\n' "$app" >&2
    exit 1
  else
    printf '<!doctype html><html lang="en"><meta charset="utf-8"><title>Reference pending</title><h1>%s reference pending</h1><p>Module implementation is pending.</p><p><a href="../index.html">Developer reference</a></p>\n' "$module" > "$dest/index.html"
  fi
done

# Module guides link to source files relative to the checkout, but Doxygen emits
# flat pages at the site root where those paths resolve above the site. Rewrite
# them to permalinks pinned to this commit, then assert none were left behind.
# GitHub's variables are trusted in CI; a local build falls back to origin and
# HEAD. Fail closed when identity cannot be derived, and never print the raw
# remote because HTTPS remotes may carry a token.
repository=${GITHUB_REPOSITORY:-}
ref=${GITHUB_SHA:-}
if [[ -z $repository ]]; then
  origin=$(git remote get-url origin 2>/dev/null || true)
  case $origin in
    https://github.com/*|git@github.com:*|ssh://git@github.com/*)
      repository=${origin#*github.com[:/]}
      repository=${repository%.git}
      ;;
  esac
fi
if [[ -z $ref ]]; then
  ref=$(git rev-parse HEAD 2>/dev/null || true)
fi
case $repository in
  *[!A-Za-z0-9._/-]*|-*|/*|*/|*//*|*.git|*/*/*)
    printf 'Cannot derive a safe repository name for source links.\n' >&2
    repository=
    ;;
esac
case $ref in
  *[!0-9a-fA-F]*)
    printf 'Cannot derive a valid commit for source links.\n' >&2
    ref=
    ;;
esac
if [[ -z $repository || -z $ref ]]; then
  printf 'Set GITHUB_REPOSITORY and GITHUB_SHA, or build from a Git checkout with an origin remote.\n' >&2
  exit 1
fi

python3 -I scripts/link_developer_reference.py --site "$site" --repo-root "$PWD" --repository "$repository" --ref "$ref"
python3 -I scripts/link_developer_reference.py --site "$site" --repo-root "$PWD" --repository "$repository" --ref "$ref" --check
printf 'Developer reference: %s/index.html\n' "$site"
