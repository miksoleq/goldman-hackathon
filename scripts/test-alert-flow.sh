#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v jq >/dev/null || { echo 'Test wymaga jq.' >&2; exit 1; }
env_file=.env
[[ -f "$env_file" ]] || env_file=.env.example
compose=(docker compose --env-file "$env_file" -f docker-compose.yml -f scripts/alert-smoke.compose.yml)
"${compose[@]}" up --build -d postgres redis engine engine-lb control workflow-demo

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
session_cookie=$(mktemp)
trap 'rm -f "$session_cookie"' EXIT
curl -fsS -c "$session_cookie" http://localhost:8082/api/auth/login -H 'Content-Type: application/json' \
  --data '{"username":"demo.member","password":"Runbook123!"}' >/dev/null

run=$(uuidgen)
"${compose[@]}" exec -T workflow-demo python /app/server.py seed "$run"
"${compose[@]}" exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
INSERT INTO teams(name) VALUES ('smoke-alerts'), ('smoke-oncall') ON CONFLICT (name) DO NOTHING;
SQL

rule=$(curl -fsS -b "$session_cookie" http://localhost:8082/api/rules -H 'Content-Type: application/yaml' --data-binary @- <<YAML
name: Smoke $run
match:
  all:
    - {path: payload.smokeRun, op: eq, value: "$run"}
    - {path: payload.error.code, op: eq, value: TIMEOUT}
title: "Timeout w {{payload.service}}"
route: {teams: [smoke-alerts]}
runbook:
  name: "smoke-$run"
  format: text
  content: "Check {{payload.service}}"
  actions:
    - {type: check_workflow_status, params: {workflowId: "{{payload.workflowId}}"}}
    - {type: retry_workflow, params: {workflowId: "{{payload.workflowId}}"}}
    - {type: escalate, params: {team: smoke-oncall}}
YAML
)
rule_id=$(jq -er '.id' <<<"$rule")
echo "Control utworzył regułę $rule_id"
wait_for_log engine "Indexed rule ruleId=$rule_id version=1"

payload=$(jq -nc --arg run "$run" '{smokeRun:$run, workflowId:$run, service:"payments", error:{code:"TIMEOUT"}}')
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
curl -fsS -b "$session_cookie" http://localhost:8082/api/rules -H 'Content-Type: application/yaml' --data-binary @- >/dev/null <<YAML
name: Smoke reuse $run
title: Follow-up
match: {path: payload.followUpRun, op: eq, value: "$run"}
route: {teams: [smoke-alerts]}
runbook: "smoke-$run"
YAML
echo "OK — Control zapisał jeden alert dla wysłanego zdarzenia i utworzonej reguły."

if [[ ${1:-} == --prepare ]]; then
  echo "Alert z automatycznym runbookiem w monitorze: $alert_id"
  exit 0
fi

for ((attempt=0; attempt<60; attempt++)); do
  detail=$(curl -fsS "http://localhost:8082/api/alerts/$alert_id")
  if jq -e 'any(.actions[]; .status == "failed" or .status == "unknown" or .status == "skipped" or .status == "unavailable")' <<<"$detail" >/dev/null; then
    jq '.actions' <<<"$detail" >&2
    exit 1
  fi
  if jq -e '(.actions | length) == 3 and all(.actions[]; .automatic and .status == "succeeded")' <<<"$detail" >/dev/null; then break; fi
  sleep 1
done
jq -e '.actions[0].result.response.status == "failed" and .actions[1].result.response.status == "running"
  and .actions[2].result.team == "smoke-oncall"' <<<"$detail" >/dev/null
team_id=$("${compose[@]}" exec -T postgres sh -c 'psql -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT id FROM teams WHERE name = 'smoke-oncall';
SQL
)
curl -fsS "http://localhost:8082/api/alerts/$alert_id" | jq -e --arg team "$team_id" \
  '.severity == "critical" and .teamIds == [$team] and ([.actions[].status] | all(. == "succeeded"))' >/dev/null
curl -fsS http://localhost:8082/api/monitor/alerts | jq -e --arg id "$alert_id" --arg team "$team_id" \
  'any(.[]; .alertId == $id and .severity == "critical" and .teamIds == [$team])' >/dev/null
echo "OK — status, retry i eskalacja zapisane dla alertu $alert_id."
