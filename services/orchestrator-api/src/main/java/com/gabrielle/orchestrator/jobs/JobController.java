package com.gabrielle.orchestrator.jobs;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = "*")
public class JobController {

    private final JobRepository repo;

    public JobController(JobRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public JobResponse create(@RequestBody @Valid CreateJobRequest req) {
        JobEntity job = new JobEntity();
        job.setTitle(req.title);
        job.setPriority(req.priority);
        job.setEstimatedRuntimeMs(req.estimatedRuntimeMs);
        job.setDeadlineUtc(req.deadlineUtc);
        job.setMaxRetries(req.maxRetries);
        job.setStatus(JobStatus.SUBMITTED);

        return JobResponse.from(repo.save(job));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return repo.findById(id)
                .map(JobResponse::from)
                .orElseThrow(() -> new RuntimeException("Job not found"));
    }

    @GetMapping
    public List<JobResponse> list() {
        return repo.findTop100ByOrderByCreatedAtUtcDesc()
                .stream()
                .map(JobResponse::from)
                .toList();
    }
}

