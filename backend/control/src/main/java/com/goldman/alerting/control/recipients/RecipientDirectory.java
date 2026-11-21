package com.goldman.alerting.control.recipients;

import static com.goldman.alerting.control.db.Tables.TEAMS;
import static com.goldman.alerting.control.db.Tables.USERS;

import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
class RecipientDirectory {
    private final DSLContext database;

    RecipientDirectory(DSLContext database) {
        this.database = database;
    }

    List<String> teams() {
        return database.select(TEAMS.NAME).from(TEAMS).orderBy(TEAMS.NAME).fetch(TEAMS.NAME);
    }

    List<String> users() {
        return database.select(USERS.USERNAME).from(USERS).orderBy(USERS.USERNAME).fetch(USERS.USERNAME);
    }
}
