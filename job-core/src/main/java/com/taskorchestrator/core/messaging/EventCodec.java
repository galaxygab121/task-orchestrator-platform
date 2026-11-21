package com.taskorchestrator.core.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** JSON (de)serialisation of contract events, mapping parse failures to {@link PoisonMessageException}. */
@Component
public class EventCodec {

    private final ObjectMapper objectMapper;

    public EventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event " + event.getClass().getSimpleName(), e);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        try {
            T value = objectMapper.readValue(json, type);
            if (value == null) {
                throw new PoisonMessageException("Empty " + type.getSimpleName() + " message", null);
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new PoisonMessageException("Unparseable " + type.getSimpleName() + " message", e);
        }
    }
}
