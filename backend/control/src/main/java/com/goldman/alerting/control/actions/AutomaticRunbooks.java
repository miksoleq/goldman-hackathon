package com.goldman.alerting.control.actions;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AutomaticRunbooks {
    private final RunbookActions actions;

    AutomaticRunbooks(RunbookActions actions) {
        this.actions = actions;
    }

    @Scheduled(fixedDelayString = "${runbooks.poll-ms:200}")
    public void runNextStep() {
        var id = actions.claimNext();
        if (id != null) actions.runClaimed(id);
    }
}
