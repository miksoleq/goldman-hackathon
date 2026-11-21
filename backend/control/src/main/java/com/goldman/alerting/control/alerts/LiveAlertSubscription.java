package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.schema.LiveAlert;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
class LiveAlertSubscription {
    @Bean
    RedisMessageListenerContainer liveAlerts(RedisConnectionFactory connection, ObjectMapper mapper,
                                            ApplicationEventPublisher events) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener((message, pattern) -> {
            try {
                var alert = mapper.readValue(message.getBody(), LiveAlert.class);
                if (alert.v() == 1 && "alert.created".equals(alert.type())) events.publishEvent(alert);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Invalid alerts.live message", e);
            }
        }, new ChannelTopic(LiveAlert.CHANNEL));
        return container;
    }
}
