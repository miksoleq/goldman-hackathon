package com.goldman.alerting.control.alerts;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.goldman.alerting.control.db.Tables.*;

@RestController
@RequestMapping("/api")
class AlertReadController {
    record BookSummary(UUID id, String name, String format) {}
    private final DSLContext db;
    AlertReadController(DSLContext db) { this.db = db; }

    @GetMapping("/runbooks")
    List<BookSummary> runbooks() {
        return db.select(RUNBOOKS.ID, RUNBOOKS.NAME, RUNBOOKS.FORMAT).from(RUNBOOKS)
                .where(RUNBOOKS.ENABLED.isTrue()).orderBy(RUNBOOKS.NAME).fetch(Records.mapping(BookSummary::new));
    }

    @GetMapping(value = "/alerts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    String alert(@PathVariable("id") UUID id) {
        return db.select(LIVE_ALERT_INBOX.PAYLOAD).from(LIVE_ALERT_INBOX)
                .where(LIVE_ALERT_INBOX.ALERT_ID.eq(id)).fetchOptional(LIVE_ALERT_INBOX.PAYLOAD)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).data();
    }
}
