package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rules.redis.enabled", havingValue = "true")
class RuleSubscription {
    @Bean
    RedisRuleCreatedAdapter ruleCreatedAdapter(StringRedisTemplate redis, ObjectMapper mapper, RuleIndexManager index,
            @Value("${rules.redis.channel}") String channel) {
        return new RedisRuleCreatedAdapter(mapper, index, redis, channel);
    }

    @Bean
    RedisMessageListenerContainer ruleSubscriptionContainer(RedisConnectionFactory factory, RedisRuleCreatedAdapter adapter,
            @Value("${rules.redis.channel}") String channel) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(adapter, new ChannelTopic(channel));
        return container;
    }
}
