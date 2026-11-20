# Engine ingest skeleton

Minimalny backend przyjmuje dowolny JSON z trzech wejść i zapisuje go jako kopertę zdarzenia w Redis Streams (`events:ingest`). Nie ma jeszcze walidacji biznesowej, workera ani reguł przetwarzania.

## Uruchomienie

Wymagany Docker z Docker Compose. Skopiuj lokalną konfigurację środowiska (w PowerShell):

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Potem uruchom cały zestaw. Ingest HTTP wystawia port `8081`, `control` port `8082`, a PostgreSQL jest dostępny na `localhost:55432` (port hosta można zmienić przez `POSTGRES_PORT`).

```powershell
docker compose up --build
```

Opcjonalnie uruchom też Kafka KRaft i listener Kafka:

```powershell
$env:INGEST_KAFKA_ENABLED = "true"
docker compose --profile kafka up --build
```

Domyślny topic to `ingest.external`; broker dostępny lokalnie pod `localhost:9092`.

## Control i wersje schematu

`control` jest właścicielem migracji bazy. Przy starcie Spring Boot/Flyway sprawdza `flyway_schema_history` i stosuje tylko te migracje, których wersji jeszcze tam nie ma. Uruchom samą bazę i control (bez engine):

```powershell
docker compose up -d postgres
docker compose up --build -d control
```

Wersję bieżącą, wykonane migracje i oczekujące zmiany sprawdzisz przez API:

```powershell
Invoke-RestMethod http://localhost:8082/internal/migrations | ConvertTo-Json -Depth 5
docker compose exec postgres psql -U alerting -d alerting -c "SELECT version, description, success FROM flyway_schema_history;"
docker compose exec postgres psql -U alerting -d alerting -c "\dt"
```

Połączenie lokalne: host `localhost`, port `55432`, baza `alerting`, użytkownik `alerting`, hasło z pliku `.env`. Inne kontenery Compose łączą się z hostem `postgres` i portem `5432`.

### Co robi plik SQL

`backend/control/src/main/resources/db/migration/V1__init.sql` opisuje pierwszy schemat aplikacji; sam plik nie uruchamia serwera ani nie tworzy bazy. Usługa `postgres` tworzy bazę i użytkownika z konfiguracji środowiska. `control` czeka na gotowość Postgresa, a Flyway wykonuje migrację i zapisuje jej wersję w `flyway_schema_history`. Dzięki temu kolejne uruchomienie nie wykonuje `V1` drugi raz.

W `V1__init.sql` są cztery enumy statusów, tabele konfiguracji (zespoły, użytkownicy, kanały, reguły i runbooki), tabele operacyjne (alerty, powiadomienia, wykonania akcji i audyt), klucze obce oraz indeksy. `jsonb` przechowuje elastyczne definicje reguł, konfiguracje kanałów i surowe zdarzenia. Skrypt dodaje też początkowy wiersz wersji konfiguracji i wbudowane typy akcji.

Postgres przechowuje dane w nazwanym wolumenie `postgres_data`, więc zwykłe zatrzymanie lub usunięcie kontenera ich nie kasuje. `docker compose down -v` usuwa ten wolumen i wszystkie dane; używaj tego tylko wtedy, gdy świadomie chcesz wyzerować bazę. Nowe zmiany schematu zapisuj w kolejnych plikach w katalogu migracji, np. `V2__add_column.sql`; `control` zastosuje je przy następnym starcie.

Na tym etapie `control` zajmuje się połączeniem z bazą, migracjami i raportowaniem wersji przez `GET /internal/migrations`. Nie ma jeszcze CRUD konfiguracji ani alertów. `engine` nadal nie łączy się bezpośrednio z PostgreSQL.

## Wejścia

### HTTP

`POST /ingest` przyjmuje `Content-Type: application/json`. Payload może być dowolną wartością JSON: obiektem, tablicą, tekstem, liczbą, booleanem albo `null`. Limit wiadomości wynosi domyślnie 1 MiB (`INGEST_MAX_PAYLOAD_BYTES`) dla wszystkich adapterów. W kopercie HTTP pole `source` ma wartość `null`. Jeśli producent podaje źródło biznesowe w treści, reguła może je odczytać np. z `payload.service`.

```sh
curl -i -X POST http://localhost:8081/ingest \
  -H 'Content-Type: application/json' \
  --data-binary '{"type":"payment_failed","details":{"code":"TIMEOUT"}}'
```

Producenci korzystający wcześniej z `/ingest/{source}` przechodzą na `/ingest`.

Sukces zwraca `202 Accepted` i `{"eventId":"..."}`. Niepoprawny JSON zwraca `400`, a body powyżej limitu `413`.

### Redis Streams

Producenci zapisują do streamu `ingest:external` pola `payload` (tekst zawierający dowolny JSON) i opcjonalnie `source`. Adapter konsumuje grupą `engine`, kopertuje payload i przekazuje go do `events:ingest`.

```sh
redis-cli XADD ingest:external '*' source payments payload '{"type":"payment_failed"}'
```

### Kafka

Adapter jest wyłączony domyślnie. Po włączeniu konsumuje wartości tekstowe z topicu `ingest.external`; każda wartość musi zawierać poprawny JSON. Zmieniaj ustawienia przez `INGEST_KAFKA_ENABLED`, `INGEST_KAFKA_TOPIC` i `KAFKA_BOOTSTRAP_SERVERS`.

Przykład wysłania wiadomości z poziomu kontenera Kafka:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic ingest.external
```

Wpisz jedną linię JSON i zatwierdź Enterem. Producent lokalny może łączyć się przez `localhost:9092`.

## Koperta w `events:ingest`

Każdy wpis zawiera pole `event` z JSON-em w formacie:

```json
{
  "eventId": "uuid",
  "source": null,
  "adapter": "http",
  "receivedAt": 1795212345678,
  "contentType": "json",
  "payload": { "type": "payment_failed" }
}
```

Wartość `payload` zachowuje typ i strukturę dostarczonego JSON-a. Adapter HTTP publikuje bezpośrednio do `events:ingest`; Redis i Kafka przyjmują dane ze swoich wejść, a następnie przekazują je do tego samego streamu. Poświadczenia nie są wymagane w lokalnym profilu.

## Zakres szkieletu

To jest tylko warstwa ingestu. Nie ma jeszcze konsumenta `events:ingest`, odzyskiwania niepotwierdzonych wpisów Redis, DLQ, idempotencji, autoryzacji ani obsługi treści innych niż JSON.

### Tworzenie reguły w control

`POST /api/rules` na porcie 8082 przyjmuje dokument edytora jako UTF-8
(`application/yaml`, maks. 1 MiB). Routing wymaga przynajmniej jednego odbiorcy: zespołu (`route.teams`), użytkownika po loginie (`route.users`) lub obu, np. `route: {teams: [payments], users: [anna]}`. Nazwy odbiorców i nazwa przypiętego
runbooka muszą już istnieć w bazie. Na pustej lokalnej bazie demo przygotuj
zespół poleceniem:

```sh
docker compose exec -T postgres psql -U alerting -d alerting -c "INSERT INTO teams(name) VALUES ('payments') ON CONFLICT DO NOTHING"
curl -i http://localhost:8082/api/rules -H 'Content-Type: application/yaml' --data-binary @- <<'YAML'
name: Timeout płatności
match:
  all:
    - {path: payload.service, op: eq, value: payments}
    - {path: payload.error.code, op: eq, value: TIMEOUT}
title: "Timeout w {{payload.service}}"
route: {teams: [payments]}
runbook: auto
YAML
```

Odpowiedź `201` zawiera `id`, `version` i `configVersion`. Niepoprawny
dokument lub nieznana nazwa zespołu/użytkownika/runbooka daje `400`, powtórzona nazwa
reguły `409`. Pełny YAML (wraz z komentarzami) zostaje w `rules.source_yaml`,
a znormalizowana definicja v1 w `rules.definition`.

Aby wysyłać zmiany, uruchom
`RULES_KAFKA_ENABLED=true docker compose --profile kafka up --build -d postgres kafka control`.
Po zatwierdzeniu transakcji control publikuje zdarzenie `rule.created` na
`rules.created.v1` (zmienna `RULES_KAFKA_TOPIC`), z kluczem Kafka równym ID
reguły. Treść zawiera ID i wersje, nazwę, priorytet, enabled, teamIds, userIds, runbookId
oraz definition. Przy wyłączonym publisherze lub niedostępnym brokerze wpis
pozostaje w `rule_created_outbox`. Publikacja jest co najmniej jednokrotna;
konsument powinien rozpoznawać powtórzenia po `(ruleId, version)`.

### Odbiór gotowych alertów z engine

`ALERTS_KAFKA_ENABLED=true docker compose --profile kafka up --build -d postgres kafka control`
włącza konsumenta topicu `alerts.live` w grupie `control-alerts-live-v1`.
Flaga działa niezależnie od publikacji reguł. Kontrakt v1 i przykładowy
rekord dla producenta: [alert-created.json](docs/backend/examples/alert-created.json).

Engine powinien ustawiać klucz Kafka na `alertId`, aby kolejne aktualizacje
alertu trafiały do tej samej partycji. `alert.created` i `alert.updated`
niosą pełny stan prezentacji, docelowe ID zespołów i użytkowników (łącznie
przynajmniej jeden odbiorca), ID ostatniego zdarzenia i reguły, dodatni
licznik wystąpień oraz czas utworzenia aktualizacji w UTC. Treść `body` jest
opcjonalna. Ważność: info, low, medium, high, critical.

Na tym etapie control odczytuje i waliduje rekord, loguje identyfikatory
oraz zwiększa licznik `alerts.live.records`. Nie zapisuje ani nie wysyła
alertu. Błędny rekord jest zliczany jako rejected i pomijany. Grupa zapisuje
offset również dla obsłużonych w ten sposób rekordów. Przyszły konsument
wymagający odtworzenia historii może użyć nowej grupy w okresie retencji.

Próba z przykładowym rekordem (wymaga `jq`). Klucz przed tabulatorem to
`alertId`; brak klucza lub inne ID powoduje odrzucenie rekordu.

```sh
jq -r '.alertId + "\t" + tojson' docs/backend/examples/alert-created.json |
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic alerts.live --property parse.key=true
docker compose logs control | grep 'Received alert.created'
```
