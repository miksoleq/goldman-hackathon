package com.goldman.alerting.control.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class LiveAlertFeed {
    private final MonitorController monitor;
    private final Set<SseEmitter> subscribers = ConcurrentHashMap.newKeySet();
    private List<JsonNode> previous;

    LiveAlertFeed(MonitorController monitor) {
        this.monitor = monitor;
    }

    @GetMapping(path = "/api/monitor/alerts/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    synchronized SseEmitter subscribe(HttpServletResponse response) throws IOException {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        var emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> subscribers.remove(emitter));
        // Establish the same baseline for new and existing tabs before future updates.
        var snapshot = monitor.alerts();
        publishChanges(snapshot);
        emitter.send(SseEmitter.event().name("alerts").reconnectTime(1000).data(snapshot));
        subscribers.add(emitter);
        return emitter;
    }

    @Scheduled(fixedDelayString = "${alerts.ui-poll-ms:300}")
    synchronized void poll() {
        if (!subscribers.isEmpty()) publishChanges(monitor.alerts());
    }

    private void publishChanges(List<JsonNode> snapshot) {
        if (snapshot.equals(previous)) return;
        previous = snapshot;
        for (var emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("alerts").data(snapshot));
            } catch (IOException | IllegalStateException disconnected) {
                subscribers.remove(emitter);
                emitter.complete();
            }
        }
    }
}