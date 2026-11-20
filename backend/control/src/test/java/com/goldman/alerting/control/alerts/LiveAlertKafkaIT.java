package com.goldman.alerting.control.alerts;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "alerts.kafka.enabled=true")
class LiveAlertKafkaIT {
    @DynamicPropertySource
    static void isolateKafka(DynamicPropertyRegistry properties) {
        String name = "alerts.live.it." + UUID.randomUUID();
        properties.add("alerts.kafka.topic", () -> name);
        properties.add("alerts.kafka.group", () -> name);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired MeterRegistry metrics;
    @Value("${alerts.kafka.topic}") String topic;

    @Test
    void consumesCreationAndUpdateEvenWithMalformedRecordBetweenThem() throws Exception {
        UUID id = UUID.randomUUID();
        for (String value : new String[]{LiveAlertListenerTest.alert(id, "alert.created", 1),
                "broken", LiveAlertListenerTest.alert(id, "alert.updated", 2)}) {
            kafka.send(topic, id.toString(), value).get(20, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(metrics.get("alerts.live.records").tag("result","received").counter().count()).isEqualTo(2);
            assertThat(metrics.get("alerts.live.records").tag("result","rejected").counter().count()).isEqualTo(1);
        });
    }
}
