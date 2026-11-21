package com.taskorchestrator.scheduler;

import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.contracts.events.JobSubmittedEvent;
import com.taskorchestrator.core.messaging.EventCodec;
import com.taskorchestrator.core.messaging.PoisonMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Treats a submission event purely as a wake-up signal so new jobs are dispatched immediately instead of
 * waiting for the next polling cycle. The DB queue is authoritative, so duplicates are harmless.
 */
@Component
public class SubmissionListener {

    private static final Logger log = LoggerFactory.getLogger(SubmissionListener.class);

    private final EventCodec codec;
    private final DispatchService dispatchService;

    public SubmissionListener(EventCodec codec, DispatchService dispatchService) {
        this.codec = codec;
        this.dispatchService = dispatchService;
    }

    @KafkaListener(topics = Topics.JOB_SUBMITTED, concurrency = "${scheduler.listener-concurrency:1}")
    public void onSubmitted(String message) {
        JobSubmittedEvent event = codec.decode(message, JobSubmittedEvent.class);
        if (event.schemaVersion() != JobSubmittedEvent.SCHEMA_VERSION || event.jobId() == null) {
            throw new PoisonMessageException("Unsupported JobSubmitted event (schemaVersion=" + event.schemaVersion() + ")", null);
        }
        MDC.put("jobId", event.jobId().toString());
        try {
            int queued = dispatchService.dispatchOnce();
            log.debug("Submission event {} triggered dispatch of {} job(s)", event.eventId(), queued);
        } finally {
            MDC.remove("jobId");
        }
    }
}
