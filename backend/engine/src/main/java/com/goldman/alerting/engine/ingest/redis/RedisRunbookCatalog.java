package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RunbookCandidate;
import com.goldman.alerting.engine.rules.RunbookCatalog;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rules.redis.enabled", havingValue = "true")
class RedisRunbookCatalog {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RunbookCatalog catalog;
    private final String key;
    private String loaded;

    RedisRunbookCatalog(StringRedisTemplate redis, ObjectMapper mapper, RunbookCatalog catalog,
                        @Value("${rules.redis.channel}") String channel) {
        this.redis = redis;
        this.mapper = mapper;
        this.catalog = catalog;
        key = RunbookCandidate.snapshotKey(channel);
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${runbooks.catalog.poll-ms:1000}")
    public void refresh() throws JsonProcessingException {
        String snapshot = redis.opsForValue().get(key);
        if (snapshot == null || snapshot.equals(loaded)) return;
        catalog.replace(mapper.readValue(snapshot, new TypeReference<List<RunbookCandidate>>() {}));
        loaded = snapshot;
    }
}
