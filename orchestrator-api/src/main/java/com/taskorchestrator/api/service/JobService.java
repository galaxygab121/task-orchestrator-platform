package com.taskorchestrator.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.api.error.IdempotencyConflictException;
import com.taskorchestrator.api.error.InvalidPayloadException;
import com.taskorchestrator.api.error.JobNotFoundException;
import com.taskorchestrator.api.web.CreateJobRequest;
import com.taskorchestrator.api.web.JobEventResponse;
import com.taskorchestrator.api.web.JobResponse;
import com.taskorchestrator.api.web.PageResponse;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.PayloadRules;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for the REST API.
 *
 * <p>Idempotency: when an {@code Idempotency-Key} is supplied, the key and a SHA-256 fingerprint of the
 * (defaults-applied) request are stored on the job under a UNIQUE constraint. A repeat with the same key
 * and same body returns the original job (replay); the same key with a different body is a 409. Two
 * concurrent first requests race on the unique index; the loser re-reads and replays. Keys do not expire.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-]{1,128}");
    private static final String ACTOR = "api";

    private final JobRepository jobs;
    private final JobEventRepository events;
    private final JobLifecycleService lifecycle;
    private final TransactionTemplate tx;
    private final RequestFingerprint fingerprint;
    private final ObjectMapper mapper;

    public JobService(JobRepository jobs, JobEventRepository events, JobLifecycleService lifecycle, TransactionTemplate tx,
            RequestFingerprint fingerprint, ObjectMapper mapper) {
        this.jobs = jobs;
        this.events = events;
        this.lifecycle = lifecycle;
        this.tx = tx;
        this.fingerprint = fingerprint;
        this.mapper = mapper;
    }

    public CreateResult create(CreateJobRequest request, String idempotencyKey) {
        String key = normaliseKey(idempotencyKey);
        List<String> errors = PayloadRules.validate(request.type(), request.payload());
        if (!errors.isEmpty()) {
            throw new InvalidPayloadException(errors);
        }
        String hash = fingerprint.of(request);
        if (key != null) {
            Optional<Job> existing = jobs.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), hash);
            }
        }
        try {
            Job job = tx.execute(status -> {
                Job candidate = new Job(UUID.randomUUID(), request.title().trim(), request.type(), toJson(request.payload()),
                        request.priorityOrDefault(), PayloadRules.estimateRuntimeMs(request.type(), request.payload()),
                        request.maxRetriesOrDefault());
                candidate.setIdempotencyKey(key);
                candidate.setRequestHash(hash);
                return lifecycle.submit(candidate, ACTOR);
            });
            log.info("Accepted job {} type={} priority={}", job.getId(), job.getType(), job.getPriority());
            return new CreateResult(toResponse(job), false);
        } catch (DataIntegrityViolationException e) {
            if (key == null) {
                throw e;
            }
            Job winner = jobs.findByIdempotencyKey(key).orElseThrow(() -> e);
            return replay(winner, hash);
        }
    }

    public JobResponse get(UUID id) {
        return toResponse(jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id)));
    }

    public PageResponse<JobResponse> list(JobStatus status, JobType type, int page, int size) {
        Specification<Job> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        // Deterministic order: newest first, id as tie-breaker.
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<Job> result = jobs.findAll(spec, PageRequest.of(page, size, sort));
        return new PageResponse<>(result.getContent().stream().map(this::toResponse).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public List<JobEventResponse> history(UUID id) {
        if (!jobs.existsById(id)) {
            throw new JobNotFoundException(id);
        }
        return events.findByJobIdOrderByIdAsc(id).stream()
                .map(e -> new JobEventResponse(e.getFromStatus(), e.getToStatus(), e.getAttempt(), e.getActor(), e.getDetail(),
                        e.getOccurredAt()))
                .toList();
    }

    /** Cancels a SUBMITTED/QUEUED job; RUNNING or finished jobs yield InvalidJobTransitionException (409). */
    public JobResponse cancel(UUID id) {
        Job job = tx.execute(status -> {
            Job current = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
            lifecycle.cancel(current, ACTOR);
            return jobs.saveAndFlush(current);
        });
        log.info("Cancelled job {}", id);
        return toResponse(job);
    }

    private CreateResult replay(Job existing, String hash) {
        if (!hash.equals(existing.getRequestHash())) {
            throw new IdempotencyConflictException();
        }
        return new CreateResult(toResponse(existing), true);
    }

    private static String normaliseKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-128 characters of [A-Za-z0-9._:-]");
        }
        return trimmed;
    }

    private String toJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is unreadable", e);
        }
    }

    JobResponse toResponse(Job job) {
        return new JobResponse(job.getId(), job.getTitle(), job.getType(), job.getStatus(), job.getPriority(), job.getMaxRetries(),
                job.getAttempt(), job.getEstimatedRuntimeMs(), parse(job.getPayload()), parse(job.getResult()), job.getLastError(),
                job.getCreatedAt(), job.getUpdatedAt(), job.getStartedAt(), job.getCompletedAt());
    }

    public record CreateResult(JobResponse job, boolean replayed) {
    }
}
