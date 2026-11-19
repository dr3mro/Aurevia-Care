#!/usr/bin/env bash
# Pre-merge gate for the swarm integrator. Runs the full canonical check plus
# the developer reference (Doxygen/TypeDoc), so broken Javadoc on worker PRs is
# rejected before it can land on main.
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/check.sh
bash scripts/docs.sh
