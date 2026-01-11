package com.gabrielle.worker.consumer;

import com.gabrielle.worker.jobs.JobEntity;
import com.gabrielle.worker.jobs.JobRepository;
import com.gabrielle.worker.jobs.JobStatus;
import com.gabrielle.worker.kafka.DlqProducer;
import com.gabrielle.worker.kafka.JobMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class JobConsumer {

    private final JobRepository repo;
    private final DlqProducer dlq;

    public JobConsumer(JobRepository repo, DlqProducer dlq) {
        this.repo = repo;
        this.dlq = dlq;
    }

    @KafkaListener(topics = "jobs.v1", groupId = "worker-group-v1")
    @Transactional
    public void handle(JobMessage msg) throws InterruptedException {
        System.out.println("Worker received job: " + msg.id + " title=" + msg.title + " attempt=" + msg.attempt);

        JobEntity job = repo.findById(msg.id)
                .orElseThrow(() -> new RuntimeException("Job not found in DB: " + msg.id));

        try {
            // ---- simulate doing the work ----
            Thread.sleep(Math.max(1, msg.estimatedRuntimeMs));

            // ---- simulate occasional failure (30%) ----
            boolean fail = ThreadLocalRandom.current().nextInt(100) < 30;
            if (fail) {
                throw new RuntimeException("Simulated failure");
            }

            job.setStatus(JobStatus.SUCCEEDED);
            repo.save(job);
            System.out.println("Worker completed job: " + msg.id + " -> SUCCEEDED");

        } catch (Exception ex) {
            int nextAttempt = job.getAttempt() + 1;
            job.setAttempt(nextAttempt);

            if (nextAttempt >= job.getMaxRetries()) {
                job.setStatus(JobStatus.DEAD_LETTERED);
                repo.save(job);

                // send original message to DLQ for inspection/replay
                msg.attempt = nextAttempt;
                dlq.sendToDlq(msg);

                System.out.println("Job " + msg.id + " -> DEAD_LETTERED (sent to jobs.dlq.v1)");
            } else {
                job.setStatus(JobStatus.QUEUED);
                repo.save(job);

                System.out.println("Job " + msg.id + " failed, re-queued (attempt " + nextAttempt + "/" + job.getMaxRetries() + ")");
            }
        }
    }
}

