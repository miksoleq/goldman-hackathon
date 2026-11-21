package com.goldman.alerting.engine.ingest.redis;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name="rules.redis.enabled", havingValue="true")
class RuleSnapshotController {
    private final RedisRuleCreatedAdapter adapter;

    RuleSnapshotController(RedisRuleCreatedAdapter adapter) { this.adapter = adapter; }

    @GetMapping("/api/engine/snapshot")
    List<RedisRuleCreatedAdapter.Status> snapshot() { return adapter.statuses(); }
}
