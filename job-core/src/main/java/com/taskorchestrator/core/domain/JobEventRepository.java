package com.taskorchestrator.core.domain;

import com.taskorchestrator.contracts.JobStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobEventRepository extends JpaRepository<JobEvent, Long> {

    List<JobEvent> findByJobIdOrderByIdAsc(UUID jobId);

    long countByJobIdAndToStatus(UUID jobId, JobStatus toStatus);
}
