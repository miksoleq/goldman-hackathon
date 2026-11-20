package com.goldman.alerting.control.rules;

import org.jooq.DSLContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static com.goldman.alerting.control.db.Tables.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "rules.kafka.enabled=false")
@Transactional
class RulePersistenceIT {
    @Autowired RuleDocument parser;
    @Autowired RuleCreation creation;
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;

    @Test
    void routesOnlyToNamedUser() throws Exception {
        UUID user = UUID.randomUUID();
        db.insertInto(USERS, USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME)
                .values(user, user.toString(), "Recipient").execute();
        String source = """
                name: personal-%s
                match: {path: payload.code, op: eq, value: TIMEOUT}
                title: Timeout
                route: {users: ['%s']}
                """.formatted(user, user);
        var created = creation.create(parser.parse(source), source, "operator");
        assertThat(db.fetchCount(RULE_USERS, RULE_USERS.RULE_ID.eq(created.id()).and(RULE_USERS.USER_ID.eq(user)))).isEqualTo(1);
        var pending = db.selectFrom(RULE_CREATED_OUTBOX).where(RULE_CREATED_OUTBOX.RULE_ID.eq(created.id())).fetchSingle();
        var route = json.readTree(pending.getPayload().data());
        assertThat(route.get("teamIds").size()).isZero();
        assertThat(route.at("/userIds/0").asText()).isEqualTo(user.toString());
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
