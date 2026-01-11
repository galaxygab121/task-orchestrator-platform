package com.gabrielle.scheduler.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class JobProducer {

    private final KafkaTemplate<String, JobMessage> kafka;

    public JobProducer(KafkaTemplate<String, JobMessage> kafka) {
        this.kafka = kafka;
    }

    public void publishJob(String topic, JobMessage msg) {
        kafka.send(topic, msg.id.toString(), msg);
    }
}

