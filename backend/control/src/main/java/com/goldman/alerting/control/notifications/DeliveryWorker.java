package com.goldman.alerting.control.notifications;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="notifications.worker-enabled", matchIfMissing=true)
class DeliveryWorker {
    private final DeliveryQueue queue;
    private final ChannelTransports transports;

    DeliveryWorker(DeliveryQueue queue, ChannelTransports transports) {
        this.queue = queue;
        this.transports = transports;
    }

    @Scheduled(fixedDelayString="${notifications.poll-ms:500}")
    public void deliver() {
        for (var job : queue.claim(4)) {
            DeliveryQueue.Outcome result;
            try {
                result = transports.get(job.transport()).send(job);
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(getClass()).warn("Delivery {} raised {}", job.id(), error.getClass().getSimpleName());
                result = DeliveryQueue.Outcome.TRANSIENT_FAILURE;
            }
            queue.finish(job, result);
        }
    }
}
