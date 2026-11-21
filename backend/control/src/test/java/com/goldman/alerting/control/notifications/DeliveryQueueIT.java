package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.Tables.*;
import static com.goldman.alerting.control.notifications.DeliveryQueue.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import com.goldman.alerting.control.db.enums.Severity;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties="notifications.worker-enabled=false")
class DeliveryQueueIT {
    @Autowired DSLContext db;
    @Autowired DeliveryQueue queue;
    @Autowired NotificationRouting routing;
    @Autowired NotificationPreferences preferences;
    final UUID user = UUID.randomUUID();
    final UUID alert = UUID.randomUUID();

    @BeforeEach void seed() {
        db.insertInto(USERS, USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME).values(user, user.toString(), "Queue test").execute();
        db.insertInto(LIVE_ALERT_INBOX, LIVE_ALERT_INBOX.ALERT_ID, LIVE_ALERT_INBOX.PAYLOAD).values(alert, JSONB.valueOf("{}")).execute();
    }
    @AfterEach void cleanup() {
        db.deleteFrom(LIVE_ALERT_INBOX).where(LIVE_ALERT_INBOX.ALERT_ID.eq(alert)).execute();
        db.deleteFrom(USERS).where(USERS.ID.eq(user)).execute();
    }

    @Test void routesOnlySelectedChannelsAcceptedByRecipient() {
        preferences.replace(user, null, Map.of("sms", new DeliveryPolicy.Setting(true, Severity.info),
                "email", new DeliveryPolicy.Setting(false, Severity.info)));
        preferences.setContact(user, "sms", "+12025550123");
        routing.route(new com.goldman.alerting.contracts.LiveAlert(1, "alert.created", alert, "event", UUID.randomUUID(),
                List.of(), List.of(user), "high", "Title", "Body", 1, java.time.Instant.now(), null, null,
                null, List.of("sms", "email")));
        var d = NOTIFICATION_DELIVERIES;
        assertThat(db.select(d.CHANNEL_TYPE).from(d).where(d.ALERT_ID.eq(alert)).fetch(d.CHANNEL_TYPE))
                .containsExactly("sms");
    }

    @Test void concurrentWorkersClaimDistinctJobsAndExpiredTokenCannotComplete() throws Exception {
        enqueue("email");
        enqueue("sms");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CyclicBarrier(2);
            Callable<DeliveryQueue.Job> claim = () -> { gate.await(5, TimeUnit.SECONDS); return queue.claim(1).getFirst(); };
            var results = workers.invokeAll(List.of(claim, claim));
            var first = results.getFirst().get();
            var second = results.getLast().get();
            assertThat(first.id()).isNotEqualTo(second.id());
            assertThat(queue.claim(1)).isEmpty();
            expire(first.id());
            var recovered = queue.claim(1).getFirst();
            assertThat(recovered.id()).isEqualTo(first.id());
            assertThat(recovered.token()).isNotEqualTo(first.token());
            assertThat(recovered.attempt()).isEqualTo(2);
            assertThat(queue.finish(first, PERMANENT_FAILURE)).isFalse();
            assertThat(queue.finish(recovered, SENT)).isTrue();
            assertThat(queue.finish(second, PERMANENT_FAILURE)).isTrue();
        }
    }

    @Test void transientFailuresFollowScheduleAndStopAfterFiveAttempts() {
        var id = enqueue("sms");
        for (int i = 1; i <= 5; i++) {
            var job = queue.claim(1).getFirst();
            var start = OffsetDateTime.now();
            assertThat(job.attempt()).isEqualTo(i);
            assertThat(queue.finish(job, TRANSIENT_FAILURE)).isTrue();
            var row = db.fetchOne(NOTIFICATION_DELIVERIES, NOTIFICATION_DELIVERIES.ID.eq(id));
            if (i < 5) {
                assertThat(row.getStatus()).isEqualTo("retrying");
                assertThat(row.getAvailableAt()).isAfterOrEqualTo(start.plus(DeliveryQueue.BACKOFF.get(i-1)).minusSeconds(1));
                assertThat(queue.claim(1)).isEmpty();
                expire(id);
            } else assertThat(row.getStatus()).isEqualTo("failed");
        }
    }

    private UUID enqueue(String channel) {
        var d = NOTIFICATION_DELIVERIES;
        return db.insertInto(d, d.ALERT_ID, d.EVENT_ID, d.USER_ID, d.CHANNEL_TYPE, d.TRANSPORT, d.STATUS, d.TITLE, d.SEVERITY, d.MESSAGE)
                .values(alert, UUID.randomUUID().toString(), user, channel, channel.equals("sms") ? "http" : "smtp", "pending", "Title", Severity.high, "Snapshot")
                .returningResult(d.ID).fetchSingle(d.ID);
    }
    private void expire(UUID id) {
        var d = NOTIFICATION_DELIVERIES;
        db.update(d).set(d.AVAILABLE_AT, OffsetDateTime.now().minusSeconds(1)).where(d.ID.eq(id)).execute();
    }
}
