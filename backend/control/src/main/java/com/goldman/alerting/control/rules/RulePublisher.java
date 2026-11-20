package com.goldman.alerting.control.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.jooq.DSLContext;

import static com.goldman.alerting.control.db.Tables.RULE_CREATED_OUTBOX;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Component
@EnableScheduling
@ConditionalOnProperty(name="rules.kafka.enabled", havingValue="true")
class RulePublisher {
    private final DSLContext db;
    private final KafkaTemplate<String,String> kafka;
    private final String topic;

    RulePublisher(DSLContext db, KafkaTemplate<String,String> kafka, @Value("${rules.kafka.topic}") String topic) {
        this.db = db;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString="${rules.kafka.poll-ms:500}")
    @Transactional(rollbackFor=Exception.class)
    public void publish() throws Exception {
        var batch = db.selectFrom(RULE_CREATED_OUTBOX)
                .orderBy(RULE_CREATED_OUTBOX.CONFIG_VERSION)
                .limit(32).forUpdate().skipLocked().fetch();
        for (var pending : batch) {
            kafka.send(topic, pending.getRuleId().toString(), pending.getPayload().data())
                    .get(10, TimeUnit.SECONDS);
            db.deleteFrom(RULE_CREATED_OUTBOX)
                    .where(RULE_CREATED_OUTBOX.CONFIG_VERSION.eq(pending.getConfigVersion())).execute();
        }
    }
}
