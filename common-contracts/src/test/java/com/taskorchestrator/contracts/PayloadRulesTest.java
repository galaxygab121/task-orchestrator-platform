package com.taskorchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayloadRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SLEEP|{\"durationMs\":0}",
        "SLEEP|{\"durationMs\":15000}",
        "FIBONACCI|{\"n\":40}",
        "FIBONACCI|{\"n\":0,\"failTransientlyTimes\":2}",
        "DATA_SUMMARY|{\"values\":[1,2.5,-3]}",
        "DATA_SUMMARY|{\"values\":[1],\"failPermanently\":true}"
    })
    void acceptsBoundedPayloads(JobType type, String payload) throws Exception {
        assertThat(PayloadRules.validate(type, json(payload))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SLEEP|{\"durationMs\":15001}|between 0 and 15000",
        "SLEEP|{\"durationMs\":-1}|between 0 and 15000",
        "SLEEP|{\"durationMs\":1.5}|must be an integer",
        "SLEEP|{}|durationMs is required",
        "FIBONACCI|{\"n\":41}|between 0 and 40",
        "FIBONACCI|{\"n\":\"10\"}|must be an integer",
        "DATA_SUMMARY|{\"values\":[]}|between 1 and 1000",
        "DATA_SUMMARY|{\"values\":[1,\"a\"]}|values[1] must be a finite number",
        "DATA_SUMMARY|{\"values\":5}|must be an array",
        "FIBONACCI|{\"n\":5,\"command\":\"rm -rf /\"}|payload.command is not supported",
        "FIBONACCI|{\"n\":5,\"failTransientlyTimes\":11}|between 0 and 10",
        "FIBONACCI|{\"n\":5,\"failPermanently\":\"yes\"}|must be a boolean",
        "SLEEP|[1,2]|must be a JSON object"
    })
    void rejectsInvalidPayloads(JobType type, String payload, String expectedMessage) throws Exception {
        assertThat(PayloadRules.validate(type, json(payload))).anySatisfy(e -> assertThat(e).contains(expectedMessage));
    }

    @Test
    void rejectsMissingTypeAndPayload() {
        assertThat(PayloadRules.validate(null, null)).containsExactly("type is required");
        assertThat(PayloadRules.validate(JobType.SLEEP, null)).containsExactly("payload must be a JSON object");
    }

    @Test
    void rejectsOversizedArray() throws Exception {
        String values = "[" + "1,".repeat(1_000) + "1]";
        assertThat(PayloadRules.validate(JobType.DATA_SUMMARY, json("{\"values\":" + values + "}"))).isNotEmpty();
    }

    @Test
    void estimatesRuntimeAndReadsSimulationFlags() throws Exception {
        assertThat(PayloadRules.estimateRuntimeMs(JobType.SLEEP, json("{\"durationMs\":1200}"))).isEqualTo(1200);
        assertThat(PayloadRules.estimateRuntimeMs(JobType.FIBONACCI, json("{\"n\":3}"))).isEqualTo(10);
        assertThat(PayloadRules.simulatedTransientFailures(json("{\"failTransientlyTimes\":3}"))).isEqualTo(3);
        assertThat(PayloadRules.simulatedPermanentFailure(json("{\"failPermanently\":true}"))).isTrue();
        assertThat(PayloadRules.simulatedPermanentFailure(json("{}"))).isFalse();
    }
}
