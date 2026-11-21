package com.taskorchestrator.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskorchestrator.contracts.JobType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A bounded built-in task. Payload fields depend on type; see README for the allowlist.")
public record CreateJobRequest(
        @NotBlank @Size(max = 200) @Schema(example = "fib-30") String title,
        @NotNull @Schema(example = "FIBONACCI") JobType type,
        @NotNull @Schema(example = "{\"n\": 30}") JsonNode payload,
        @Min(1) @Max(5) @Schema(description = "1 (lowest) .. 5 (highest), default 3", example = "3") Integer priority,
        @Min(0) @Max(5) @Schema(description = "Retries after the first attempt, default 3", example = "3") Integer maxRetries) {

    public static final int DEFAULT_PRIORITY = 3;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public int priorityOrDefault() {
        return priority == null ? DEFAULT_PRIORITY : priority;
    }

    public int maxRetriesOrDefault() {
        return maxRetries == null ? DEFAULT_MAX_RETRIES : maxRetries;
    }
}
