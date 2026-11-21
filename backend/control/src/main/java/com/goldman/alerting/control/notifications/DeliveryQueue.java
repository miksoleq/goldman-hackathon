package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.Tables.NOTIFICATION_DELIVERIES;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeliveryQueue {
    record Job(UUID id, UUID token, String transport, String recipient, String title, String message, JsonNode options, int attempt) {}
    enum Outcome { SENT, TRANSIENT_FAILURE, PERMANENT_FAILURE }
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10));
    private final DSLContext db;
    private final Duration lease;

    private final ObjectMapper json;
    private final ChannelTransports transports;

    DeliveryQueue(DSLContext db, ObjectMapper json, ChannelTransports transports, @Value("${notifications.lease:2m}") Duration lease) {
        this.db = db;
        this.json = json;
        this.transports = transports;
        this.lease = lease;
    }

    @Transactional
    public List<Job> claim(int count) {
        var d = NOTIFICATION_DELIVERIES;
        var now = OffsetDateTime.now();
        var due = db.selectFrom(d).where(d.STATUS.in("pending", "retrying").and(d.AVAILABLE_AT.le(now))
                        .and(d.TRANSPORT.in(transports.types())))
                .orderBy(d.AVAILABLE_AT, d.ID).limit(count).forUpdate().skipLocked().fetch();
        var jobs = new ArrayList<Job>();
        for (var row : due) {
            if (row.getAttempts() >= BACKOFF.size() + 1) {
                row.setStatus("failed");
                row.setLeaseToken(null);
            } else {
                row.setLeaseToken(UUID.randomUUID());
                row.setAttempts(row.getAttempts() + 1);
                row.setAvailableAt(now.plus(lease));
                try {
                    jobs.add(new Job(row.getId(), row.getLeaseToken(), row.getTransport(), row.getRecipient(),
                            row.getTitle(), row.getMessage(), json.readTree(row.getTransportOptions().data()), row.getAttempts()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException("Invalid stored transport options", e);
                }
            }
            row.update();
        }
        return jobs;
    }

    public boolean finish(Job job, Outcome outcome) {
        var d = NOTIFICATION_DELIVERIES;
        boolean retry = outcome == Outcome.TRANSIENT_FAILURE && job.attempt() <= BACKOFF.size();
        String status = outcome == Outcome.SENT ? "sent" : retry ? "retrying" : "failed";
        var available = OffsetDateTime.now();
        if (retry) available = available.plus(BACKOFF.get(job.attempt() - 1));
        return db.update(d).set(d.STATUS, status).setNull(d.LEASE_TOKEN).set(d.AVAILABLE_AT, available)
                .where(d.ID.eq(job.id()).and(d.LEASE_TOKEN.eq(job.token()))).execute() == 1;
    }
}
