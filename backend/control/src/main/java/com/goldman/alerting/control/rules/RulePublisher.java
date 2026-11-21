package com.goldman.alerting.control.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.jooq.DSLContext;

import static com.goldman.alerting.control.db.Tables.RULE_CREATED_OUTBOX;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
@EnableScheduling
@ConditionalOnProperty(name="rules.redis.enabled", havingValue="true")
class RulePublisher {
    // A subscriber recovering after PUBLISH can always read the corresponding definition.
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current and cjson.decode(current).version > cjson.decode(ARGV[2]).version then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return redis.call('PUBLISH', ARGV[3], ARGV[2])
            """, Long.class);

    private final DSLContext db;
    private final StringRedisTemplate redis;
    private final String channel;

    RulePublisher(DSLContext db, StringRedisTemplate redis, @Value("${rules.redis.channel}") String channel) {
        this.db = db;
        this.redis = redis;
        this.channel = channel;
    }

    @Scheduled(fixedDelayString="${rules.redis.poll-ms:500}")
    @Transactional(rollbackFor=Exception.class)
    public void publish() throws Exception {
        var batch = db.selectFrom(RULE_CREATED_OUTBOX)
                .orderBy(RULE_CREATED_OUTBOX.CONFIG_VERSION)
                .limit(32).forUpdate().skipLocked().fetch();
        for (var pending : batch) {
            redis.execute(PUBLISH, List.of(channel + ":snapshot"), pending.getRuleId().toString(),
                    pending.getPayload().data(), channel);
            db.deleteFrom(RULE_CREATED_OUTBOX)
                    .where(RULE_CREATED_OUTBOX.CONFIG_VERSION.eq(pending.getConfigVersion())).execute();
        }
    }
}
