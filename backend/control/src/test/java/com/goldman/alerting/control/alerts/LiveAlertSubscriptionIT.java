package com.goldman.alerting.control.alerts;

import static com.goldman.alerting.control.db.Tables.LIVE_ALERT_INBOX;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.LiveAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveAlertSubscriptionIT {
    static class Observer {
        final List<LiveAlert> alerts = new java.util.concurrent.CopyOnWriteArrayList<>();
        @EventListener public void received(LiveAlert alert) { alerts.add(alert); }
        List<LiveAlert> forAlert(UUID id) {
            return alerts.stream().filter(alert -> id.equals(alert.alertId())).toList();
        }
    }
    @TestConfiguration static class Config {
        @Bean Observer observer() { return new Observer(); }
    }

    @Autowired StringRedisTemplate redis;
    @Autowired AlertInbox inbox;
    @Autowired Observer observer;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext db;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"01ARZ3NDEKTSV4RRFFQ69G5FAV", "966f62b8-c687-4463-a682-7db02965098c"})
    void catchesUpAndAcknowledgesReplayAfterCommitExactlyOnce(String eventId) throws Exception {
        String stream = "it:alerts:" + UUID.randomUUID();
        var ops = redis.<String, String>opsForStream();
        var alert = new LiveAlert(1, "alert.created", UUID.randomUUID(), eventId, UUID.randomUUID(),
                List.of(), List.of(UUID.randomUUID()), "high", "Timeout", "WF-42", 1,
                Instant.parse("2026-11-21T12:00:00Z"), LiveAlert.UNASSIGNED_RUNBOOK, null);
        var payload = Map.of("alert", mapper.writeValueAsString(alert));
        try {
            ops.add(stream, payload);
            var consumer = new LiveAlertSubscription(redis, mapper, inbox, stream, Duration.ZERO);
            consumer.poll();
            var persisted = db.select(LIVE_ALERT_INBOX.PAYLOAD).from(LIVE_ALERT_INBOX)
                    .where(LIVE_ALERT_INBOX.ALERT_ID.eq(alert.alertId())).fetchSingle(LIVE_ALERT_INBOX.PAYLOAD);
            assertEquals(alert, mapper.readValue(persisted.data(), LiveAlert.class));

            // Another reader dies with an already persisted alert still pending.
            ops.add(stream, payload);
            String group = LiveAlertSubscription.GROUP;
            ops.read(Consumer.from(group, "offline"), StreamOffset.create(stream, ReadOffset.lastConsumed()));
            assertEquals(1, ops.pending(stream, group).getTotalPendingMessages());
            new LiveAlertSubscription(redis, mapper, inbox, stream, Duration.ZERO).poll();
            assertEquals(0, ops.pending(stream, group).getTotalPendingMessages());
            assertEquals(List.of(alert), observer.forAlert(alert.alertId()));

            var count = mapper.createObjectNode().put("v", 1).put("type", "alert.counted")
                    .put("alertId", alert.alertId().toString());
            for (int n : List.of(3, 2, 3)) ops.add(stream, Map.of("alert", count.put("count", n).toString()));
            ops.add(stream, payload);
            consumer.poll();
            var stored = db.select(LIVE_ALERT_INBOX.PAYLOAD).from(LIVE_ALERT_INBOX)
                    .where(LIVE_ALERT_INBOX.ALERT_ID.eq(alert.alertId())).fetchSingle(LIVE_ALERT_INBOX.PAYLOAD);
            com.fasterxml.jackson.databind.node.ObjectNode expected = mapper.valueToTree(alert);
            expected.put("count", 3);
            assertEquals(expected, mapper.readTree(stored.data()));
            var detail = http.getForObject("/api/alerts/" + alert.alertId(), com.fasterxml.jackson.databind.JsonNode.class);
            assertEquals(3, detail.required("count").asInt());
            assertEquals(eventId, detail.required("eventId").asText());
            assertEquals(0, ops.pending(stream, group).getTotalPendingMessages());
            assertEquals(List.of(alert), observer.forAlert(alert.alertId()));
        } finally {
            redis.delete(stream);
            db.deleteFrom(LIVE_ALERT_INBOX).where(LIVE_ALERT_INBOX.ALERT_ID.eq(alert.alertId())).execute();
        }
    }

    @Test void retriesCountDeliveredBeforeCreation() throws Exception {
        String stream = "it:alerts:" + UUID.randomUUID();
        var ops = redis.<String,String>opsForStream();
        var id = UUID.randomUUID();
        var alert = new LiveAlert(1, "alert.created", id, UUID.randomUUID().toString(), UUID.randomUUID(),
                List.of(), List.of(), "high", "First match", "", 1, Instant.now(),
                LiveAlert.UNASSIGNED_RUNBOOK, null);
        var consumer = new LiveAlertSubscription(redis, mapper, inbox, stream, Duration.ZERO);
        try {
            var update = mapper.createObjectNode().put("v", 1).put("type", "alert.counted")
                    .put("alertId", id.toString()).put("count", 2);
            ops.add(stream, Map.of("alert", update.toString()));
            consumer.poll();
            assertEquals(1, ops.pending(stream, LiveAlertSubscription.GROUP).getTotalPendingMessages());
            ops.add(stream, Map.of("alert", mapper.writeValueAsString(alert)));
            consumer.poll();
            consumer.poll();
            assertEquals(0, ops.pending(stream, LiveAlertSubscription.GROUP).getTotalPendingMessages());
            var stored = db.select(LIVE_ALERT_INBOX.PAYLOAD).from(LIVE_ALERT_INBOX)
                    .where(LIVE_ALERT_INBOX.ALERT_ID.eq(id)).fetchSingle(LIVE_ALERT_INBOX.PAYLOAD);
            assertEquals(2, mapper.readTree(stored.data()).required("count").asInt());
            assertEquals(List.of(alert), observer.forAlert(id));
        } finally {
            redis.delete(stream);
            db.deleteFrom(LIVE_ALERT_INBOX).where(LIVE_ALERT_INBOX.ALERT_ID.eq(id)).execute();
        }
    }
}
