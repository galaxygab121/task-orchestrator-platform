package coretest;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.testsupport.AbstractContainersIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = CoreTestApplication.class, properties = "orchestrator.outbox.enabled=false")
class ClaimConcurrencyIT extends AbstractContainersIT {

    @Autowired
    JobLifecycleService lifecycle;
    @Autowired
    JobRepository jobs;
    @Autowired
    JobEventRepository events;
    @Autowired
    TransactionTemplate tx;

    @Test
    void exactlyOneOfManyConcurrentClaimsWins() throws Exception {
        Job job = new Job(UUID.randomUUID(), "race", JobType.SLEEP, "{\"durationMs\": 1}", 3, 1, 0);
        tx.executeWithoutResult(s -> {
            lifecycle.submit(job, "test");
            lifecycle.dispatch(jobs.findById(job.getId()).orElseThrow(), "test");
        });

        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String worker = "w" + i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return Boolean.TRUE.equals(tx.execute(s ->
                            lifecycle.claim(jobs.findById(job.getId()).orElseThrow(), 1, worker, Duration.ofSeconds(30))));
                } catch (ObjectOptimisticLockingFailureException lostRace) {
                    return false;
                }
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdownNow();

        assertThat(winners).isEqualTo(1);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(events.countByJobIdAndToStatus(job.getId(), JobStatus.RUNNING)).isEqualTo(1);
    }
}
