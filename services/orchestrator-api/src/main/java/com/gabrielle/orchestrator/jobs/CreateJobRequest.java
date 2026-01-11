package com.gabrielle.orchestrator.jobs;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public class CreateJobRequest {

    @NotBlank
    public String title;

    @Min(0)
    public int priority;

    @Min(1)
    public long estimatedRuntimeMs;

    // Optional: ISO-8601 timestamp like "2026-01-11T19:00:00Z"
    public Instant deadlineUtc;

    public int maxRetries = 3;
}
