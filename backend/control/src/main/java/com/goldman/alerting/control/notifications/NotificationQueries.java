package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.Tables.*;
import static org.jooq.impl.DSL.*;
import com.goldman.alerting.control.db.enums.Severity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationQueries {
    record Item(UUID notificationId, UUID alertId, String eventId, String title, Severity severity, OffsetDateTime createdAt) {}
    record Page(List<Item> items, String nextCursor) {}
    record Delivery(UUID notificationId, UUID alertId, String channel, String status, int attempts) {}
    private final DSLContext db;
    NotificationQueries(DSLContext db) {
        this.db = db;
    }

    Page feed(UUID user, String event, String cursor) {
        var d = NOTIFICATION_DELIVERIES;
        var filter = d.USER_ID.eq(user).and(d.CHANNEL_TYPE.eq("ui")).and(d.STATUS.eq("sent"));
        if (event != null) filter = filter.and(d.EVENT_ID.eq(event));
        if (cursor != null) {
            try {
                String[] parts = cursor.split("~", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                filter = filter.and(row(d.CREATED_AT, d.ID).lt(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1])));
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
            }
        }
        var items = db.select(d.ID, d.ALERT_ID, d.EVENT_ID, d.TITLE, d.SEVERITY, d.CREATED_AT).from(d)
                .where(filter).orderBy(d.CREATED_AT.desc(), d.ID.desc()).limit(51).fetch(Records.mapping(Item::new));
        if (items.size() <= 50) return new Page(items, null);
        var last = items.get(49);
        return new Page(items.subList(0, 50), last.createdAt().toInstant() + "~" + last.notificationId());
    }

    List<Delivery> deliveries(UUID user, UUID alert) {
        var d = NOTIFICATION_DELIVERIES;
        return db.select(d.ID, d.ALERT_ID, d.CHANNEL_TYPE, d.STATUS, d.ATTEMPTS).from(d)
                .where(d.USER_ID.eq(user).and(alert == null ? noCondition() : d.ALERT_ID.eq(alert)))
                .orderBy(d.CREATED_AT.desc(), d.ID.desc()).limit(100).fetch(Records.mapping(Delivery::new));
    }
}
