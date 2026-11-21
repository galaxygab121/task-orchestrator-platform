package com.taskorchestrator.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.contracts.JobType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaskHandlersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private TaskContext ctx(JobType type, String payload) throws Exception {
        return new TaskContext(UUID.randomUUID(), type, 1, mapper.readTree(payload));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "2,1", "10,55", "20,6765", "30,832040", "40,102334155"})
    void fibonacciMatchesKnownValues(int n, long expected) throws Exception {
        JsonNode result = new FibonacciTaskHandler().execute(ctx(JobType.FIBONACCI, "{\"n\":" + n + "}"));
        assertThat(result.get("value").asLong()).isEqualTo(expected);
        assertThat(result.get("n").asInt()).isEqualTo(n);
    }

    @Test
    void fibonacciRejectsOutOfRange() {
        assertThatThrownBy(() -> new FibonacciTaskHandler().execute(ctx(JobType.FIBONACCI, "{\"n\":41}")))
                .isInstanceOf(PermanentTaskException.class);
    }

    @Test
    void dataSummaryComputesStatistics() throws Exception {
        JsonNode r = new DataSummaryTaskHandler().execute(ctx(JobType.DATA_SUMMARY, "{\"values\":[4, 1, 3, 2, 10]}"));
        assertThat(r.get("count").asInt()).isEqualTo(5);
        assertThat(r.get("sum").asDouble()).isEqualTo(20.0);
        assertThat(r.get("min").asDouble()).isEqualTo(1.0);
        assertThat(r.get("max").asDouble()).isEqualTo(10.0);
        assertThat(r.get("mean").asDouble()).isEqualTo(4.0);
        assertThat(r.get("median").asDouble()).isEqualTo(3.0);
        assertThat(r.get("stdDev").asDouble()).isCloseTo(Math.sqrt(10.0), within(1e-9));
    }

    @Test
    void dataSummaryEvenCountMedian() throws Exception {
        JsonNode r = new DataSummaryTaskHandler().execute(ctx(JobType.DATA_SUMMARY, "{\"values\":[1.5, -2, 8, 4]}"));
        assertThat(r.get("median").asDouble()).isEqualTo(2.75);
        assertThat(r.get("min").asDouble()).isEqualTo(-2.0);
    }

    @Test
    void sleepSleepsForRequestedDuration() throws Exception {
        long start = System.nanoTime();
        JsonNode r = new SleepTaskHandler().execute(ctx(JobType.SLEEP, "{\"durationMs\":120}"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(115);
        assertThat(r.get("sleptMs").asLong()).isEqualTo(120);
    }

    @Test
    void registryIsAnAllowlist() {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(
                List.of(new SleepTaskHandler(), new FibonacciTaskHandler(), new DataSummaryTaskHandler()));
        for (JobType type : JobType.values()) {
            assertThat(registry.require(type).type()).isEqualTo(type);
        }
        assertThatThrownBy(() -> registry.require(null)).isInstanceOf(PermanentTaskException.class);
        assertThatThrownBy(() -> new TaskHandlerRegistry(List.of(new SleepTaskHandler(), new SleepTaskHandler())))
                .isInstanceOf(IllegalStateException.class);
    }
}
