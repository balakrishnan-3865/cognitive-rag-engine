#!/usr/bin/env bash
# Idempotent Docker environment bootstrap for tests.
#
# Treats the Docker environment as long-lived: if the required containers
# are already up and healthy, this is a fast no-op. It never runs
# `docker compose down` — infra stays up across test runs, and test data
# isolation is the test suite's responsibility (DB cleanup utils / Flyway),
# not this script's.
#
# Usage: source this file from run-*-tests.sh, or run it directly to just
# bring the environment up.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_ARGS=(-f "${REPO_ROOT}/deploy/docker-compose.yml" -f "${REPO_ROOT}/deploy/docker-compose-test.yml")

# Services with healthchecks that tests actually depend on.
REQUIRED_SERVICES=(postgres minio elasticsearch docling postgres-test elasticsearch-test)

WAIT_TIMEOUT_SECS=180
POLL_INTERVAL_SECS=3

container_id_for() {
    docker compose "${COMPOSE_ARGS[@]}" ps -q "$1" 2>/dev/null
}

is_healthy_or_running() {
    local cid="$1"
    local health
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo "unknown")"
    [[ "$health" == "healthy" || "$health" == "running" ]]
}

all_services_up() {
    local svc cid
    for svc in "${REQUIRED_SERVICES[@]}"; do
        cid="$(container_id_for "$svc")"
        [[ -n "$cid" ]] || return 1
        is_healthy_or_running "$cid" || return 1
    done
    return 0
}

if all_services_up; then
    echo "[ensure-env] All required containers already up and healthy. Reusing them."
    return 0 2>/dev/null || exit 0
fi

echo "[ensure-env] Bringing up required containers (existing ones are reused, not recreated)..."
docker compose "${COMPOSE_ARGS[@]}" up -d "${REQUIRED_SERVICES[@]}" >/dev/null

echo -n "[ensure-env] Waiting for containers to become healthy"
elapsed=0
while (( elapsed < WAIT_TIMEOUT_SECS )); do
    if all_services_up; then
        echo " done."
        return 0 2>/dev/null || exit 0
    fi
    echo -n "."
    sleep "$POLL_INTERVAL_SECS"
    elapsed=$(( elapsed + POLL_INTERVAL_SECS ))
done

echo
echo "[ensure-env] TIMEOUT after ${WAIT_TIMEOUT_SECS}s waiting for containers to become healthy."
for svc in "${REQUIRED_SERVICES[@]}"; do
    cid="$(container_id_for "$svc")"
    if [[ -z "$cid" ]]; then
        echo "  - ${svc}: not created"
    else
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo "unknown")"
        echo "  - ${svc}: ${status}"
    fi
done
exit 1
