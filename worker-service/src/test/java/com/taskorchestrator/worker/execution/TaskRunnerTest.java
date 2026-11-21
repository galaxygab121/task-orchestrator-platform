package com.taskorchestrator.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.worker.WorkerProperties;
import com.taskorchestrator.worker.task.DataSummaryTaskHandler;
import com.taskorchestrator.worker.task.FibonacciTaskHandler;
import com.taskorchestrator.worker.task.PermanentTaskException;
import com.taskorchestrator.worker.task.SleepTaskHandler;
import com.taskorchestrator.worker.task.TaskContext;
import com.taskorchestrator.worker.task.TaskHandlerRegistry;
import com.taskorchestrator.worker.task.TransientTaskException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRunner runner = new TaskRunner(
            new TaskHandlerRegistry(List.of(new SleepTaskHandler(), new FibonacciTaskHandler(), new DataSummaryTaskHandler())),
            new WorkerProperties(2, Duration.ofMillis(300), Duration.ofSeconds(30), Duration.ofSeconds(10), "test"));

    @AfterEach
    void stop() throws InterruptedException {
        runner.shutdown();
    }

    private TaskContext ctx(JobType type, int attempt, String payload) throws Exception {
        return new TaskContext(UUID.randomUUID(), type, attempt, mapper.readTree(payload));
    }

    @Test
    void runsHandler() throws Exception {
        assertThat(runner.run(ctx(JobType.FIBONACCI, 1, "{\"n\":12}")).get("value").asLong()).isEqualTo(144);
    }

    @Test
    void timeoutIsTransientAndInterruptsTask() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(ctx(JobType.SLEEP, 1, "{\"durationMs\":5000}")))
                .isInstanceOf(TransientTaskException.class)
                .hasMessageContaining("timed out after 300 ms");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void simulatedTransientFailuresStopAfterConfiguredAttempts() throws Exception {
        String payload = "{\"n\":5,\"failTransientlyTimes\":2}";
        assertThatThrownBy(() -> runner.run(ctx(JobType.FIBONACCI, 1, payload))).isInstanceOf(TransientTaskException.class);
        assertThatThrownBy(() -> runner.run(ctx(JobType.FIBONACCI, 2, payload))).isInstanceOf(TransientTaskException.class);
        assertThat(runner.run(ctx(JobType.FIBONACCI, 3, payload)).get("value").asLong()).isEqualTo(5);
    }

    @Test
    void simulatedPermanentFailureIsNotRetryable() {
        assertThatThrownBy(() -> runner.run(ctx(JobType.DATA_SUMMARY, 1, "{\"values\":[1],\"failPermanently\":true}")))
                .isInstanceOf(PermanentTaskException.class);
    }

    @Test
    void invalidPayloadIsRejectedBeforeExecution() {
        assertThatThrownBy(() -> runner.run(ctx(JobType.SLEEP, 1, "{\"durationMs\":999999}")))
                .isInstanceOf(PermanentTaskException.class)
                .hasMessageContaining("between 0 and 15000");
        assertThatThrownBy(() -> runner.run(ctx(JobType.FIBONACCI, 1, "{\"n\":3,\"exec\":\"curl evil\"}")))
                .isInstanceOf(PermanentTaskException.class)
                .hasMessageContaining("not supported");
    }
}
