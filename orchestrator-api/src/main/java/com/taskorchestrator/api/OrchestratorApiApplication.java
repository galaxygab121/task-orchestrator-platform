package com.taskorchestrator.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.taskorchestrator")
public class OrchestratorApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApiApplication.class, args);
    }
}
