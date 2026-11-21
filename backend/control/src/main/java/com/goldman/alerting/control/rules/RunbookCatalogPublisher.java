package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.Runbook;
import com.goldman.alerting.contracts.RunbookCandidate;
import java.util.ArrayList;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static com.goldman.alerting.control.db.Tables.CONFIG_VERSION;
import static com.goldman.alerting.control.db.Tables.RUNBOOKS;

@Component
@ConditionalOnProperty(name = "rules.redis.enabled", havingValue = "true")
class RunbookCatalogPublisher {
    private final DSLContext db;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final String key;

    RunbookCatalogPublisher(DSLContext db, ObjectMapper mapper, StringRedisTemplate redis,
                            @Value("${rules.redis.channel}") String channel) {
        this.db = db;
        this.mapper = mapper;
        this.redis = redis;
        this.key = RunbookCandidate.snapshotKey(channel);
    }

    @Transactional
    @Scheduled(fixedDelayString = "${runbooks.catalog.poll-ms:1000}")
    public void refresh() throws JsonProcessingException {
        // Serialize publishers so a slower Control replica cannot overwrite a newer snapshot.
        if (db.select(CONFIG_VERSION.ID).from(CONFIG_VERSION).where(CONFIG_VERSION.ID.eq((short) 1))
                .forUpdate().skipLocked().fetchOne() == null) return;

        var candidates = new ArrayList<RunbookCandidate>();
        var rows = db.select(RUNBOOKS.ID, RUNBOOKS.NAME, RUNBOOKS.FORMAT, RUNBOOKS.CONTENT, RUNBOOKS.MATCHES)
                .from(RUNBOOKS).where(RUNBOOKS.ENABLED.isTrue().and(RUNBOOKS.MATCHES.isNotNull()))
                .orderBy(RUNBOOKS.ID).fetch();
        for (var row : rows) {
            var book = new Runbook(row.value1(), row.value2(), row.value3(), row.value4());
            candidates.add(new RunbookCandidate(book, mapper.readTree(row.value5().data())));
        }
        String snapshot = mapper.writeValueAsString(candidates);
        if (!snapshot.equals(redis.opsForValue().get(key))) redis.opsForValue().set(key, snapshot);
    }
}
