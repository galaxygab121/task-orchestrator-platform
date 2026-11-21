package com.taskorchestrator.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.core.messaging.PoisonMessageException;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrchestratorProperties.class)
public class CoreConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public NewTopic jobSubmittedTopic(OrchestratorProperties props) {
        return topic(Topics.JOB_SUBMITTED, props);
    }

    @Bean
    public NewTopic jobDispatchTopic(OrchestratorProperties props) {
        return topic(Topics.JOB_DISPATCH, props);
    }

    @Bean
    public NewTopic jobDlqTopic(OrchestratorProperties props) {
        return topic(Topics.JOB_DLQ, props);
    }

    /**
     * Consumer error policy: unexpected exceptions are retried twice (1s apart) in-process; poison
     * messages (unparseable / unsupported schema) skip retries. Either way the raw record is then
     * forwarded to {@code jobs.dlq.v1} with Spring's {@code kafka_dlt-*} diagnostic headers and the
     * offset is committed, so one bad record cannot block a partition.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.JOB_DLQ, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
        handler.addNotRetryableExceptions(PoisonMessageException.class, JsonProcessingException.class);
        return handler;
    }

    private static NewTopic topic(String name, OrchestratorProperties props) {
        return TopicBuilder.name(name)
                .partitions(props.kafka().topicPartitions())
                .replicas(props.kafka().replicationFactor())
                .build();
    }
}
