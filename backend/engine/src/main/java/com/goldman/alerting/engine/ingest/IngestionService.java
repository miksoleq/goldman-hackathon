package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final ObjectMapper objectMapper;
    private final IngestPublisher publisher;
    private final int maxPayloadBytes;

    public IngestionService(
            ObjectMapper objectMapper,
            IngestPublisher publisher,
            @Value("${ingest.max-payload-bytes:1048576}") int maxPayloadBytes
    ) {
        this.objectMapper = objectMapper;
        this.publisher = publisher;
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
        MessageEnvelope envelope = new MessageEnvelope(
                UUID.randomUUID().toString(),
                source,
                adapter,
                System.currentTimeMillis(),
                "json",
                payload
        );
        publisher.publish(envelope);
        return envelope;
    }
}
