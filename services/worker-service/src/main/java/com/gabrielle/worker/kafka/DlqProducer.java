package com.gabrielle.worker.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DlqProducer {

    private final KafkaTemplate<String, JobMessage> kafka;

    public DlqProducer(KafkaTemplate<String, JobMessage> kafka) {
        this.kafka = kafka;
    }

    public void sendToDlq(JobMessage msg) {
        kafka.send("jobs.dlq.v1", msg.id.toString(), msg);
    }
}

