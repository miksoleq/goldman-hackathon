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
    private final Map<String, Integer> applied = new HashMap<>();

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
        try {
            var event = RuleCreated.fromTree(mapper.readTree(json));
            if (applied.getOrDefault(event.ruleId(), 0) >= event.version()) return;
            if (event.enabled()) {
                index.accept(RuleDefinition.fromEvent(event));
                log.debug("Indexed rule ruleId={} version={}", event.ruleId(), event.version());
            } else {
                index.remove(event.ruleId());
                log.debug("Disabled rule ruleId={} version={}", event.ruleId(), event.version());
            }
            applied.put(event.ruleId(), event.version());
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            log.warn("Skipping invalid rule creation on {}", channel);
        }
    }
}
