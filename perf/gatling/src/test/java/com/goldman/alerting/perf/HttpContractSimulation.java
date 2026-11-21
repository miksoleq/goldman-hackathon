package com.goldman.alerting.perf;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/** Smoke checks the HTTP ingest status and response contracts. */
public class HttpContractSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(System.getProperty("baseUrl", "http://localhost:8081"))
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("goldman-engine-gatling")
            .shareConnections();

    private final io.gatling.javaapi.core.ScenarioBuilder contract = scenario("Engine HTTP contract")
            .exec(http("GET /actuator/health")
                    .get("/actuator/health")
                    .check(status().is(200), jsonPath("$.status").is("UP")))
            .exec(http("POST JSON /ingest")
                    .post("/ingest/gatling-contract")
                    .body(io.gatling.javaapi.core.CoreDsl.StringBody("{\"smoke\":true}"))
                    .check(status().is(202), jsonPath("$.eventId").exists()))
            .exec(http("POST text /ingest")
                    .post("/ingest/gatling-contract")
                    .header("Content-Type", "text/plain")
                    .body(io.gatling.javaapi.core.CoreDsl.StringBody("synthetic smoke message"))
                    .check(status().is(202), jsonPath("$.eventId").exists()))
            .exec(http("POST malformed JSON /ingest")
                    .post("/ingest/gatling-contract")
                    .body(io.gatling.javaapi.core.CoreDsl.StringBody("{"))
                    .check(status().is(400), jsonPath("$.code").is("invalid_json")))
            .exec(http("POST empty /ingest")
                    .post("/ingest/gatling-contract")
                    .body(io.gatling.javaapi.core.CoreDsl.StringBody(""))
                    .check(status().is(400), jsonPath("$.code").is("invalid_request")));

    {
        setUp(contract.injectOpen(io.gatling.javaapi.core.CoreDsl.atOnceUsers(1)))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().count().is(0L));
    }
}
