#!/usr/bin/env bash
# Opt-in mutation testing (PIT) of the authz, audit and tenant packages.
# Slow, so it is not part of check.sh. Usage: scripts/mutation-check.sh [min-mutation-percent]
set -euo pipefail
cd "$(dirname "$0")/../apps/api"
exec mvn -B -Pmutation -DexcludedGroups=integration -Dpitest.threshold="${1:-0}" \
  test-compile org.pitest:pitest-maven:mutationCoverage
