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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/rules", "/apply"})
    void requiresLoginAndReturnsServerAssignedOwnerInCreationListAndDetail(String endpoint) {
        var name = "auth-rule-" + UUID.randomUUID();
        var rule = Map.of("name", name, "title", "Test", "route", Map.of("teams", List.of("demo-operations")),
                "match", Map.of("path","payload.kind","op","eq","value","test"));
        var cookie = login("demo.member");
        var me = call(GET, "/auth/me", cookie, null).getBody();
        for (String path : List.of("/rules", "/apply")) {
            assertEquals(HttpStatus.UNAUTHORIZED, call(POST, path, null, rule).getStatusCode());
        }
        var forged = new HashMap<String,Object>(rule);
        forged.put("created_by_user_id", UUID.randomUUID().toString());
        assertEquals(HttpStatus.BAD_REQUEST, call(POST, "/rules", cookie, forged).getStatusCode());
        var spec = new HashMap<String,Object>(rule);
        spec.remove("name");
        var document = endpoint.equals("/apply") ? Map.of("kind", "Rule", "name", name, "spec", spec) : rule;
        var result = call(POST, endpoint, cookie, document);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        var id = UUID.fromString(result.getBody().path("id").asText());
        try {
            var owner = result.getBody().path("createdBy");
            assertEquals(me.path("id"), owner.path("id"));
            assertEquals("demo.member", owner.path("username").asText());
            assertEquals(owner, call(GET, "/rules/" + id, null, null).getBody().path("createdBy"));
            var rules = call(GET, "/monitor/rules", null, null).getBody();
            assertTrue(java.util.stream.StreamSupport.stream(rules.spliterator(), false)
                    .anyMatch(r -> name.equals(r.path("name").asText()) && owner.equals(r.path("createdBy"))));
            assertEquals(owner.path("id").asText(), db.select(RULES.CREATED_BY_USER_ID).from(RULES)
                    .where(RULES.ID.eq(id)).fetchSingle(RULES.CREATED_BY_USER_ID).toString());
            db.update(RULES).setNull(RULES.CREATED_BY_USER_ID).where(RULES.ID.eq(id)).execute();
            assertEquals("System", call(GET, "/rules/" + id, null, null).getBody().at("/createdBy/username").asText());
        } finally {
            db.deleteFrom(RULE_CREATED_OUTBOX).where(RULE_CREATED_OUTBOX.RULE_ID.eq(id)).execute();
            db.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(id.toString())).execute();
            db.deleteFrom(RULES).where(RULES.ID.eq(id)).execute();
        }
    }

    @Test void leaderSeesAndManagesOnlyTheirDatabaseAssignedTeam() {
        var member = login("demo.analyst");
        var leader = login("demo.leader");
        var leaderId = UUID.fromString(call(GET, "/auth/me", leader, null).getBody().path("id").asText());
        var userId = call(GET, "/auth/me", member, null).getBody().path("id").asText();
        var user = Map.of("userId", userId);
        var assigned = db.select(TEAMS.ID).from(TEAMS).where(TEAMS.NAME.eq("demo-operations")).fetchSingle(TEAMS.ID);
        var other = db.insertInto(TEAMS).set(TEAMS.NAME, "auth-team-" + UUID.randomUUID())
                .returningResult(TEAMS.ID).fetchSingle(TEAMS.ID);
        var ownPath = "/teams/" + assigned + "/members";
        var otherPath = "/teams/" + other + "/members";
        try {
            db.insertInto(TEAM_MEMBERS, TEAM_MEMBERS.TEAM_ID, TEAM_MEMBERS.USER_ID).values(other, leaderId).execute();
            var directory = call(GET, "/teams/directory", leader, null).getBody();
            assertEquals(1, directory.size());
            assertEquals(assigned.toString(), directory.at("/0/id").asText());
            assertTrue(call(GET, "/teams/directory", member, null).getBody().toString().contains(other.toString()));
            assertEquals(HttpStatus.UNAUTHORIZED, call(POST, ownPath, null, user).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, call(POST, ownPath, member, user).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, call(POST, otherPath, leader, user).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, call(DELETE, otherPath + "/" + leaderId, leader, null).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, call(DELETE, ownPath + "/" + leaderId, leader, null).getStatusCode());
            assertEquals(HttpStatus.OK, call(POST, ownPath, leader, user).getStatusCode());
            assertEquals(HttpStatus.OK, call(POST, ownPath, leader, user).getStatusCode());
            assertTrue(call(GET, "/auth/me", member, null).getBody().path("teams").toString().contains(assigned.toString()));
            assertEquals(HttpStatus.FORBIDDEN, call(DELETE, ownPath + "/" + userId, member, null).getStatusCode());
            assertEquals(HttpStatus.OK, call(DELETE, ownPath + "/" + userId, leader, null).getStatusCode());
            assertFalse(call(GET, "/auth/me", member, null).getBody().path("teams").toString().contains(assigned.toString()));
        } finally {
            db.deleteFrom(TEAM_MEMBERS).where(TEAM_MEMBERS.TEAM_ID.eq(assigned).and(TEAM_MEMBERS.USER_ID.eq(UUID.fromString(userId)))).execute();
            db.deleteFrom(TEAMS).where(TEAMS.ID.eq(other)).execute();
        }
    }
}
