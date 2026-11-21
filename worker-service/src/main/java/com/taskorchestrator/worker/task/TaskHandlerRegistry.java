package com.taskorchestrator.worker.task;

import com.taskorchestrator.contracts.JobType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The execution allowlist: exactly one handler per {@link JobType}; anything else fails permanently. */
@Component
public class TaskHandlerRegistry {

    private final Map<JobType, TaskHandler> handlers = new EnumMap<>(JobType.class);

    public TaskHandlerRegistry(List<TaskHandler> all) {
        for (TaskHandler handler : all) {
            if (handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Duplicate handler for " + handler.type());
            }
        }
    }

    public TaskHandler require(JobType type) {
        TaskHandler handler = type == null ? null : handlers.get(type);
        if (handler == null) {
            throw new PermanentTaskException("No handler registered for job type " + type);
        }
        return handler;
    }
}
