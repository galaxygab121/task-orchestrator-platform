package com.taskorchestrator.api.web;

import com.taskorchestrator.api.service.JobService;
import com.taskorchestrator.api.service.JobService.CreateResult;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs")
public class JobController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Submit a job", description = "Persists the job and returns 202. With Idempotency-Key, a repeated "
            + "identical request returns the original job (header Idempotent-Replayed: true); a different body returns 409.")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different body")
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request,
            @Parameter(description = "Optional client key, 1-128 chars [A-Za-z0-9._:-]")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        CreateResult result = service.create(request, idempotencyKey);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/jobs/" + result.job().id()))
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.job());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a job with status, result and metadata")
    public JobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "List jobs (newest first, id tie-break)")
    public PageResponse<JobResponse> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) JobType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, type, page, size);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Status transition history (audit trail) of a job")
    public List<JobEventResponse> events(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a job that has not started running", description = "409 if RUNNING or already finished.")
    public JobResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
