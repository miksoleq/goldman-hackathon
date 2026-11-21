package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.goldman.alerting.contracts.RuleCreated;
import com.goldman.alerting.engine.rules.RuleDefinition;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

class RedisRuleCreatedAdapter implements MessageListener, SubscriptionListener {
    private static final Logger log = LoggerFactory.getLogger(RedisRuleCreatedAdapter.class);
    private final ObjectMapper mapper;
    private final RuleIndexManager index;
    private final StringRedisTemplate redis;
    private final String channel;
    private final AtomicBoolean restoreNeeded = new AtomicBoolean(true);
    record Status(String ruleId, int version, String state, String reason) {}
    private final Map<String, Status> statuses = new HashMap<>();

    synchronized java.util.List<Status> statuses() {
        return statuses.values().stream().sorted(java.util.Comparator.comparing(Status::ruleId)).toList();
    }

    RedisRuleCreatedAdapter(ObjectMapper mapper, RuleIndexManager index, StringRedisTemplate redis, String channel) {
        this.mapper = mapper;
        this.index = index;
        this.redis = redis;
        this.channel = channel;
    }

    @Override
    public void onChannelSubscribed(byte[] subscribed, long count) {
        restoreNeeded.set(true);
    }

    @Scheduled(fixedDelayString = "${rules.redis.recovery-poll-ms:1000}")
    public void recover() {
        if (!restoreNeeded.getAndSet(false)) return;
        try {
            for (String json : redis.<String, String>opsForHash().values(channel + ":snapshot")) accept(json);
        } catch (RuntimeException ex) {
            restoreNeeded.set(true);
            throw ex;
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        accept(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    synchronized void accept(String json) {
        String id = null;
        int version = 0;
        try {
            var node = mapper.readTree(json);
            if (node == null) throw new IllegalArgumentException("Missing rule envelope");
            id = node.path("ruleId").asText();
            java.util.UUID.fromString(id);
            var versionNode = node.path("version");
            if (!versionNode.isMissingNode() && (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt())) {
                throw new IllegalArgumentException("Invalid rule version");
            }
            version = versionNode.asInt(1);
            if (version < 1) throw new IllegalArgumentException("Invalid rule version");
            var previous = statuses.get(id);
            if (previous != null && previous.version() >= version) return;
            var event = RuleCreated.fromTree(node);
            if (event.enabled()) {
                index.accept(RuleDefinition.fromEvent(event));
                log.debug("Indexed rule ruleId={} version={}", id, version);
            } else {
                index.remove(id);
            }
            statuses.put(id, new Status(id, version, event.enabled() ? "active" : "disabled", null));
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            io.micrometer.core.instrument.Metrics.counter("rules.compile.errors").increment();
            if (id != null && version > 0) {
                index.remove(id);
                statuses.put(id, new Status(id, version, "skipped", ex.getMessage()));
            }
            log.warn("Skipping invalid rule on {} ruleId={} version={}: {}", channel, id, version, ex.getMessage());
        }
    }
}
