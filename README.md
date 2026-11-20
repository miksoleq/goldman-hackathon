# Engine ingest skeleton

Minimalny backend przyjmuje dowolny JSON z trzech wejść i zapisuje go jako kopertę zdarzenia w Redis Streams (`events:ingest`). Nie ma jeszcze walidacji biznesowej, workera ani reguł przetwarzania.

## Uruchomienie

Wymagany Docker z Docker Compose. Usługa HTTP wystawia port `8081`.

```sh
docker compose up --build
```

Opcjonalnie uruchom Kafka KRaft i listener Kafka:

```sh
INGEST_KAFKA_ENABLED=true docker compose --profile kafka up --build
```

Wartość `INGEST_KAFKA_ENABLED` włącza adapter Kafka. Domyślny topic to `ingest.external`; broker dostępny lokalnie pod `localhost:9092`.

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
