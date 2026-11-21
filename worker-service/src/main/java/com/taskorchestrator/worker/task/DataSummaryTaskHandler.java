package com.taskorchestrator.worker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.PayloadRules;
import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
public class DataSummaryTaskHandler implements TaskHandler {

    @Override
    public JobType type() {
        return JobType.DATA_SUMMARY;
    }

    @Override
    public JsonNode execute(TaskContext context) {
        JsonNode values = context.payload().path("values");
        if (!values.isArray() || values.isEmpty() || values.size() > PayloadRules.DATA_SUMMARY_MAX_VALUES) {
            throw new PermanentTaskException("values must be a non-empty array of at most " + PayloadRules.DATA_SUMMARY_MAX_VALUES);
        }
        double[] numbers = new double[values.size()];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = values.get(i).asDouble();
        }
        double[] sorted = numbers.clone();
        Arrays.sort(sorted);
        double sum = Arrays.stream(numbers).sum();
        double mean = sum / numbers.length;
        double variance = Arrays.stream(numbers).map(v -> (v - mean) * (v - mean)).sum() / numbers.length;
        int mid = sorted.length / 2;
        double median = sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("count", numbers.length);
        result.put("sum", sum);
        result.put("min", sorted[0]);
        result.put("max", sorted[sorted.length - 1]);
        result.put("mean", mean);
        result.put("median", median);
        result.put("stdDev", Math.sqrt(variance));
        return result;
    }
}
