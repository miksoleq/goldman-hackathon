package com.goldman.alerting.engine.rules;

public class InvalidRuleDefinitionException extends IllegalArgumentException {

    public InvalidRuleDefinitionException(String message) {
        super(message);
    }
}
