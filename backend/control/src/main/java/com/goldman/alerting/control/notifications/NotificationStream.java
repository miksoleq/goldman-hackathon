package com.goldman.alerting.control.notifications;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Configuration
class NotificationStream {
    private static final String TOPIC = "notifications.changed";
    private final ConcurrentHashMap<SseEmitter, UUID> clients = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;

    NotificationStream(StringRedisTemplate redis) { this.redis = redis; }

    @Bean
    RedisMessageListenerContainer feedChanges() {
        var listener = new RedisMessageListenerContainer() {
            @Override public boolean isAutoStartup() { return false; }
        };
        listener.setConnectionFactory(redis.getRequiredConnectionFactory());
        listener.addMessageListener((message, pattern) -> {
            UUID user = UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
            clients.forEach((client, owner) -> {
                if (owner.equals(user)) send(client, TOPIC);
            });
        }, new ChannelTopic(TOPIC));
        return listener;
    }

    @TransactionalEventListener
    public void committed(NotificationRouting.FeedChanged changed) {
        try {
            redis.convertAndSend(TOPIC, changed.userId().toString());
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(getClass()).warn("Could not publish feed change for {}", changed.userId());
        }
    }

    // Reading the durable inbox and creating rules also work during a Redis outage.
    private synchronized void ensureSubscribed() {
        var listener = feedChanges();
        if (listener.isRunning()) return;
        try {
            listener.start();
        } catch (RuntimeException error) {
            listener.stop();
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Feed stream unavailable", error);
        }
    }

    SseEmitter subscribe(UUID user) {
        ensureSubscribed();
        var client = new SseEmitter(60_000L);
        client.onCompletion(() -> clients.remove(client));
        client.onTimeout(client::complete);
        client.onError(error -> clients.remove(client));
        clients.put(client, user);
        send(client, "ready");
        return client;
    }

    private void send(SseEmitter client, String name) {
        try {
            client.send(SseEmitter.event().name(name).data("{}"));
        } catch (IOException | IllegalStateException e) {
            clients.remove(client);
            client.completeWithError(e);
        }
    }
}
