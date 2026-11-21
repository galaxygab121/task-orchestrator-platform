package com.taskorchestrator.api.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.api.error.IdempotencyConflictException;
import com.taskorchestrator.api.error.InvalidPayloadException;
import com.taskorchestrator.api.error.JobNotFoundException;
import com.taskorchestrator.api.service.JobService;
import com.taskorchestrator.api.service.JobService.CreateResult;
import com.taskorchestrator.contracts.InvalidJobTransitionException;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobController.class)
class JobControllerTest {

    private static final String VALID = """
            {"title":"fib","type":"FIBONACCI","payload":{"n":10},"priority":4,"maxRetries":2}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    JobService service;

    private JobResponse job(UUID id, JobStatus status) throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new JobResponse(id, "fib", JobType.FIBONACCI, status, 4, 2, 0, 10, mapper.readTree("{\"n\":10}"), null, null,
                now, now, null, null);
    }

    @Test
    void createReturns202WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(), isNull())).thenReturn(new CreateResult(job(id, JobStatus.SUBMITTED), false));

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/jobs/" + id))
                .andExpect(header().string(JobController.REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void repeatedIdempotencyKeyReplaysOriginal() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(), eq("order-42"))).thenReturn(new CreateResult(job(id, JobStatus.QUEUED), true));

        mvc.perform(post("/api/v1/jobs").header(JobController.IDEMPOTENCY_KEY, "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(header().string(JobController.REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void idempotencyKeyWithDifferentBodyIs409() throws Exception {
        when(service.create(any(), eq("k1"))).thenThrow(new IdempotencyConflictException());
        mvc.perform(post("/api/v1/jobs").header(JobController.IDEMPOTENCY_KEY, "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void beanValidationFailuresAre400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"type\":\"SLEEP\",\"payload\":{},\"priority\":9,\"maxRetries\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasItem("title must not be blank")))
                .andExpect(jsonPath("$.errors", hasItem("priority must be less than or equal to 5")))
                .andExpect(jsonPath("$.errors", hasItem("maxRetries must be greater than or equal to 0")));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonIs400WithoutInternals() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"x\", "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content -> org.assertj.core.api.Assertions.assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("com.fasterxml").doesNotContain("at org."));
    }

    @Test
    void unknownJobTypeIs400ListingAllowedTypes() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"type\":\"SHELL\",\"payload\":{\"cmd\":\"ls\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]", containsString("SLEEP, FIBONACCI, DATA_SUMMARY")));
    }

    @Test
    void unknownTopLevelFieldIs400() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"type\":\"SLEEP\",\"payload\":{\"durationMs\":1},\"script\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payloadRuleViolationIs400() throws Exception {
        when(service.create(any(), isNull())).thenThrow(new InvalidPayloadException(List.of("payload.n must be between 0 and 40")));
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"type\":\"FIBONACCI\",\"payload\":{\"n\":99}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"))
                .andExpect(jsonPath("$.errors[0]").value("payload.n must be between 0 and 40"));
    }

    @Test
    void invalidIdempotencyKeyIs400() throws Exception {
        when(service.create(any(), eq("bad key!"))).thenThrow(new IllegalArgumentException("Idempotency-Key must be ..."));
        mvc.perform(post("/api/v1/jobs").header(JobController.IDEMPOTENCY_KEY, "bad key!")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void missingJobIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new JobNotFoundException(id));
        mvc.perform(get("/api/v1/jobs/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void nonUuidIdIs400() throws Exception {
        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void cancelRunningJobIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id)).thenThrow(new InvalidJobTransitionException(JobStatus.RUNNING, JobStatus.CANCELLED));
        mvc.perform(post("/api/v1/jobs/" + id + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.detail").value("Job is RUNNING and cannot move to CANCELLED"));
    }

    @Test
    void listValidatesPagingAndFilters() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs").param("status", "DONE")).andExpect(status().isBadRequest());

        when(service.list(JobStatus.QUEUED, JobType.SLEEP, 1, 5)).thenReturn(new PageResponse<>(List.of(), 1, 5, 7, 2));
        mvc.perform(get("/api/v1/jobs").param("status", "QUEUED").param("type", "SLEEP").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(2));
        verify(service).list(JobStatus.QUEUED, JobType.SLEEP, 1, 5);
    }

    @Test
    void unexpectedErrorIs500WithGenericMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new IllegalStateException("db password is hunter2"));
        mvc.perform(get("/api/v1/jobs/" + id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content -> org.assertj.core.api.Assertions.assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("hunter2"));
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/jobs/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code", not("INTERNAL_ERROR")));
    }
}
