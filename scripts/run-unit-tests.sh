#!/usr/bin/env bash
# Fast unit/integration test suite. Mandatory verification gate during
# feature implementation - do not run `./mvnw test` directly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/ensure-env.sh"
source "${SCRIPT_DIR}/lib/report.sh"

run_mvn_tests "unit-tests" test
