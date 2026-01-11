package com.gabrielle.scheduler.poller;

import com.gabrielle.scheduler.jobs.JobEntity;
import com.gabrielle.scheduler.jobs.JobRepository;
import com.gabrielle.scheduler.jobs.JobStatus;
import com.gabrielle.scheduler.kafka.JobMessage;
import com.gabrielle.scheduler.kafka.JobProducer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class JobPoller {

    private static final String TOPIC = "jobs.v1";

    private final JobRepository repo;
    private final JobProducer producer;

    public JobPoller(JobRepository repo, JobProducer producer) {
        this.repo = repo;
        this.producer = producer;
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void poll() {
        // Stage 1: move SUBMITTED -> QUEUED
        List<JobEntity> submitted = repo.findTop50ByStatusOrderByPriorityDescIdAsc(JobStatus.SUBMITTED);
        for (JobEntity job : submitted) {
            job.setStatus(JobStatus.QUEUED);
        }
        if (!submitted.isEmpty()) {
            repo.saveAll(submitted);
            System.out.println("Queued " + submitted.size() + " submitted job(s).");
        }

        // Stage 2: publish QUEUED -> Kafka, mark RUNNING
        List<JobEntity> queued = repo.findTop50ByStatusOrderByPriorityDescIdAsc(JobStatus.QUEUED);
        if (queued.isEmpty()) return;

        for (JobEntity job : queued) {
            JobMessage msg = new JobMessage();
            msg.id = job.getId();
            msg.title = job.getTitle();
            msg.priority = job.getPriority();
            msg.estimatedRuntimeMs = job.getEstimatedRuntimeMs();
            msg.deadlineUtc = job.getDeadlineUtc();
            msg.attempt = job.getAttempt();
            msg.maxRetries = job.getMaxRetries();

            producer.publishJob(TOPIC, msg);
            job.setStatus(JobStatus.RUNNING);
        }

        repo.saveAll(queued);
        System.out.println("Published " + queued.size() + " queued job(s) to Kafka and marked RUNNING.");
    }
}


