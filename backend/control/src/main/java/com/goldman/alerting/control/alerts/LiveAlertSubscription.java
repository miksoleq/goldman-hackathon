package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.LiveAlert;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
class LiveAlertSubscription {
    static final String GROUP = "control";
    private static final int BATCH = 64;
    private static final Logger log = LoggerFactory.getLogger(LiveAlertSubscription.class);
    private final Consumer consumer = Consumer.from(GROUP, UUID.randomUUID().toString());
    private final StreamOperations<String, String, String> streams;
    private final String stream;
    private final Duration claimIdle;
    private final ObjectMapper mapper;
    private final AlertInbox inbox;
    private boolean groupReady;
    private Range<String> pendingRange = Range.unbounded();

    LiveAlertSubscription(StringRedisTemplate redis, ObjectMapper mapper, AlertInbox inbox,
                          @Value("${alerts.stream:" + LiveAlert.STREAM + "}") String stream,
                          @Value("${alerts.claim-idle:30s}") Duration claimIdle) {
        this.streams = redis.opsForStream();
        this.stream = stream;
        this.claimIdle = claimIdle;
        this.mapper = mapper;
        this.inbox = inbox;
    }

    @Scheduled(fixedDelayString = "${alerts.poll-ms:250}")
    public void poll() {
        try {
            if (!groupReady) {
                try {
                    streams.createGroup(stream, ReadOffset.from("0-0"), GROUP);
                } catch (RuntimeException e) {
                    if (!org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e)
                            .getMessage().startsWith("BUSYGROUP")) throw e;
                }
                groupReady = true;
            }
            recoverPending();
            var fresh = streams.read(consumer, StreamReadOptions.empty().count(BATCH),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()));
            if (fresh != null) fresh.forEach(this::receive);
        } catch (RuntimeException e) {
            groupReady = false;
            throw e;
        }
    }

    private void recoverPending() {
        var pending = streams.pending(stream, GROUP, pendingRange, BATCH);
        var expired = new java.util.ArrayList<RecordId>();
        for (var entry : pending) {
            if (entry.getElapsedTimeSinceLastDelivery().compareTo(claimIdle) >= 0) expired.add(entry.getId());
        }
        pendingRange = pending.size() == BATCH
                ? Range.rightUnbounded(Range.Bound.exclusive(pending.get(BATCH - 1).getId().getValue()))
                : Range.unbounded();
        if (!expired.isEmpty()) {
            streams.claim(stream, GROUP, consumer.getName(), claimIdle, expired.toArray(RecordId[]::new))
                    .forEach(this::receive);
        }
    }

    private void receive(MapRecord<String, String, String> record) {
        try {
            String payload = record.getValue().get("alert");
            var envelope = mapper.readTree(payload);
            if (!envelope.path("v").isIntegralNumber() || !envelope.path("v").canConvertToInt()
                    || envelope.path("v").intValue() != 1) {
                throw new IllegalArgumentException("Invalid alert envelope");
            }
            var id = UUID.fromString(envelope.path("alertId").asText());
            switch (envelope.path("type").asText()) {
                case "alert.created" -> {
                    LiveAlert alert = mapper.treeToValue(envelope, LiveAlert.class);
                    inbox.receive(alert, payload);
                    log.debug("Received alert ruleId={} eventId={} alertId={}", alert.ruleId(), alert.eventId(), id);
                }
                case "alert.counted" -> {
                    var count = envelope.path("count");
                    if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 2) {
                        throw new IllegalArgumentException("Invalid alert count");
                    }
                    inbox.recordCount(id, count.intValue());
                }
                default -> throw new IllegalArgumentException("Invalid alert type");
            }
            streams.acknowledge(stream, GROUP, record.getId());
        } catch (Exception e) {
            log.warn("Alert stream entry {} remains pending ({})", record.getId(), e.getClass().getSimpleName());
        }
    }
}
