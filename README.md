# Engine ingest skeleton

Minimalny backend przyjmuje dowolny JSON z trzech wejść i zapisuje go jako kopertę zdarzenia w Redis Streams (`events:ingest`). Engine dopasowuje przyjęte zdarzenia do reguł z Control.

## Uruchomienie

Wymagany Docker z Docker Compose. Skopiuj lokalną konfigurację środowiska (w PowerShell):

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Potem uruchom cały zestaw. Frontend jest dostępny na porcie `8080`, ingest HTTP na `8081`, `control` na `8082`, a PostgreSQL na `localhost:55432`. Porty hosta można zmieniać przez `FRONTEND_PORT` i `POSTGRES_PORT`.

```powershell
docker compose up --build
```

Dashboard monitoringu jest dostępny pod [http://localhost:8080](http://localhost:8080). Pokazuje ostatnie wiadomości z Redis Streams, reguły i runbooki z bazy Control oraz ostatnie alerty zapisane w inboxie Control.

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

Control przyjmuje reguły przez `POST /api/rules`, zapisuje je w PostgreSQL i odbiera dopasowane alerty. Engine buduje indeks w pamięci i przyjmuje zdarzenia. Obie aplikacje pakują bibliotekę Maven `backend/contracts` ze schematem definicji reguły i formatami komunikatów.

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
  "payload": { "type": "payment_failed" },
  "matchedRuleIds": []
}
```

Wartość `payload` zachowuje typ i strukturę dostarczonego JSON-a. Adapter HTTP publikuje bezpośrednio do `events:ingest`; Redis i Kafka przyjmują dane ze swoich wejść, a następnie przekazują je do tego samego streamu. Poświadczenia nie są wymagane w lokalnym profilu.

## Zakres szkieletu

Nie ma jeszcze konsumenta `events:ingest`, odzyskiwania niepotwierdzonych wpisów Redis, DLQ, idempotencji, autoryzacji ani obsługi treści innych niż JSON.

Warunek `jsonSchema` porównuje strukturę danych pod wskazaną ścieżką. Identyfikatory dopasowanych reguł trafiają do `matchedRuleIds`. [Przykładowa reguła i obsługiwane elementy schematu](docs/backend/business-decisions/2026-11-21-json-schema-matching.md).

### Tworzenie reguły w control

`POST /api/rules` na porcie 8082 przyjmuje dokument edytora jako UTF-8
(`application/yaml`, maks. 1 MiB). Nazwy w `route.teams`, loginy w `route.users` i nazwa przypiętego
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
dokument lub nieznana nazwa użytkownika, zespołu albo runbooka daje `400`, powtórzona nazwa
reguły `409`. Pełny YAML (wraz z komentarzami) zostaje w `rules.source_yaml`,
a znormalizowana definicja v1 w `rules.definition`.

Uruchom oba serwisy poleceniem `docker compose up --build -d postgres redis control engine`.
Compose domyślnie włącza dystrybucję reguł. Poza Compose ustaw w obu serwisach
`RULES_REDIS_ENABLED=true` i wskaż ten sam Redis.

Control zapisuje regułę i wpis w `rule_created_outbox` w jednej transakcji.
Publisher zapisuje pełny komunikat pod ID reguły w hashu `rules.created.v1:snapshot`
i publikuje go na kanale Redis Pub/Sub `rules.created.v1` w jednej operacji.
Gdy Redis jest niedostępny, Control ponawia wysyłkę z outboxa. Nazwę kanału
można zmienić przez `RULES_REDIS_CHANNEL`, a nazwa hasha otrzymuje sufiks `:snapshot`.

Każda replika Engine subskrybuje kanał i dodaje nową regułę do swojego indeksu
w pamięci. Po starcie lub ponownym połączeniu uzupełnia reguły z hasha,
pomijając już zastosowane wersje. Compose utrwala dane Redisa w wolumenie
`redis_data`. Przy przejściu z Kafki migracja V4 kolejkuje istniejące reguły
do wysłania przez Redis.

### Alert po dopasowaniu

Aby kierować alert tylko do wybranych osób, ustaw np. `route: {users: [alice]}`. Można też podać zespoły i osoby w jednej regule. Po dopasowaniu Engine dopisuje komunikat `alert.created` do strumienia Redis `alerts.live` w polu `alert`. Control zapisuje go w PostgreSQL w tabeli `live_alert_inbox` i potwierdza odbiór po zatwierdzeniu transakcji. Po restarcie odczytuje zaległe wpisy. Ostatni komunikat można podejrzeć poleceniem `docker compose exec redis redis-cli XREVRANGE alerts.live + - COUNT 1`. Przykład i zasady dostarczania opisuje [kontrakt alertów](docs/backend/business-decisions/2026-11-21-live-alerts.md).

### Ręczny test od utworzenia reguły do odbioru alertu

Z katalogu repozytorium uruchom `./scripts/test-alert-flow.sh`. Wymagane są Docker Compose, Bash, curl, jq i uuidgen oraz dostępne dla tego projektu porty z Compose. Skrypt korzysta z lokalnego `.env` lub ustawień demo z `.env.example`. Dystrybucja reguł musi być włączona.

Skrypt buduje i uruchamia Control, Engine, PostgreSQL i Redis, tworzy zespół testowy i regułę przez HTTP. Czeka na zaindeksowanie reguły, po czym wysyła jedno pasujące zdarzenie. Sukces potwierdza wpis z procesu Control zawierający ten sam `ruleId`, ten sam `eventId` oraz nadany `alertId`. Skrypt kończy się błędem, jeśli potwierdzenie nie dotrze.

Każdy przebieg tworzy regułę z unikalnym identyfikatorem testu i zostawia dane oraz usługi do inspekcji. Używaj lokalnej bazy demo. Dodatkowe logowanie diagnostyczne włącza plik Compose używany przez skrypt.
