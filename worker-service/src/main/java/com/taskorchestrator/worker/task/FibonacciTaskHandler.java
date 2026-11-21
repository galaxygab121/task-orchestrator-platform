package com.taskorchestrator.worker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.PayloadRules;
import org.springframework.stereotype.Component;

@Component
public class FibonacciTaskHandler implements TaskHandler {

    @Override
    public JobType type() {
        return JobType.FIBONACCI;
    }

    @Override
    public JsonNode execute(TaskContext context) {
        int n = context.payload().path("n").asInt();
        if (n < 0 || n > PayloadRules.FIBONACCI_MAX_N) {
            throw new PermanentTaskException("n out of range: " + n);
        }
        return JsonNodeFactory.instance.objectNode().put("n", n).put("value", fibonacci(n));
    }

    /** Iterative, O(n): F(0)=0, F(1)=1. F(40)=102334155 fits easily in a long. */
    static long fibonacci(int n) {
        long previous = 0;
        long current = 1;
        if (n == 0) {
            return 0;
        }
        for (int i = 2; i <= n; i++) {
            long next = previous + current;
            previous = current;
            current = next;
        }
        return current;
    }
}
