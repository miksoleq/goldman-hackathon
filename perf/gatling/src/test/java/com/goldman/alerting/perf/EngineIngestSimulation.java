package com.goldman.alerting.perf;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/** Measures synchronous HTTP ingest, matching, Redis deduplication and publishing. */
public class EngineIngestSimulation extends Simulation {

    private static final int REQUESTS_PER_SECOND = Integer.getInteger("rps", 50);
    private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 30);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);
    private static final int MAX_P95_MS = Integer.getInteger("maxP95Ms", 1000);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(System.getProperty("baseUrl", "http://localhost:8081"))
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("goldman-engine-gatling")
            .shareConnections();

    private final io.gatling.javaapi.core.ScenarioBuilder ingest = scenario("Engine HTTP ingest")
            .exec(http("POST /ingest/gatling")
                    .post("/ingest/gatling")
                    .body(io.gatling.javaapi.core.CoreDsl.StringBody(
                            "{\"service\":\"gatling\",\"severity\":\"error\",\"message\":\"synthetic load event\"}"))
                    .check(status().is(202)));

    {
        if (REQUESTS_PER_SECOND < 1 || DURATION_SECONDS < 1 || RAMP_SECONDS < 1 || MAX_P95_MS < 1) {
            throw new IllegalArgumentException("rps, durationSeconds, rampSeconds and maxP95Ms must be positive");
        }
        setUp(ingest.injectOpen(
                        rampUsersPerSec(1).to(REQUESTS_PER_SECOND).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantUsersPerSec(REQUESTS_PER_SECOND).during(Duration.ofSeconds(DURATION_SECONDS))))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().count().is(0L),
                        global().responseTime().percentile3().lt(MAX_P95_MS));
    }
}
