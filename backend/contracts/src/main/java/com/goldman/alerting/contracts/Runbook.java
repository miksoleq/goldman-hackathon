package com.goldman.alerting.contracts;

import java.util.UUID;

/** The content is a template on a rule and rendered instructions on an alert. */
public record Runbook(UUID id, String name, String format, String content) {}
