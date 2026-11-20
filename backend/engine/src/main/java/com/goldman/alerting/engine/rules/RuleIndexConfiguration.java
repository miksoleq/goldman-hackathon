package com.goldman.alerting.engine.rules;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuleIndexConfiguration {

    @Bean
    RuleIndexManager ruleIndexManager() {
        return new RuleIndexManager(List.of());
    }
}
