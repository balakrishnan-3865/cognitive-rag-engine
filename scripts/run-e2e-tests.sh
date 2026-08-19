#!/usr/bin/env bash
# End-to-end integration tests against the full Docker environment.
# Mandatory verification gate during feature implementation - do not run
# `./mvnw test` directly.
#
# Convention: any class named `*E2ETest` is picked up automatically. If none
# exist yet, this is a no-op (reported below, not treated as a failure).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/ensure-env.sh"
source "${SCRIPT_DIR}/lib/report.sh"

if ! find "${SCRIPT_DIR}/../src/test/java" -name '*E2ETest.java' -print -quit | grep -q .; then
    echo "[e2e-tests] No *E2ETest classes found yet. Nothing to run."
    exit 0
fi

run_mvn_tests "e2e-tests" test -Dtest=**/*E2ETest -DfailIfNoTests=false
