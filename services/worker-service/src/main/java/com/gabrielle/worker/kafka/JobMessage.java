package com.gabrielle.worker.kafka;

import java.time.Instant;
import java.util.UUID;

public class JobMessage {
    public UUID id;
    public String title;
    public int priority;
    public long estimatedRuntimeMs;
    public Instant deadlineUtc;
    public int attempt;
    public int maxRetries;
}
