package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.Ulid;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EventIdTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void producesDistinctUlidsWithTheEnvelopeTimestamp() {
        var service = new IngestionService(mapper, (event, alerts) -> {}, mock(RuleIndexManager.class), 32);
        var first = service.accept("test", "http", "true");
        var second = service.accept("test", "http", "true");
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(first.receivedAt(), Ulid.from(first.eventId()).getTime());
        assertTrue(first.eventId().matches("[0-7][0-9A-HJKMNP-TV-Z]{25}"));
    }

    @Test void envelopeRoundTripsBothEventIdFormats() throws Exception {
        for (String id : List.of("01ARZ3NDEKTSV4RRFFQ69G5FAV", "966f62b8-c687-4463-a682-7db02965098c")) {
            var envelope = new MessageEnvelope(id, "test", "http", 42, "json", mapper.readTree("{}"), List.of());
            assertEquals(envelope, mapper.readValue(mapper.writeValueAsBytes(envelope), MessageEnvelope.class));
        }
    }
}
