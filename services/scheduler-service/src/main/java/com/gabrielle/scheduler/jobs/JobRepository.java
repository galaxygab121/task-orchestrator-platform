package com.gabrielle.scheduler.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {
    List<JobEntity> findTop50ByStatusOrderByPriorityDescIdAsc(JobStatus status);
}

