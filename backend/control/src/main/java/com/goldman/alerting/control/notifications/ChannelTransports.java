package com.goldman.alerting.control.notifications;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class ChannelTransports {
    private final Map<String, ChannelTransport> transports;

    ChannelTransports(List<ChannelTransport> adapters) {
        transports = adapters.stream().collect(Collectors.toUnmodifiableMap(ChannelTransport::type, Function.identity()));
    }

    Set<String> types() { return transports.keySet(); }

    ChannelTransport get(String type) {
        var transport = transports.get(type);
        if (transport == null) throw new IllegalArgumentException("Unsupported outbound transport: " + type);
        return transport;
    }
}
