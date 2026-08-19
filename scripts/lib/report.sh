#!/usr/bin/env bash
# Shared helper: run Maven and print only a concise pass/fail summary instead
# of dumping the full build log into the active session.
#
# Usage: run_mvn_tests <log-label> [extra mvnw args...]
set -euo pipefail

REPORT_LIB_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MAX_FAILED_CLASSES=5
MAX_LINES_PER_FAILURE=60
MAX_LOG_TAIL_LINES=80

run_mvn_tests() {
    local label="$1"
    shift
    local log_dir="${REPORT_LIB_REPO_ROOT}/target/claude-test-logs"
    mkdir -p "$log_dir"
    local log_file="${log_dir}/${label}-$(date +%Y%m%d-%H%M%S).log"

    echo "[${label}] Running: ./mvnw $* (full log: ${log_file})"

    local exit_code=0
    ( cd "$REPORT_LIB_REPO_ROOT" && ./mvnw -B "$@" ) >"$log_file" 2>&1 || exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        local run_line
        run_line="$(grep -E 'Tests run: [0-9]+, Failures' "$log_file" | tail -1 | sed -E 's/^\[INFO\] *//' || true)"
        echo "[${label}] PASSED. ${run_line:-no tests matched}"
        return 0
    fi

    echo "[${label}] FAILED (exit ${exit_code}). Summary below:"
    summarize_failures "$log_file"
    return "$exit_code"
}

summarize_failures() {
    local log_file="$1"
    local surefire_dir="${REPORT_LIB_REPO_ROOT}/target/surefire-reports"

    local failed_reports=()
    if [[ -d "$surefire_dir" ]]; then
        while IFS= read -r -d '' report; do
            failed_reports+=("$report")
        done < <(grep -lZE 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$surefire_dir"/*.txt 2>/dev/null || true)
    fi

    if [[ ${#failed_reports[@]} -eq 0 ]]; then
        # No per-test failures found (e.g. compile error) - fall back to the
        # tail of the build log, which is where Maven prints ERROR blocks.
        echo "--- last ${MAX_LOG_TAIL_LINES} lines of build log ---"
        tail -n "$MAX_LOG_TAIL_LINES" "$log_file"
        return
    fi

    local shown=0
    for report in "${failed_reports[@]}"; do
        if (( shown >= MAX_FAILED_CLASSES )); then
            echo "... and $(( ${#failed_reports[@]} - shown )) more failed test class(es) truncated. See $(basename "$log_file")."
            break
        fi
        echo "--- $(basename "$report" .txt) ---"
        head -n "$MAX_LINES_PER_FAILURE" "$report"
        shown=$(( shown + 1 ))
    done
}
