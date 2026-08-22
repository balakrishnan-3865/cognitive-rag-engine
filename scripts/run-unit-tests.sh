#!/usr/bin/env bash
# Fast unit/integration test suite. Mandatory verification gate during
# feature implementation - do not run `./mvnw test` directly.
# Usage: run-unit-tests.sh [TestClassName]   (optional: scope to one class)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/ensure-env.sh"
source "${SCRIPT_DIR}/lib/report.sh"

if [[ $# -ge 1 ]]; then
    run_mvn_tests "unit-tests" test "-Dtest=$1"
else
    run_mvn_tests "unit-tests" test
fi
