package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.goldman.alerting.engine.rules.Rule;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final ObjectMapper objectMapper;
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
        if (rawJson.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new PayloadTooLargeException(maxPayloadBytes);
        }
        try {
            JsonNode payload = objectMapper.readTree(rawJson);
            if (payload == null) {
                throw new InvalidJsonException();
            }
            return accept(source, adapter, payload);
        } catch (JsonProcessingException exception) {
            throw new InvalidJsonException();
        }
    }

    public MessageEnvelope accept(String source, String adapter, JsonNode payload) {
        String eventId = UUID.randomUUID().toString();
        long receivedAt = System.currentTimeMillis();
        var event = objectMapper.createObjectNode();
        event.set("payload", payload);
        event.putObject("meta").put("source", source).put("adapter", adapter)
                .put("eventId", eventId).put("receivedAt", receivedAt).put("contentType", "json");
        var matches = rules.match(event).stream().map(Rule::id).toList();
        MessageEnvelope envelope = new MessageEnvelope(
                eventId,
                source,
                adapter,
                receivedAt,
                "json",
                payload,
                matches
        );
        publisher.publish(envelope);
        return envelope;
    }
}
