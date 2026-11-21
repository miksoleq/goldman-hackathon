package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.schema.LiveAlert;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LiveAlertSubscriptionIT {
    static class Observer {
        final BlockingQueue<LiveAlert> alerts = new LinkedBlockingQueue<>();
        @EventListener public void received(LiveAlert alert) { alerts.add(alert); }
    }
    @TestConfiguration static class Config {
        @Bean Observer observer() { return new Observer(); }
    }
    @Autowired StringRedisTemplate redis;
    @Autowired Observer observer;
    @Autowired ObjectMapper mapper;

    @Test void receivesPersonalAlertOverRedis() throws Exception {
        var alert = new LiveAlert(1, "alert.created", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(), List.of(UUID.randomUUID()), "high", "Timeout", "WF-42", 1,
                Instant.parse("2026-11-21T12:00:00Z"), LiveAlert.UNASSIGNED_RUNBOOK);
        redis.convertAndSend(LiveAlert.CHANNEL, mapper.writeValueAsString(alert));
        assertEquals(alert, observer.alerts.poll(10, TimeUnit.SECONDS));
    }
}
