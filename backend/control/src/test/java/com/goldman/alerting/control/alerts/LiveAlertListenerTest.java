package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class LiveAlertListenerTest {
    static String alert(UUID id, String type, int count) {
        return """
                {"v":1,"type":"%s","alertId":"%s","eventId":"%s","ruleId":"%s",
                 "userIds":["%s"],"teamIds":[],"title":"Timeout","severity":"high",
                 "count":%d,"occurredAt":"2026-11-21T12:00:00Z"}
                """.formatted(type, id, UUID.randomUUID(), id, id, count);
    }

    @Test
    void rejectsInvalidRecordsAndAcceptsPersonalAlertsWithoutABody() {
        var metrics = new SimpleMeterRegistry();
        var listener = new LiveAlertListener(JsonMapper.builder().findAndAddModules().build(), metrics);
        var id = UUID.randomUUID();
        String valid = alert(id, "alert.created", 1);
        for (String invalid : new String[]{null, "null", "{", valid.replace("alert.created","unknown"),
                valid.replace("\"v\":1", "\"v\":2"), valid.replace("\"count\":1", "\"count\":3"),
                valid.replace("\"teamIds\":[],", ""), valid.replace("[\"" + id + "\"]", "[]")}) {
            listener.receive(new ConsumerRecord<>("alerts.live", 0, 0, id.toString(), invalid));
        }
        listener.receive(new ConsumerRecord<>("alerts.live", 0, 0, "wrong-key", valid));
        listener.receive(new ConsumerRecord<>("alerts.live", 0, 0, id.toString(), valid));
        assertThat(metrics.get("alerts.live.records").tag("result","rejected").counter().count()).isEqualTo(9);
        assertThat(metrics.get("alerts.live.records").tag("result","received").counter().count()).isEqualTo(1);
    }
}
