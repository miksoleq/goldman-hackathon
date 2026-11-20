package com.goldman.alerting.control.rules;

import org.jooq.DSLContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static com.goldman.alerting.control.db.Tables.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "rules.kafka.enabled=false")
class RulePersistenceIT {
    @Autowired RuleDocument parser;
    @Autowired RuleCreation creation;
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void storesDirectUserAndOptionalTeam(boolean includeTeam) throws Exception {
        UUID userId = UUID.randomUUID();
        String username = "user-" + userId;
        db.insertInto(USERS, USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME)
                .values(userId, username, "Demo user").execute();
        String teamName = "team-" + userId;
        UUID teamId = UUID.randomUUID();
        if (includeTeam) db.insertInto(TEAMS, TEAMS.ID, TEAMS.NAME).values(teamId, teamName).execute();
        String source = """
                name: personal-%s
                title: Timeout
                match: {path: payload.code, op: eq, value: TIMEOUT}
                route:
                  users: [%s]
                """.formatted(userId, username);
        if (includeTeam) source += "  teams: [" + teamName + "]\n";
        var created = creation.create(parser.parse(source), source, "operator");
        assertThat(db.select(RULE_USERS.USER_ID).from(RULE_USERS)
                .where(RULE_USERS.RULE_ID.eq(created.id())).fetch(RULE_USERS.USER_ID)).containsExactly(userId);
        var event = db.selectFrom(RULE_CREATED_OUTBOX)
                .where(RULE_CREATED_OUTBOX.CONFIG_VERSION.eq(created.configVersion())).fetchSingle();
        var payload = json.readTree(event.getPayload().data());
        assertThat(payload.get("userIds")).isEqualTo(json.valueToTree(java.util.List.of(userId)));
        assertThat(payload.get("teamIds")).isEqualTo(json.valueToTree(includeTeam ? java.util.List.of(teamId) : java.util.List.of()));
    }

    @Test
    void persistsRuleAndOutboxTogetherAndRejectsDuplicateName() throws Exception {
        UUID teamId = UUID.randomUUID();
        String teamName = "team-" + teamId;
        db.insertInto(TEAMS, TEAMS.ID, TEAMS.NAME).values(teamId, teamName).execute();
        var source = """
                name: rule-%s
                match: {path: payload.code, op: eq, value: TIMEOUT}
                title: Timeout
                route: {teams: [%s]}
                """.formatted(teamId, teamName);
        var draft = parser.parse(source);
        var created = creation.create(draft, source, "operator");
        assertThatThrownBy(() -> creation.create(draft, source, "operator"))
                .isInstanceOf(RuleCreation.NameConflict.class);
        assertThat(db.select(RULE_TEAMS.TEAM_ID).from(RULE_TEAMS)
                .where(RULE_TEAMS.RULE_ID.eq(created.id())).fetch(RULE_TEAMS.TEAM_ID)).containsExactly(teamId);
        var history = db.selectFrom(RULE_VERSIONS).where(RULE_VERSIONS.RULE_ID.eq(created.id())
                .and(RULE_VERSIONS.VERSION.eq(1))).fetchSingle();
        assertThat(history.getSourceYaml()).isEqualTo(source);
        var pending = db.selectFrom(RULE_CREATED_OUTBOX)
                .where(RULE_CREATED_OUTBOX.CONFIG_VERSION.eq(created.configVersion())).fetchSingle();
        var payload = json.readTree(pending.getPayload().data());
        assertThat(payload.get("definition")).isEqualTo(draft.definition());
        assertThat(payload.get("ruleId").asText()).isEqualTo(created.id().toString());
    }
}
