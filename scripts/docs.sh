#!/usr/bin/env bash
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
printf 'Developer reference: %s/index.html\n' "$site"
