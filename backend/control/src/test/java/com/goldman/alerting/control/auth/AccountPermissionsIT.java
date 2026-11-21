package com.goldman.alerting.control.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static com.goldman.alerting.control.db.Tables.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountPermissionsIT {
    @Autowired TestRestTemplate http;
    @Autowired DSLContext db;

    ResponseEntity<JsonNode> call(HttpMethod method, String path, String cookie, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    String login(String identifier) {
        var result = call(POST, "/auth/login", null, Map.of("username", identifier, "password", "Runbook123!"));
        assertEquals(HttpStatus.OK, result.getStatusCode());
        var cookie = result.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("HttpOnly"));
        return cookie.split(";", 2)[0];
    }

    @Test void validatesCredentialsRestoresSessionsAndInvalidatesOnLogout() {
        assertEquals(HttpStatus.UNAUTHORIZED, call(GET, "/auth/me", null, null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, call(POST, "/auth/login", null,
                Map.of("username","demo.leader","password","wrong")).getStatusCode());
        var cookie = login("demo.leader@example.test");
        var me = call(GET, "/auth/me", cookie, null).getBody();
        assertEquals("TEAM_LEADER", me.path("role").asText());
        assertEquals("demo.leader", me.path("username").asText());
        assertTrue(me.path("teams").isArray());
        assertFalse(me.has("passwordHash"));
        assertEquals(HttpStatus.NO_CONTENT, call(POST, "/auth/logout", cookie, null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, call(GET, "/auth/me", cookie, null).getStatusCode());
    }

    @Test void anonymousRuleWritesRequireLogin() {
        assertEquals(HttpStatus.UNAUTHORIZED, call(POST, "/rules", null, Map.of()).getStatusCode());
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"1\"");
        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange("/api/rules/" + UUID.randomUUID(),
                PUT, new HttpEntity<>("{}", headers), JsonNode.class).getStatusCode());
    }
}
