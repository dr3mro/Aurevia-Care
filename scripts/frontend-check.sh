#!/usr/bin/env bash
# Frontend standards gate. Enforces AGENTS.md rules and runs the required tools.
# Workers and the Delivery CI job both call this, so violations fail the build.
set -euo pipefail
cd "$(dirname "$0")/.."

modules=(apps/staff-web apps/patient-mobile)
fail=0

for module in "${modules[@]}"; do
  sources=()
  while IFS= read -r file; do
    sources+=("$file")
  done < <(find "$module" -type f \( -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.jsx' \) \
    -not -path '*/node_modules/*' -not -path '*/dist/*' 2>/dev/null | sort)

  if [[ ${#sources[@]} -eq 0 ]]; then
    printf '%s: implementation pending\n' "$module"
    continue
  fi

  # Run the module's own canonical commands with its own locked toolchain.
  if [[ ! -f "$module/package.json" ]]; then
    printf 'FAIL %s: missing package.json\n' "$module" >&2
    fail=1
    continue
  fi
  (
    cd "$module"
    npm ci --no-audit --no-fund
    npm run typecheck
    npm run lint
    npm test
  ) || fail=1

  # TypeScript only.
  bad_js=()
  for file in "${sources[@]}"; do
    case "$file" in
      *.js|*.jsx) bad_js+=("$file") ;;
    esac
  done
  if [[ ${#bad_js[@]} -gt 0 ]]; then
    printf 'FAIL %s: JavaScript files must be TypeScript: %s\n' "$module" "${bad_js[*]}" >&2
    fail=1
  fi

  # tsconfig strict flags.
  tsconfig="$module/tsconfig.json"
  if [[ ! -f "$tsconfig" ]]; then
    printf 'FAIL %s: missing %s\n' "$module" "$tsconfig" >&2
    fail=1
  else
    for flag in '"strict"[[:space:]]*:[[:space:]]*true' '"noUncheckedIndexedAccess"[[:space:]]*:[[:space:]]*true'; do
      if ! grep -Eq "$flag" "$tsconfig"; then
        printf 'FAIL %s: tsconfig missing %s\n' "$module" "$flag" >&2
        fail=1
      fi
    done
  fi

  # No any / ts-ignore in source, and React must use function components.
  for file in "${sources[@]}"; do
    case "$file" in
      *.ts|*.tsx) ;;
      *) continue ;;
    esac
    if grep -nE '@ts-ignore|:[[:space:]]*any\b|as[[:space:]]+any\b|class[[:space:]]+[A-Z][A-Za-z0-9_]*[[:space:]]+extends[[:space:]]+(React\.)?(Component|PureComponent)\b' "$file"; then
      printf 'FAIL %s: forbidden pattern in %s\n' "$module" "$file" >&2
      fail=1
    fi
  done
done

if [[ $fail -ne 0 ]]; then
  exit 1
fi

# Toolchain commands must pass; npm ci makes the gate self-contained in CI.
npm ci --no-audit --no-fund
npm run typecheck
npm run lint
npm run format:check
npm test

printf 'frontend standards: policy and tool commands passed\n'
