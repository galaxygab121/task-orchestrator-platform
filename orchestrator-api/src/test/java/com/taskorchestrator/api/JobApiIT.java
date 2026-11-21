package com.taskorchestrator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.outbox.OutboxRepository;
import com.taskorchestrator.testsupport.AbstractContainersIT;
import com.taskorchestrator.testsupport.DatabaseCleaner;
import com.taskorchestrator.testsupport.TestKafka;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** End-to-end API test over HTTP against real Postgres and Kafka (no scheduler running, so jobs stay SUBMITTED). */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobApiIT extends AbstractContainersIT {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JobRepository jobs;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    private ResponseEntity<JsonNode> post(String body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity("/api/v1/jobs", new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void createPersistsJobAndPublishesSubmittedEventViaOutbox() {
        ResponseEntity<JsonNode> created = post("{\"title\":\"fib\",\"type\":\"FIBONACCI\",\"payload\":{\"n\":20},\"priority\":5}", null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = UUID.fromString(created.getBody().get("id").asText());
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/jobs/" + id);
        assertThat(created.getBody().get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(created.getBody().get("maxRetries").asInt()).isEqualTo(3);

        ResponseEntity<JsonNode> fetched = http.getForEntity("/api/v1/jobs/" + id, JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("payload").get("n").asInt()).isEqualTo(20);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outbox.findByAggregateIdOrderByIdAsc(id))
                .singleElement().satisfies(row -> assertThat(row.getPublishedAt()).isNotNull()));
        assertThat(TestKafka.readUntil(Topics.JOB_SUBMITTED, r -> id.toString().equals(r.key()), Duration.ofSeconds(20)))
                .isNotEmpty();
    }

    @Test
    void invalidPayloadRejectedAndNothingPersisted() {
        ResponseEntity<JsonNode> response = post("{\"title\":\"x\",\"type\":\"SLEEP\",\"payload\":{\"durationMs\":60000}}", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(jobs.count()).isZero();
    }

    @Test
    void idempotencyKeyDeduplicatesAndDetectsMismatch() {
        String body = "{\"title\":\"sum\",\"type\":\"DATA_SUMMARY\",\"payload\":{\"values\":[1,2,3]}}";
        ResponseEntity<JsonNode> first = post(body, "client-req-1");
        ResponseEntity<JsonNode> second = post(body, "client-req-1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jobs.count()).isEqualTo(1);

        ResponseEntity<JsonNode> mismatch = post(body.replace("[1,2,3]", "[4]"), "client-req-1");
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jobs.count()).isEqualTo(1);

        // no key -> no deduplication
        post(body, null);
        post(body, null);
        assertThat(jobs.count()).isEqualTo(3);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneJob() throws Exception {
        String body = "{\"title\":\"race\",\"type\":\"FIBONACCI\",\"payload\":{\"n\":3}}";
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post(body, "same-key");
            }));
        }
        start.countDown();
        Set<String> ids = new HashSet<>();
        for (Future<ResponseEntity<JsonNode>> f : futures) {
            ResponseEntity<JsonNode> r = f.get();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            ids.add(r.getBody().get("id").asText());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void listPaginatesDeterministicallyAndFilters() {
        for (int i = 0; i < 5; i++) {
            post("{\"title\":\"f" + i + "\",\"type\":\"FIBONACCI\",\"payload\":{\"n\":" + i + "}}", null);
        }
        post("{\"title\":\"s\",\"type\":\"SLEEP\",\"payload\":{\"durationMs\":1}}", null);

        JsonNode page0 = http.getForObject("/api/v1/jobs?type=FIBONACCI&size=2&page=0", JsonNode.class);
        JsonNode page2 = http.getForObject("/api/v1/jobs?type=FIBONACCI&size=2&page=2", JsonNode.class);
        assertThat(page0.get("totalElements").asInt()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.get("content")).hasSize(2);
        assertThat(page0.get("content").get(0).get("title").asText()).isEqualTo("f4");
        assertThat(page2.get("content")).hasSize(1);
        assertThat(page2.get("content").get(0).get("title").asText()).isEqualTo("f0");

        JsonNode submitted = http.getForObject("/api/v1/jobs?status=SUBMITTED&size=100", JsonNode.class);
        assertThat(submitted.get("totalElements").asInt()).isEqualTo(6);
        JsonNode none = http.getForObject("/api/v1/jobs?status=SUCCEEDED", JsonNode.class);
        assertThat(none.get("totalElements").asInt()).isZero();
    }

    @Test
    void cancelSubmittedJobThenSecondCancelConflicts() {
        UUID id = UUID.fromString(post("{\"title\":\"c\",\"type\":\"SLEEP\",\"payload\":{\"durationMs\":10}}", null)
                .getBody().get("id").asText());

        ResponseEntity<JsonNode> cancelled = http.postForEntity("/api/v1/jobs/" + id + "/cancel", null, JsonNode.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");

        ResponseEntity<JsonNode> again = http.postForEntity("/api/v1/jobs/" + id + "/cancel", null, JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode history = http.getForObject("/api/v1/jobs/" + id + "/events", JsonNode.class);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("toStatus").asText()).isEqualTo("CANCELLED");

        assertThat(http.postForEntity("/api/v1/jobs/" + UUID.randomUUID() + "/cancel", null, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void healthPrometheusAndOpenApiAreExposed() {
        assertThat(http.getForEntity("/actuator/health", JsonNode.class).getBody().get("status").asText()).isEqualTo("UP");
        ResponseEntity<String> prometheus = http.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("orchestrator_outbox_pending");
        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody()).contains("/api/v1/jobs/{id}/cancel");
    }
}
