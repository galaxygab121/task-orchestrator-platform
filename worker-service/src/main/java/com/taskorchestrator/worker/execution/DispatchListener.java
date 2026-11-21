package com.taskorchestrator.worker.execution;

import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.contracts.events.JobDispatchEvent;
import com.taskorchestrator.core.messaging.EventCodec;
import com.taskorchestrator.core.messaging.PoisonMessageException;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DispatchListener {

    private final EventCodec codec;
    private final JobExecutionService executionService;

    public DispatchListener(EventCodec codec, JobExecutionService executionService) {
        this.codec = codec;
        this.executionService = executionService;
    }

    @KafkaListener(topics = Topics.JOB_DISPATCH, concurrency = "${worker.concurrency:2}")
    public void onDispatch(String message) {
        JobDispatchEvent event = codec.decode(message, JobDispatchEvent.class);
        if (event.schemaVersion() != JobDispatchEvent.SCHEMA_VERSION || event.jobId() == null || event.attempt() < 1) {
            throw new PoisonMessageException("Unsupported JobDispatch event (schemaVersion=" + event.schemaVersion() + ")", null);
        }
        MDC.put("jobId", event.jobId().toString());
        try {
            executionService.process(event);
        } finally {
            MDC.remove("jobId");
        }
    }
}
