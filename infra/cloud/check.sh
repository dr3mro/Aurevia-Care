#!/usr/bin/env bash
# Canonical BUILD-58 gate: offline Terraform policy checks, unit tests and a
# build-failing 100% line-coverage gate for the checker itself.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I tests/coverage_gate.py

# The committed Terraform must satisfy every OPS-02/OPS-06 control.
python3 -I terraform_policy_check.py . >/dev/null

# The synthetic negative fixture must fail with the policy-breach exit code.
if python3 -I terraform_policy_check.py fixtures >/dev/null 2>&1; then
  printf 'cloud: violation fixture unexpectedly passed\n' >&2
  exit 1
else
  violation_status=$?
  if [ "$violation_status" -ne 1 ]; then
    printf 'cloud: violation fixture must exit 1, got %s\n' "$violation_status" >&2
    exit 1
  fi
fi

printf 'cloud: OPS-06/OPS-02 Terraform policy gate passed\n'
