#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${E2E_USERNAME:?Set E2E_USERNAME to a seeded demo user}"
: "${E2E_PASSWORD:?Set E2E_PASSWORD to the demo password}"
export POSTGRES_PASSWORD="${E2E_POSTGRES_PASSWORD:-e2e-local-only}"
case "${1:-}" in
  "") services=(frontend control-panel engine sms-mock mailpit) ;;
  --latency) services=(control engine sms-mock mailpit) ;;
  *) echo "Usage: $0 [--latency]" >&2; exit 2 ;;
esac
read -r E2E_CONTROL_PORT E2E_ENGINE_PORT E2E_MONITOR_PORT E2E_PANEL_PORT MAILPIT_PORT SMS_MOCK_PORT < <(
  python3 -c 'import socket
sockets = [socket.socket() for _ in range(6)]
for s in sockets: s.bind(("127.0.0.1", 0))
print(*(s.getsockname()[1] for s in sockets))'
)
export MAILPIT_PORT SMS_MOCK_PORT
export MAILPIT_URL="http://127.0.0.1:$MAILPIT_PORT" SMS_MOCK_URL="http://127.0.0.1:$SMS_MOCK_PORT"
export E2E_CONTROL_PORT E2E_ENGINE_PORT E2E_MONITOR_PORT E2E_PANEL_PORT
export CONTROL_URL="http://127.0.0.1:$E2E_CONTROL_PORT"
export ENGINE_URL="http://127.0.0.1:$E2E_ENGINE_PORT"
export MONITOR_URL="http://127.0.0.1:$E2E_MONITOR_PORT"
export PANEL_URL="http://127.0.0.1:$E2E_PANEL_PORT"
project="runbook-e2e-$RANDOM-$$"
compose=(docker compose -p "$project" -f docker-compose.yml -f scripts/e2e-compose.yml)
if [[ "${1:-}" == "--latency" ]]; then
  compose+=(-f scripts/latency-compose.yml)
fi
cleanup() { "${compose[@]}" down --volumes --remove-orphans --rmi local >/dev/null; }
trap cleanup EXIT
"${compose[@]}" up -d --build "${services[@]}"
urls=("$CONTROL_URL/actuator/health" "$ENGINE_URL/actuator/health" "$SMS_MOCK_URL/messages" "$MAILPIT_URL/api/v1/messages")
if [[ "${1:-}" != "--latency" ]]; then urls+=("$MONITOR_URL" "$PANEL_URL"); fi
for url in "${urls[@]}"; do
  ready=false
  for ((attempt=0; attempt<60; attempt++)); do
    if curl --silent --fail --max-time 2 "$url" >/dev/null; then ready=true; break; fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then
    "${compose[@]}" logs --no-color --tail 100
    exit 1
  fi
done
if [[ "${1:-}" == "--latency" ]]; then
  python3 scripts/delivery_latency.py
  exit
fi
python3 scripts/test-notification-flow.py
node control-panel/e2e-suite/tests/run.mjs
CONTROL_URL="$CONTROL_URL" ENGINE_URL="$ENGINE_URL" python3 scripts/test-python-runbook.py
"${compose[@]}" exec -T script-runner python -B - < script-runner/test_runner.py
