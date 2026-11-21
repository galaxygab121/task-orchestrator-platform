package com.taskorchestrator.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.api.web.CreateJobRequest;
import com.taskorchestrator.contracts.JobType;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestFingerprint fingerprint = new RequestFingerprint(mapper);

    @Test
    void ignoresKeyOrderAndAppliesDefaults() throws Exception {
        var a = new CreateJobRequest("t", JobType.DATA_SUMMARY,
                mapper.readTree("{\"values\":[1,2],\"failPermanently\":false}"), null, null);
        var b = new CreateJobRequest("t", JobType.DATA_SUMMARY, mapper.readTree("{\"failPermanently\":false,\"values\":[1,2]}"), 3, 3);
        assertThat(fingerprint.of(a)).isEqualTo(fingerprint.of(b)).hasSize(64);
    }

    @Test
    void differsWhenContentDiffers() throws Exception {
        var a = new CreateJobRequest("t", JobType.FIBONACCI, mapper.readTree("{\"n\":1}"), 3, 3);
        var b = new CreateJobRequest("t", JobType.FIBONACCI, mapper.readTree("{\"n\":2}"), 3, 3);
        var c = new CreateJobRequest("t", JobType.FIBONACCI, mapper.readTree("{\"n\":1}"), 4, 3);
        assertThat(fingerprint.of(a)).isNotEqualTo(fingerprint.of(b)).isNotEqualTo(fingerprint.of(c));
    }
}
