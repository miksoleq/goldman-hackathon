package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Instant;
import java.util.ArrayList;
import com.goldman.alerting.contracts.LiveAlert;
import com.goldman.alerting.engine.rules.Rule;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final ObjectMapper objectMapper;
    private final ObjectReader jsonReader;
    private final IngestPublisher publisher;
    private final int maxPayloadBytes;
    private final RuleIndexManager rules;

    public IngestionService(
            ObjectMapper objectMapper,
            IngestPublisher publisher,
            RuleIndexManager rules,
            @Value("${ingest.max-payload-bytes:1048576}") int maxPayloadBytes
    ) {
        this.objectMapper = objectMapper;
        this.jsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.publisher = publisher;
        this.rules = rules;
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("ingest.max-payload-bytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public MessageEnvelope accept(String source, String adapter, String rawJson) {
        if (rawJson == null) {
            throw new InvalidJsonException();
        }
        checkSize(rawJson);
        try {
            JsonNode payload = jsonReader.readTree(rawJson);
            if (payload == null || payload.isMissingNode()) {
                throw new InvalidJsonException();
            }
            return accept(source, adapter, payload);
        } catch (JsonProcessingException exception) {
            throw new InvalidJsonException();
        }
    }

    public MessageEnvelope accept(String source, String adapter, JsonNode payload) {
        return publish(source, adapter, "json", payload);
    }

    public MessageEnvelope acceptText(String source, String adapter, String message) {
        checkSize(message);
        return publish(source, adapter, "text", objectMapper.createObjectNode().put("message", message));
    }

    private void checkSize(String input) {
        if (input.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new PayloadTooLargeException(maxPayloadBytes);
        }
    }

    private MessageEnvelope publish(String source, String adapter, String contentType, JsonNode payload) {
        String eventId = UUID.randomUUID().toString();
        long receivedAt = System.currentTimeMillis();
        var event = objectMapper.createObjectNode();
        event.set("payload", payload);
        event.putObject("meta").put("source", source).put("adapter", adapter)
                .put("eventId", eventId).put("receivedAt", receivedAt).put("contentType", contentType);
        var matches = rules.match(event);
        var alerts = new ArrayList<IngestPublisher.MatchedAlert>();
        for (Rule rule : matches) {
            var spec = rule.alert();
            if (spec == null) continue;
            var book = spec.runbook().apply(event);
            var alert = new LiveAlert(1, "alert.created", UUID.randomUUID(), UUID.fromString(eventId),
                    UUID.fromString(rule.id()), spec.teams(), spec.users(), spec.severity().apply(event),
                    spec.title().apply(event), spec.body().apply(event), 1, Instant.ofEpochMilli(receivedAt),
                    book == null ? LiveAlert.UNASSIGNED_RUNBOOK : book.id(), book);
            var dedup = rule.definition().dedup();
            alerts.add(new IngestPublisher.MatchedAlert(alert, dedup.fingerprint(rule.id(), event), dedup.windowSeconds()));
        }
        MessageEnvelope envelope = new MessageEnvelope(
                eventId,
                source,
                adapter,
                receivedAt,
                contentType,
                payload,
                matches.stream().map(Rule::id).toList()
        );
        publisher.publish(envelope, alerts);
        return envelope;
    }
}
