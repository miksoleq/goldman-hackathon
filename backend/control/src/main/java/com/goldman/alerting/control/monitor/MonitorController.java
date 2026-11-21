package com.goldman.alerting.control.monitor;

import static com.goldman.alerting.control.db.Tables.LIVE_ALERT_INBOX;
import static com.goldman.alerting.control.db.tables.Runbooks.RUNBOOKS;
import static com.goldman.alerting.control.db.tables.Rules.RULES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitor")
public final class MonitorController {

    private static final int MAX_ITEMS = 100;

    private final DSLContext database;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String eventStream;

    public MonitorController(
            DSLContext database,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            @Value("${ingest.redis.internal-stream:events:ingest}") String eventStream
    ) {
        this.database = database;
        this.redis = redis;
        this.mapper = mapper;
        this.eventStream = eventStream;
    }

    @GetMapping(path = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MonitorMessage> messages() {
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().reverseRange(eventStream, Range.unbounded(), Limit.limit().count(MAX_ITEMS));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(this::toMonitorMessage)
                .filter(message -> message != null)
                .toList();
    }

    @GetMapping(path = "/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RuleView> rules() {
        return database.selectFrom(RULES)
                .orderBy(RULES.UPDATED_AT.desc(), RULES.NAME.asc())
                .limit(MAX_ITEMS)
                .fetch(record -> new RuleView(
                        record.getId(),
                        record.getName(),
                        record.getEnabled(),
                        record.getPriority(),
                        record.getVersion(),
                        record.getSourceYaml(),
                        json(record.getDefinition()),
                        record.getRunbookId(),
                        record.getUpdatedAt()
                ));
    }

    @GetMapping(path = "/runbooks", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RunbookView> runbooks() {
        return database.selectFrom(RUNBOOKS)
                .orderBy(RUNBOOKS.UPDATED_AT.desc(), RUNBOOKS.NAME.asc())
                .limit(MAX_ITEMS)
                .fetch(record -> new RunbookView(
                        record.getId(),
                        record.getName(),
                        record.getFormat(),
                        record.getContent(),
                        json(record.getMatches()),
                        record.getEnabled(),
                        record.getUpdatedAt()
                ));
    }

    @GetMapping(path = "/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<JsonNode> alerts() {
        return database.select(LIVE_ALERT_INBOX.PAYLOAD)
                .from(LIVE_ALERT_INBOX)
                .orderBy(LIVE_ALERT_INBOX.RECEIVED_AT.desc())
                .limit(MAX_ITEMS)
                .fetch(record -> {
                    try {
                        return mapper.readTree(record.get(LIVE_ALERT_INBOX.PAYLOAD).data());
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Stored monitor alert is invalid JSON", exception);
                    }
                });
    }

    private MonitorMessage toMonitorMessage(MapRecord<String, Object, Object> record) {
        Object serializedEvent = record.getValue().get("event");
        if (serializedEvent == null) {
            return null;
        }
        try {
            JsonNode envelope = mapper.readTree(serializedEvent.toString());
            return envelope == null ? null : new MonitorMessage(record.getId().getValue(), envelope);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private JsonNode json(JSONB value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value.data());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored monitor configuration is invalid JSON", exception);
        }
    }

    public record MonitorMessage(String streamId, JsonNode envelope) { }

    public record RuleView(
            UUID id,
            String name,
            boolean enabled,
            int priority,
            int version,
            String sourceYaml,
            JsonNode definition,
            UUID runbookId,
            OffsetDateTime updatedAt
    ) { }

    public record RunbookView(
            UUID id,
            String name,
            String format,
            String content,
            JsonNode matches,
            boolean enabled,
            OffsetDateTime updatedAt
    ) { }
}
