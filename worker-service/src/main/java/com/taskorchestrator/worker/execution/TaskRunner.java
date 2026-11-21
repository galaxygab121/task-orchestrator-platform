package com.taskorchestrator.worker.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskorchestrator.contracts.PayloadRules;
import com.taskorchestrator.worker.WorkerProperties;
import com.taskorchestrator.worker.task.PermanentTaskException;
import com.taskorchestrator.worker.task.TaskContext;
import com.taskorchestrator.worker.task.TaskHandler;
import com.taskorchestrator.worker.task.TaskHandlerRegistry;
import com.taskorchestrator.worker.task.TransientTaskException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Runs handlers on a bounded pool (threads == worker.concurrency, bounded queue) with a hard timeout.
 * Validates the payload again before running (defence in depth) and applies the optional, bounded
 * fault-injection fields used for demos.
 */
@Component
public class TaskRunner {

    private final TaskHandlerRegistry registry;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;

    public TaskRunner(TaskHandlerRegistry registry, WorkerProperties props) {
        this.registry = registry;
        this.timeout = props.taskTimeout();
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(props.concurrency(), props.concurrency(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(props.concurrency()), r -> {
                    Thread t = new Thread(r, "task-runner-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Starts the task; the returned future can be cancelled (interrupting it) if the lease is lost. */
    public Future<JsonNode> start(TaskContext context) {
        List<String> errors = PayloadRules.validate(context.type(), context.payload());
        if (!errors.isEmpty()) {
            throw new PermanentTaskException("invalid payload: " + String.join("; ", errors));
        }
        TaskHandler handler = registry.require(context.type());
        try {
            return executor.submit(() -> {
                injectFaults(context);
                return handler.execute(context);
            });
        } catch (RejectedExecutionException e) {
            throw new TransientTaskException("worker saturated, task rejected");
        }
    }

    /** Waits for the result within the configured timeout, translating every outcome into the failure taxonomy. */
    public JsonNode await(Future<JsonNode> future) throws InterruptedException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TransientTaskException("task timed out after " + timeout.toMillis() + " ms");
        } catch (CancellationException e) {
            throw new TransientTaskException("task cancelled (lease lost or shutdown)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TransientTaskException || cause instanceof PermanentTaskException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof InterruptedException) {
                throw new TransientTaskException("task interrupted");
            }
            // Unknown handler errors are treated as transient; maxRetries bounds the damage.
            throw new TransientTaskException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    public JsonNode run(TaskContext context) throws InterruptedException {
        return await(start(context));
    }

    private static void injectFaults(TaskContext context) {
        if (PayloadRules.simulatedPermanentFailure(context.payload())) {
            throw new PermanentTaskException("simulated permanent failure (failPermanently=true)");
        }
        int transientFailures = PayloadRules.simulatedTransientFailures(context.payload());
        if (context.attempt() <= transientFailures) {
            throw new TransientTaskException("simulated transient failure on attempt " + context.attempt() + " of first "
                    + transientFailures);
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
        }
    }
}
