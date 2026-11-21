package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rules.redis.enabled=true", "ingest.redis.enabled=false"})
class NumericRuleIngestIT {
    private static final String PREFIX = "it:numeric:" + UUID.randomUUID();
    private static final String CHANNEL = PREFIX + ":rules";
    private static final String EVENTS = PREFIX + ":events";
    private static final String ALERTS = PREFIX + ":alerts";

    @DynamicPropertySource
    static void configureRedisKeys(DynamicPropertyRegistry registry) {
        registry.add("rules.redis.channel", () -> CHANNEL);
        registry.add("ingest.redis.internal-stream", () -> EVENTS);
        registry.add("alerts.stream", () -> ALERTS);
    }

    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired RuleIndexManager rules;
    @Autowired ObjectMapper mapper;

    @Test
    void matchesExactDecimalFromHttpUntilRedisDisablesTheRule() throws Exception {
        String id = UUID.randomUUID().toString();
        String message = """
                {"type":"rule.created","ruleId":"%s","version":%d,"enabled":%s,
                 "definition":{"v":1,"title":"Precise amount","match":{"all":[
                   {"path":"payload.amount","op":"eq","value":1.00000000000000001},
                   {"path":"payload.amount","op":"gt","value":1}]}}}
                """;
        var stream = redis.<String, String>opsForStream();
        try {
            redis.convertAndSend(CHANNEL, message.formatted(id, 1, true));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> rules.snapshot().ruleCount() == 1);
            ingest("1.00000000000000002");
            String matchedEvent = ingest("1.000000000000000010");
            var alerts = stream.range(ALERTS, Range.unbounded());
            assertEquals(1, alerts.size());
            var alert = mapper.readTree(alerts.getFirst().getValue().get("alert"));
            assertEquals(id, alert.path("ruleId").asText());
            assertEquals(matchedEvent, alert.path("eventId").asText());

            redis.convertAndSend(CHANNEL, message.formatted(id, 2, false));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> rules.snapshot().ruleCount() == 0);
            ingest("1.00000000000000001");
            assertEquals(1L, stream.size(ALERTS));
        } finally {
            redis.delete(List.of(EVENTS, ALERTS));
        }
    }

    private String ingest(String amount) throws Exception {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>("{\"amount\":" + amount + "}", headers);
        var response = http.postForEntity("/ingest", request, String.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        return mapper.readTree(response.getBody()).required("eventId").textValue();
    }
}
