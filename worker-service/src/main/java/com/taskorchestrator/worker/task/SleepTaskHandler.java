package com.taskorchestrator.worker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.PayloadRules;
import org.springframework.stereotype.Component;

@Component
public class SleepTaskHandler implements TaskHandler {

    @Override
    public JobType type() {
        return JobType.SLEEP;
    }

    @Override
    public JsonNode execute(TaskContext context) throws InterruptedException {
        long durationMs = Math.min(context.payload().path("durationMs").asLong(), PayloadRules.SLEEP_MAX_MS);
        Thread.sleep(Math.max(0, durationMs));
        return JsonNodeFactory.instance.objectNode().put("sleptMs", durationMs);
    }
}
