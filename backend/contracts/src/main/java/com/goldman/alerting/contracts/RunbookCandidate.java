package com.goldman.alerting.contracts;

import com.fasterxml.jackson.databind.JsonNode;

/** One enabled, conditionally applicable runbook in the Redis catalog. */
public record RunbookCandidate(Runbook runbook, JsonNode matches) {
    public static String snapshotKey(String ruleChannel) { return ruleChannel + ":runbooks:v1"; }
}
