package com.goldman.alerting.control.alerts;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import com.goldman.alerting.control.actions.RunbookActions;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import static com.goldman.alerting.control.db.Tables.*;

@RestController
@RequestMapping("/api")
class AlertReadController {
    record BookSummary(UUID id, String name, String format) {}
    private final DSLContext db;
    private final RunbookActions actions;

    AlertReadController(DSLContext db, RunbookActions actions) {
        this.db = db;
        this.actions = actions;
    }

    @GetMapping("/runbooks")
    List<BookSummary> runbooks() {
        return db.select(RUNBOOKS.ID, RUNBOOKS.NAME, RUNBOOKS.FORMAT).from(RUNBOOKS)
                .where(RUNBOOKS.ENABLED.isTrue()).orderBy(RUNBOOKS.NAME).fetch(Records.mapping(BookSummary::new));
    }

    @GetMapping(value = "/alerts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode alert(@PathVariable("id") UUID id) {
        return actions.detail(id);
    }

    record ExecuteRequest(boolean confirmed) {}

    @PostMapping("/alerts/{id}/actions/{stepNo}/execute")
    RunbookActions.Step execute(@PathVariable("id") UUID id, @PathVariable("stepNo") int stepNo,
                                @RequestBody ExecuteRequest request, Principal principal) {
        return actions.execute(id, stepNo, request.confirmed(), principal == null ? "system" : principal.getName());
    }
}
