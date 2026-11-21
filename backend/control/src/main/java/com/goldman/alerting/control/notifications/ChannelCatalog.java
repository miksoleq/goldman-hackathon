package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.Tables.CHANNELS;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;

@Service
public class ChannelCatalog {
    record Channel(String name, String type, JsonNode config) {}
    private final DSLContext db;
    private final ObjectMapper json;
    private final ChannelTransports transports;

    ChannelCatalog(DSLContext db, ObjectMapper json, ChannelTransports transports) {
        this.db = db;
        this.json = json;
        this.transports = transports;
    }

    private org.jooq.Condition supported() {
        return CHANNELS.TYPE.in(transports.types()).or(CHANNELS.NAME.eq("ui").and(CHANNELS.TYPE.eq("ui")));
    }

    Map<String, UUID> ids() {
        return db.select(CHANNELS.NAME, CHANNELS.ID).from(CHANNELS).where(supported())
                .orderBy(CHANNELS.NAME).fetchMap(CHANNELS.NAME, CHANNELS.ID);
    }

    List<Channel> enabled() {
        return db.select(CHANNELS.NAME, CHANNELS.TYPE, CHANNELS.CONFIG).from(CHANNELS)
                .where(CHANNELS.ENABLED.isTrue().and(supported()))
                .fetch(row -> new Channel(row.value1(), row.value2(), decode(row.value3())));
    }

    JsonNode decode(JSONB value) {
        try {
            return json.readTree(value.data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored JSON", e);
        }
    }
}
