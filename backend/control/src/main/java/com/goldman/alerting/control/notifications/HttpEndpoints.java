package com.goldman.alerting.control.notifications;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties("notifications.http")
record HttpEndpoints(Map<String, Endpoint> endpoints) {
    record Endpoint(URI url, String authorization) {}

    Endpoint require(String alias) {
        var endpoint = endpoints == null ? null : endpoints.get(alias);
        if (endpoint == null || endpoint.url() == null || endpoint.url().getHost() == null
                || !java.util.Set.of("https", "http").contains(endpoint.url().getScheme())
                || endpoint.url().getUserInfo() != null || endpoint.url().getFragment() != null) {
            throw new IllegalArgumentException("Unconfigured HTTP endpoint: " + alias);
        }
        return endpoint;
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HttpEndpoints.class)
class HttpEndpointConfiguration {}
