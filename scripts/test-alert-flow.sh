#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v jq >/dev/null || { echo 'Test wymaga jq.' >&2; exit 1; }
env_file=.env
[[ -f "$env_file" ]] || env_file=.env.example
compose=(docker compose --env-file "$env_file" -f docker-compose.yml -f scripts/alert-smoke.compose.yml)
"${compose[@]}" up --build -d postgres redis engine control

wait_for_log() {
  local service=$1 needle=$2 line
  for ((i=0; i<60; i++)); do
    line=$("${compose[@]}" logs --no-color --tail 2000 "$service" | grep -F "$needle" | tail -1) || true
    if [[ -n "$line" ]]; then echo "$line"; return 0; fi
    sleep 1
  done
  echo "Brak potwierdzenia w logach $service po 60 próbach: $needle" >&2
  return 1
}

wait_for_health() {
  local port=$1
  for ((attempt=0; attempt<60; attempt++)); do
    if curl -fsS --max-time 3 "http://localhost:$port/actuator/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Brak gotowości aplikacji na porcie $port." >&2
  return 1
}
wait_for_health 8081
wait_for_health 8082

cookies=$(mktemp)
trap 'rm -f "$cookies"' EXIT
curl -fsS -c "$cookies" http://localhost:8082/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo.member","password":"Runbook123!"}' >/dev/null
run=$(uuidgen)
"${compose[@]}" exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
INSERT INTO teams(name) VALUES ('smoke-alerts') ON CONFLICT (name) DO NOTHING;
SQL

rule=$(curl -fsS -b "$cookies" http://localhost:8082/api/rules -H 'Content-Type: application/yaml' --data-binary @- <<YAML
name: Smoke $run
match:
  all:
    - {path: payload.smokeRun, op: eq, value: "$run"}
    - {path: payload.error.code, op: eq, value: TIMEOUT}
title: "Timeout w {{payload.service}}"
route: {teams: [smoke-alerts]}
runbook: {name: "smoke-$run", format: text, content: "Check {{payload.service}}"}
YAML
)
rule_id=$(jq -er '.id' <<<"$rule")
echo "Control utworzył regułę $rule_id"
wait_for_log engine "Indexed rule ruleId=$rule_id version=1"

payload=$(jq -nc --arg run "$run" '{smokeRun:$run, service:"payments", error:{code:"TIMEOUT"}}')
echo "POST /ingest $payload"
event=$(curl -fsS http://localhost:8081/ingest -H 'Content-Type: application/json' --data-binary "$payload")
event_id=$(jq -er '.eventId' <<<"$event")
echo "Engine przyjął zdarzenie $event_id"
receipt=$(wait_for_log control "Received alert ruleId=$rule_id eventId=$event_id alertId=")
alert_id=${receipt##*alertId=}
book_id=$(curl -fsS http://localhost:8082/api/runbooks | jq -er --arg name "smoke-$run" '.[] | select(.name == $name) | .id')
curl -fsS "http://localhost:8082/api/alerts/$alert_id" | jq -e --arg id "$book_id"   '.runbookId == $id and .runbook.id == $id and .runbook.content == "Check payments"' >/dev/null
count=$("${compose[@]}" exec -T postgres sh -c 'psql -qAt -v ON_ERROR_STOP=1 -v rule="$1" -v event="$2" -U "$POSTGRES_USER" -d "$POSTGRES_DB"' sh "$rule_id" "$event_id" <<'SQL'
SELECT count(*) FROM live_alert_inbox WHERE payload->>'ruleId' = :'rule' AND payload->>'eventId' = :'event';
SQL
)
[[ "$count" == 1 ]] || { echo "Oczekiwano jednego alertu w bazie, znaleziono $count." >&2; exit 1; }
curl -fsS -b "$cookies" http://localhost:8082/api/rules -H 'Content-Type: application/yaml' --data-binary @- >/dev/null <<YAML
name: Smoke reuse $run
title: Follow-up
match: {path: payload.followUpRun, op: eq, value: "$run"}
route: {teams: [smoke-alerts]}
runbook: "smoke-$run"
YAML
echo "OK — Control zapisał jeden alert dla wysłanego zdarzenia i utworzonej reguły."
