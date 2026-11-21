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

@SpringBootTest
class LiveAlertSubscriptionIT {
    static class Observer {
        final List<LiveAlert> alerts = new java.util.concurrent.CopyOnWriteArrayList<>();
        @EventListener public void received(LiveAlert alert) { alerts.add(alert); }
    }
    @TestConfiguration static class Config {
        @Bean Observer observer() { return new Observer(); }
    }

    @Autowired StringRedisTemplate redis;
    @Autowired AlertInbox inbox;
    @Autowired Observer observer;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext db;

    @Test void catchesUpAndAcknowledgesReplayAfterCommitExactlyOnce() throws Exception {
        String stream = "it:alerts:" + UUID.randomUUID();
        var ops = redis.<String, String>opsForStream();
        var alert = new LiveAlert(1, "alert.created", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
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
            assertEquals(List.of(alert), observer.alerts);
        } finally {
            redis.delete(stream);
            db.deleteFrom(LIVE_ALERT_INBOX).where(LIVE_ALERT_INBOX.ALERT_ID.eq(alert.alertId())).execute();
        }
    }
}
