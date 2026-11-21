package com.goldman.alerting.control.recipients;

import static com.goldman.alerting.control.db.Tables.TEAMS;
import static com.goldman.alerting.control.db.Tables.USERS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecipientControllerIT {
    @Autowired TestRestTemplate http;
    @Autowired DSLContext database;

    @Test
    void listsStoredNamesThroughHttp() {
        String suffix = UUID.randomUUID().toString();
        String team = "directory-team-" + suffix;
        String username = "directory-user-" + suffix;
        try {
            database.insertInto(TEAMS).set(TEAMS.NAME, team).execute();
            database.insertInto(USERS).set(USERS.USERNAME, username).set(USERS.DISPLAY_NAME, "Example User").execute();
            assertTrue(Arrays.asList(http.getForObject("/api/teams", String[].class)).contains(team));
            assertTrue(Arrays.asList(http.getForObject("/api/users", String[].class)).contains(username));
        } finally {
            database.deleteFrom(USERS).where(USERS.USERNAME.eq(username)).execute();
            database.deleteFrom(TEAMS).where(TEAMS.NAME.eq(team)).execute();
        }
    }
}
