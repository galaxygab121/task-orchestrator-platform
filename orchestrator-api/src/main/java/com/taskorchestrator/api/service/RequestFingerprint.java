package com.taskorchestrator.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.taskorchestrator.api.web.CreateJobRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** SHA-256 over a canonical (sorted-keys, defaults-applied) form of the create request. */
@Component
public class RequestFingerprint {

    private final ObjectMapper mapper;

    public RequestFingerprint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String of(CreateJobRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("title", request.title());
        root.put("type", request.type().name());
        root.set("payload", canonical(request.payload()));
        root.put("priority", request.priorityOrDefault());
        root.put("maxRetries", request.maxRetriesOrDefault());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(canonical(root)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return node;
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        ObjectNode sorted = mapper.createObjectNode();
        for (String name : names) {
            sorted.set(name, canonical(node.get(name)));
        }
        return sorted;
    }
}
