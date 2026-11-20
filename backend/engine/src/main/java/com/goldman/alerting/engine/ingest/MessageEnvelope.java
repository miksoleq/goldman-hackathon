package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.databind.JsonNode;

public record MessageEnvelope(
        String eventId,
        String source,
        String adapter,
        long receivedAt,
        String contentType,
        JsonNode payload
) {
}
