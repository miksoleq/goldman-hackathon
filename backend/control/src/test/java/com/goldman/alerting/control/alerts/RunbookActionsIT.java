package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.LiveAlert;
import com.goldman.alerting.contracts.Runbook;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import static com.goldman.alerting.control.db.Tables.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunbookActionsIT {
    @Autowired AlertInbox inbox;
    @Autowired ObjectMapper json;
    @Autowired DSLContext db;
    @Autowired TestRestTemplate api;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    final UUID alertId = UUID.randomUUID();
    final String type = "provider-" + alertId;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> key = new AtomicReference<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    HttpServer provider;
    org.springframework.http.HttpHeaders sessionHeaders;

    private static HttpServer runner;
    private static final AtomicReference<JsonNode> runnerRequest = new AtomicReference<>();

    @org.springframework.test.context.DynamicPropertySource
    static void runnerEndpoint(org.springframework.test.context.DynamicPropertyRegistry properties) throws Exception {
        runner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runner.createContext("/execute", exchange -> {
            try (exchange) {
                runnerRequest.set(new ObjectMapper().readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("""
                        {"status":"SUCCESS","exitCode":0,"result":{"healthy":true},"stdout":"ok","stderr":"","durationMs":42}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        runner.start();
        properties.add("script-runner.url", () -> "http://127.0.0.1:" + runner.getAddress().getPort() + "/execute");
    }

    @AfterAll static void stopRunner() { runner.stop(0); }

    @BeforeEach void signIn() {
        var response = api.postForEntity("/api/auth/login",
                Map.of("username", "demo.member", "password", "Runbook123!"), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        sessionHeaders = new org.springframework.http.HttpHeaders();
        sessionHeaders.set("Cookie", response.getHeaders().getFirst("Set-Cookie").split(";", 2)[0]);
    }

    org.springframework.http.HttpEntity<?> confirmation(boolean confirmed) {
        return new org.springframework.http.HttpEntity<>(Map.of("confirmed", confirmed), sessionHeaders);
    }

    @BeforeEach void startProvider() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/workflows/WF-42", exchange -> {
            calls.incrementAndGet();
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"status\":\"running\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        provider.start();
    }

    @AfterEach void cleanup() {
        release.countDown();
        provider.stop(0);
        var ids = db.select(ACTION_EXECUTIONS.ID).from(ACTION_EXECUTIONS)
                .where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)).fetch(ACTION_EXECUTIONS.ID);
        for (UUID id : ids) db.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(id.toString())).execute();
        db.deleteFrom(LIVE_ALERT_INBOX).where(LIVE_ALERT_INBOX.ALERT_ID.eq(alertId)).execute();
        db.deleteFrom(ACTION_TYPES).where(ACTION_TYPES.NAME.eq(type)).execute();
    }

    LiveAlert deliver(int timeoutMs) throws Exception {
        return deliver(timeoutMs, true, List.of("WF-42", ""));
    }

    LiveAlert deliver(int timeoutMs, boolean approval, List<String> workflows) throws Exception {
        var config = json.createObjectNode().put("method", "POST").put("timeoutMs", timeoutMs)
                .put("url", "http://127.0.0.1:" + provider.getAddress().getPort() + "/workflows/{{params.workflowId}}");
        config.putArray("params").add("workflowId");
        db.insertInto(ACTION_TYPES).set(ACTION_TYPES.NAME, type).set(ACTION_TYPES.KIND, "http")
                .set(ACTION_TYPES.APPROVAL_REQUIRED, approval).set(ACTION_TYPES.TEMPLATE, JSONB.valueOf(config.toString())).execute();
        var book = new Runbook(UUID.randomUUID(), "retry", "text", "Check workflow", workflows.stream()
                .map(workflow -> new Runbook.Action(type, Map.of("workflowId", workflow))).toList());
        var alert = new LiveAlert(1, "alert.created", alertId, UUID.randomUUID().toString(), UUID.randomUUID(),
                List.of(), List.of(), "high", "Timeout", "", 1, Instant.now(), book.id(), book);
        inbox.receive(alert, json.writeValueAsString(alert));
        return alert;
    }

    private String url() { return "/api/alerts/" + alertId; }

    JsonNode run() {
        var response = api.postForEntity(url() + "/actions/1/execute", confirmation(true), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    @Test void snapshotsNamedPythonCodeAndAuditsAuthenticatedApprovalAfterHttp() throws Exception {
        String scriptName = "script-" + alertId;
        String code = "def run(ctx): return ctx['previousStepResult']";
        try {
            var document = Map.of("kind", "ActionType", "name", scriptName, "spec", Map.of("type", "python", "code", code));
            assertEquals(201, api.postForEntity("/api/apply", new org.springframework.http.HttpEntity<>(document, sessionHeaders),
                    JsonNode.class).getStatusCode().value());
            var get = json.createObjectNode().put("method", "GET").put("url",
                    "http://127.0.0.1:" + provider.getAddress().getPort() + "/workflows/WF-42");
            get.putArray("params");
            db.insertInto(ACTION_TYPES).set(ACTION_TYPES.NAME, type).set(ACTION_TYPES.KIND, "http")
                    .set(ACTION_TYPES.TEMPLATE, JSONB.valueOf(get.toString())).execute();
            var book = new Runbook(UUID.randomUUID(), "diagnostic", "text", "Check", List.of(
                    new Runbook.Action(type, Map.of()), new Runbook.Action(scriptName, Map.of())));
            var event = json.readTree("{\"payload\":{\"workflowId\":\"WF-42\"}}");
            var alert = new LiveAlert(1, "alert.created", alertId, UUID.randomUUID().toString(), UUID.randomUUID(),
                    List.of(), List.of(), "high", "Timeout", "", 1, Instant.now(), book.id(), book, event);
            release.countDown();
            inbox.receive(alert, json.writeValueAsString(alert));
            awaitStatus(0, "succeeded");
            String execute = url() + "/actions/2/execute";
            assertEquals(401, api.postForEntity(execute, Map.of("confirmed", true), String.class).getStatusCode().value());
            assertEquals(409, api.postForEntity(execute, confirmation(false), String.class).getStatusCode().value());
            var step = api.postForObject(execute, confirmation(true), JsonNode.class);
            assertEquals("python", step.path("kind").asText());
            assertEquals("demo.member", step.path("approvedBy").asText());
            assertEquals("demo.member", step.path("executedBy").asText());
            assertTrue(step.at("/result/result/healthy").asBoolean());
            assertEquals(42, step.at("/result/durationMs").asInt());
            assertEquals(step, api.getForObject(url(), JsonNode.class).at("/actions/1"));
            assertEquals(code, runnerRequest.get().path("code").asText());
            assertEquals(event, runnerRequest.get().at("/context/event"));
            assertEquals(alertId.toString(), runnerRequest.get().at("/context/alert/alertId").asText());
            assertEquals("running", runnerRequest.get().at("/context/previousStepResult/status").asText());
            var audit = db.selectFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(db.select(ACTION_EXECUTIONS.ID.cast(String.class))
                    .from(ACTION_EXECUTIONS).where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)).and(ACTION_EXECUTIONS.STEP_NO.eq(2))))
                    .fetchSingle();
            assertEquals("demo.member", audit.getActor());
            assertEquals(step.path("result"), json.readTree(audit.getDetails().data()).path("result"));
        } finally {
            db.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(scriptName)).execute();
            db.deleteFrom(ACTION_TYPES).where(ACTION_TYPES.NAME.eq(scriptName)).execute();
        }
    }

    @Test void requiresApprovalAndSerializesConcurrentRequestsAcrossReplay() throws Exception {
        var alert = deliver(3000);
        var detail = api.getForObject(url(), JsonNode.class);
        assertEquals("Missing parameter: workflowId", detail.at("/actions/1/reason").asText());
        assertEquals(409, api.postForEntity(url() + "/actions/2/execute", confirmation(true), String.class)
                .getStatusCode().value());
        assertEquals(409, api.postForEntity(url() + "/actions/1/execute", confirmation(false), String.class)
                .getStatusCode().value());
        assertEquals(0, calls.get());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(this::run);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var duplicate = pool.submit(this::run);
            release.countDown();
            assertEquals("succeeded", first.get().path("status").asText());
            assertEquals("running", duplicate.get().at("/result/response/status").asText());
        }
        inbox.receive(alert, json.writeValueAsString(alert));
        assertEquals("succeeded", api.getForObject(url(), JsonNode.class).at("/actions/0/status").asText());
        assertEquals(1, calls.get());
        var row = db.selectFrom(ACTION_EXECUTIONS).where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)
                .and(ACTION_EXECUTIONS.STEP_NO.eq(1))).fetchSingle();
        assertEquals(row.getId().toString(), key.get());
        assertEquals("demo.member", row.getApprovedBy());
        assertEquals(1, db.fetchCount(AUDIT_LOG, AUDIT_LOG.ENTITY_ID.eq(key.get())));
    }

    @Test void timeoutLeavesAnUnknownResultAndAnotherClickDoesNotRepeatThePost() throws Exception {
        deliver(200);
        assertEquals("unknown", run().path("status").asText());
        release.countDown();
        assertFalse(run().path("available").asBoolean());
        assertEquals(1, calls.get());
    }

    private JsonNode awaitStatus(int stepIndex, String status) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(() ->
                assertEquals(status, api.getForObject(url(), JsonNode.class).at("/actions/" + stepIndex + "/status").asText()));
        return api.getForObject(url(), JsonNode.class);
    }

    @Test void runsOrderedStepsWithoutClientAndPreservesResultsOnReplay() throws Exception {
        var alert = deliver(5000, false, List.of("WF-42", "WF-42"));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var pending = api.getForObject(url(), JsonNode.class);
        assertEquals("running", pending.at("/actions/0/status").asText());
        assertEquals("ready", pending.at("/actions/1/status").asText());
        assertFalse(pending.at("/actions/1/available").asBoolean());
        assertEquals(409, api.postForEntity(url() + "/actions/2/execute", confirmation(true), String.class)
                .getStatusCode().value());
        release.countDown();
        var done = awaitStatus(1, "succeeded");
        assertTrue(done.at("/actions/0/automatic").asBoolean());
        assertEquals("succeeded", done.at("/actions/0/status").asText());
        inbox.receive(alert, json.writeValueAsString(alert));
        var rows = db.selectFrom(ACTION_EXECUTIONS).where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId))
                .orderBy(ACTION_EXECUTIONS.STEP_NO).fetch();
        assertEquals(2, rows.size());
        assertFalse(rows.get(1).getStartedAt().isBefore(rows.get(0).getFinishedAt()));
        for (var row : rows) {
            assertEquals("system", row.getExecutedBy());
            assertNull(row.getApprovedBy());
            assertEquals(1, db.fetchCount(AUDIT_LOG, AUDIT_LOG.ENTITY_ID.eq(row.getId().toString())));
        }
        assertEquals(2, calls.get());
    }

    @Test void stopsAfterAmbiguousAutomaticPostAndSkipsRemainingSteps() throws Exception {
        deliver(200, false, List.of("WF-42", "WF-42"));
        var detail = awaitStatus(1, "skipped");
        assertEquals("unknown", detail.at("/actions/0/status").asText());
        assertEquals("Runbook stopped at step 1", detail.at("/actions/1/reason").asText());
        assertEquals(1, calls.get());
    }

    @Test void recoversAnAbandonedClaimWithItsOriginalIdempotencyKey() {
        var originalId = transaction.execute(tx -> {
            try {
                deliver(3000, false, List.of("WF-42"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            // Persist the state left by a Control process that stopped immediately after claiming its step.
            return db.update(ACTION_EXECUTIONS)
                    .set(ACTION_EXECUTIONS.STATUS, com.goldman.alerting.control.db.enums.ActionStatus.running)
                    .set(ACTION_EXECUTIONS.STARTED_AT, java.time.OffsetDateTime.now().minusMinutes(1))
                    .where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)).returningResult(ACTION_EXECUTIONS.ID)
                    .fetchSingle(ACTION_EXECUTIONS.ID);
        });
        release.countDown();
        awaitStatus(0, "succeeded");
        assertEquals(originalId.toString(), key.get());
        assertEquals(1, calls.get());
    }
}
