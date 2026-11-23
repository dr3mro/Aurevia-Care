#!/usr/bin/env bash
# Frontend standards gate. Enforces AGENTS.md rules and runs the required tools.
# Workers and the Delivery CI job both call this, so violations fail the build.
set -euo pipefail
cd "$(dirname "$0")/.."

modules=(apps/staff-web apps/patient-mobile)
fail=0

# npm audit reports registry/network failures with these codes. A registry outage
# must fail the gate as infrastructure, never be mistaken for a clean result.
npm_infra_re='ENOTFOUND|ECONNRESET|ECONNREFUSED|EAI_AGAIN|ENETUNREACH|ETIMEDOUT|ESOCKETTIMEDOUT|ENOAUDITENDPOINT|ENOLOCK|FETCH_ERROR|command not found|npm ERR!'
audit_notice_printed=0

# Classify an npm audit outcome as infrastructure: an unusable npm binary
# (126/127) or a registry/network error signature. Checked before severity so
# an outage is never mislabelled as a vulnerability finding or a clean result.
audit_is_infrastructure() {
  local output="$1" status="$2"
  [[ $status -ge 126 ]] || [[ "$output" =~ $npm_infra_re ]]
}

for module in "${modules[@]}"; do
  sources=()
  while IFS= read -r file; do
    sources+=("$file")
  done < <(find "$module" -type f \( -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.jsx' \) \
    -not -path '*/node_modules/*' -not -path '*/dist/*' -not -path '*/coverage/*' \
    -not -path '*/build/*' -not -path '*/.expo/*' 2>/dev/null | sort)

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

  # Production dependency scan (WEB-12/OPS-06): the npm equivalent of the Java
  # OWASP gate. --omit=dev keeps dev-only findings out of the failing path;
  # --audit-level=high makes npm exit non-zero for high/critical findings only.
  if [[ "${FRONTEND_AUDIT:-}" == "skip" ]]; then
    if [[ $audit_notice_printed -eq 0 ]]; then
      printf 'NOTICE FRONTEND_AUDIT=skip: npm dependency audit DISABLED; high/critical vulnerabilities in production dependencies will NOT be detected. Use only for local development.\n' >&2
      audit_notice_printed=1
    fi
  else
    prod_out='' prod_status=0 dev_out='' dev_status=0
    prod_out=$(cd "$module" && npm audit --omit=dev --audit-level=high 2>&1) || prod_status=$?
    if audit_is_infrastructure "$prod_out" "$prod_status"; then
      printf 'INFRASTRUCTURE %s: npm audit could not reach the registry (failure, not a pass)\n' "$module" >&2
      printf '%s\n' "$prod_out" >&2
      fail=1
    elif [[ $prod_status -ne 0 ]]; then
      printf 'FAIL %s: high or critical vulnerability in production dependencies\n' "$module" >&2
      printf '%s\n' "$prod_out" >&2
      fail=1
    else
      dev_out=$(cd "$module" && npm audit --audit-level=high 2>&1) || dev_status=$?
      if audit_is_infrastructure "$dev_out" "$dev_status"; then
        printf 'INFRASTRUCTURE %s: npm audit could not reach the registry (failure, not a pass)\n' "$module" >&2
        printf '%s\n' "$dev_out" >&2
        fail=1
      elif [[ $dev_status -ne 0 ]]; then
        printf 'WARN %s: high or critical vulnerability in development dependencies only (does not fail the gate)\n' "$module" >&2
        printf '%s\n' "$dev_out" >&2
      fi
    fi
  fi

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

# Generated client must match the OpenAPI contract. A worker that edits
# contracts/openapi.yaml but forgets gen:api must fail here, not at the integrator.
if [[ -f apps/staff-web/package.json ]]; then
  (cd apps/staff-web && npm ci --no-audit --no-fund && npm run gen:api:check)
fi

# Toolchain commands must pass; npm ci makes the gate self-contained in CI.
npm ci --no-audit --no-fund
npm run typecheck
npm run lint
npm run format:check
npm test

printf 'frontend standards: policy and tool commands passed\n'
