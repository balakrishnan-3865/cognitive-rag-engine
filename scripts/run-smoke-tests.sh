#!/usr/bin/env bash
# Critical-path smoke tests: application context loads and basic wiring
# tests pass. Mandatory verification gate during feature implementation -
# do not run `./mvnw test` directly.
#
# Convention: any class named `*SmokeTest` is picked up automatically,
# alongside the baseline application context-load test.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/ensure-env.sh"
source "${SCRIPT_DIR}/lib/report.sh"

run_mvn_tests "smoke-tests" test -Dtest=CognitiveRagEngineApplicationTests,**/*SmokeTest -DfailIfNoTests=false
