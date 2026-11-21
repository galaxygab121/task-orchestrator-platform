package com.taskorchestrator.contracts.events;

/** Values of the {@code eventType} Kafka header. */
public final class EventTypes {

    public static final String HEADER = "eventType";
    public static final String HEADER_EVENT_ID = "eventId";
    public static final String JOB_SUBMITTED = "JobSubmitted";
    public static final String JOB_DISPATCH = "JobDispatch";
    public static final String JOB_DEAD_LETTERED = "JobDeadLettered";

    private EventTypes() {
    }
}
