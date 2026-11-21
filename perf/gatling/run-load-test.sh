#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

command -v docker >/dev/null || { echo 'Docker Compose v2 is required.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required.' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq is required.' >&2; exit 1; }

rps="${RPS:-1000}"
duration="${DURATION_SECONDS:-60}"
ramp="${RAMP_SECONDS:-15}"
replicas="${ENGINE_REPLICAS:-3}"
drain_timeout="${DRAIN_TIMEOUT_SECONDS:-600}"
max_ui_p95_ms="${MAX_UI_P95_MS:-5000}"
channels="${PERF_CHANNELS:-ui}"
engine_port="${PERF_ENGINE_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')}"
control_port="${PERF_CONTROL_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')}"
export PERF_ENGINE_PORT="$engine_port"
export PERF_CONTROL_PORT="$control_port"
project="gatling-main-$(date +%Y%m%d%H%M%S)-$$"
load_run="load-$(date +%s)-$$"
compose=(docker compose -p "$project" -f docker-compose.yml -f perf/gatling/compose.override.yml)
cookie_file="$(mktemp)"
recipient_cookie="$(mktemp)"
report_log="$(mktemp)"
rule_id=""

cleanup() {
  rm -f "$cookie_file" "$recipient_cookie" "$report_log"
  if [[ "${KEEP_PERF_STACK:-0}" != 1 ]]; then
    POSTGRES_PASSWORD=perf-only-not-a-secret "${compose[@]}" down --rmi local --volumes --remove-orphans >/dev/null 2>&1 || true
  else
    echo "Isolated Compose project retained: $project"
  fi
}
trap cleanup EXIT

export POSTGRES_PASSWORD=perf-only-not-a-secret
export ENGINE_REPLICAS="$replicas"
echo "Starting isolated stack $project (Engine replicas=$replicas, offered rate=$rps RPS)."
[[ "$max_ui_p95_ms" =~ ^[1-9][0-9]*$ ]] || { echo 'MAX_UI_P95_MS must be a positive integer.' >&2; exit 1; }
case "$channels" in
  ui|ui,email,sms) ;;
  *) echo 'PERF_CHANNELS must be ui or ui,email,sms.' >&2; exit 1 ;;
esac
"${compose[@]}" up --build --detach postgres redis script-runner mailpit sms-mock control engine engine-lb

wait_http() {
  local url="$1" label="$2"
  for _ in $(seq 1 180); do
    if curl --silent --show-error --fail --max-time 3 "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for $label at $url" >&2
  "${compose[@]}" ps >&2
  "${compose[@]}" logs --tail 100 control engine >&2
  return 1
}

wait_http "http://localhost:$engine_port/actuator/health" Engine
wait_http "http://localhost:$control_port/actuator/health" Control

curl --silent --show-error --fail --cookie-jar "$cookie_file" \
  "http://localhost:$control_port/api/auth/login" \
  -H 'Content-Type: application/json' \
  --data '{"username":"demo.leader","password":"Runbook123!"}' >/dev/null

if [[ "$channels" == "ui,email,sms" ]]; then
  curl --silent --show-error --fail --cookie-jar "$recipient_cookie" \
    "http://localhost:$control_port/api/auth/login" \
    -H 'Content-Type: application/json' \
    --data '{"username":"demo.member","password":"Runbook123!"}' >/dev/null
  curl --silent --show-error --fail --cookie "$recipient_cookie" \
    "http://localhost:$control_port/api/me/notification-contacts/email" \
    -X PUT -H 'Content-Type: application/json' \
    --data "{\"address\":\"$load_run@example.test\"}" >/dev/null
  curl --silent --show-error --fail --cookie "$recipient_cookie" \
    "http://localhost:$control_port/api/me/notification-contacts/sms" \
    -X PUT -H 'Content-Type: application/json' \
    --data '{"address":"+15555550100"}' >/dev/null
  curl --silent --show-error --fail --cookie "$recipient_cookie" \
    "http://localhost:$control_port/api/me/notification-preferences" \
    -X PUT -H 'Content-Type: application/json' \
    --data '{"ui":{"enabled":true,"minSeverity":"info"},"email":{"enabled":true,"minSeverity":"info"},"sms":{"enabled":true,"minSeverity":"info"}}' >/dev/null
fi

rule_yaml="$(cat <<YAML
name: Gatling $load_run
match: {path: payload.loadRun, op: eq, value: "$load_run"}
title: "Load test event {{payload.sequence}}"
route: {users: [demo.member]}
channels: [$channels]
dedup: {keys: [payload.sequence], windowSec: 60}
YAML
)"
rule_response="$(curl --silent --show-error --fail --cookie "$cookie_file" \
  "http://localhost:$control_port/api/rules" \
  -H 'Content-Type: application/yaml' --data-binary "$rule_yaml")"
rule_id="$(jq -er '.id' <<<"$rule_response")"
echo "Created isolated matching rule $rule_id for demo.member channels: $channels."

for _ in $(seq 1 60); do
  active="$(curl --silent --show-error --fail "http://localhost:$engine_port/api/engine/snapshot" \
    | jq -r --arg id "$rule_id" '.[] | select(.ruleId == $id and .state == "active") | .ruleId' || true)"
  [[ "$active" == "$rule_id" ]] && break
  sleep 1
done
[[ "${active:-}" == "$rule_id" ]] || { echo 'Engine did not activate the load-test rule.' >&2; exit 1; }

network="${project}_default"
echo "Running Gatling: $rps RPS for ${duration}s after a ${ramp}s ramp."
docker run --rm --network "$network" \
  -v "$repo_root/perf/gatling:/work" -w /work \
  maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp gatling:test \
    '-Dgatling.simulationClass=com.goldman.alerting.perf.EngineIngestSimulation' \
    "-Drps=$rps" "-DrampSeconds=$ramp" "-DdurationSeconds=$duration" \
    '-DmaxP95Ms=1000' '-DbaseUrl=http://engine-lb:8081' "-DloadRun=$load_run" \
  2>&1 | tee "$report_log"

expected_events="$(grep -E '^> request count[[:space:]]+\|' "$report_log" | tail -1 | sed -E 's/.*\|[[:space:]]*([0-9,]+)[[:space:]]*\|.*/\1/' | tr -d ',')"
[[ "$expected_events" =~ ^[0-9]+$ ]] || { echo 'Could not read the Gatling request count.' >&2; exit 1; }
echo "Waiting up to ${drain_timeout}s for all $expected_events alerts and UI notification rows."
for _ in $(seq 1 "$drain_timeout"); do
  counts="$("${compose[@]}" exec -T postgres sh -c \
    'psql -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v rule="$1"' sh "$rule_id" <<'SQL'
SELECT count(*) || ':' || (
  SELECT count(*) FROM notification_deliveries d
  JOIN live_alert_inbox i ON i.alert_id = d.alert_id
  WHERE i.payload->>'ruleId' = :'rule' AND d.channel_type = 'ui' AND d.status = 'sent'
)
FROM live_alert_inbox i WHERE i.payload->>'ruleId' = :'rule';
SQL
  )"
  alerts="${counts%%:*}"
  ui_deliveries="${counts##*:}"
  if (( alerts >= expected_events && ui_deliveries >= expected_events )); then break; fi
  sleep 1
done

echo "Matching alerts: $alerts; persisted UI notification rows: $ui_deliveries."
if (( alerts < expected_events || ui_deliveries < expected_events )); then
  echo "Pipeline did not drain the full Gatling request count ($expected_events) within ${drain_timeout}s." >&2
  exit 1
fi

echo 'End-to-end UI latency from Engine event acceptance timestamp to Control notification commit (milliseconds):'
"${compose[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v rule="$1" -P pager=off' sh "$rule_id" <<'SQL'
SELECT count(*) AS deliveries,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY latency_ms))::numeric, 2) AS p50_ms,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms))::numeric, 2) AS p95_ms,
       round((percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms))::numeric, 2) AS p99_ms,
       round(max(latency_ms)::numeric, 2) AS max_ms
FROM (
  SELECT extract(epoch FROM (d.created_at - (i.payload->>'occurredAt')::timestamptz)) * 1000 AS latency_ms
  FROM notification_deliveries d
  JOIN live_alert_inbox i ON i.alert_id = d.alert_id
  WHERE i.payload->>'ruleId' = :'rule' AND d.channel_type = 'ui' AND d.status = 'sent'
) latencies;
SQL

e2e_p95_ms="$("${compose[@]}" exec -T postgres sh -c \
  'psql -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v rule="$1"' sh "$rule_id" <<'SQL'
SELECT ceil(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms))::bigint
FROM (
  SELECT extract(epoch FROM (d.created_at - (i.payload->>'occurredAt')::timestamptz)) * 1000 AS latency_ms
  FROM notification_deliveries d
  JOIN live_alert_inbox i ON i.alert_id = d.alert_id
  WHERE i.payload->>'ruleId' = :'rule' AND d.channel_type = 'ui' AND d.status = 'sent'
) latencies;
SQL
)"
echo "UI end-to-end p95: ${e2e_p95_ms}ms (limit ${max_ui_p95_ms}ms)."

echo 'Notification delivery outcomes by channel:'
"${compose[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v rule="$1" -P pager=off' sh "$rule_id" <<'SQL'
SELECT d.channel_type, d.status, count(*) AS deliveries
FROM notification_deliveries d
JOIN live_alert_inbox i ON i.alert_id = d.alert_id
WHERE i.payload->>'ruleId' = :'rule'
GROUP BY d.channel_type, d.status
ORDER BY d.channel_type, d.status;
SQL

if (( e2e_p95_ms > max_ui_p95_ms )); then
  echo 'UI end-to-end latency exceeded the configured load-test target.' >&2
  exit 1
fi

echo "Gatling HTML reports are under perf/gatling/target/gatling/. Rule: $rule_id; test run: $load_run"
