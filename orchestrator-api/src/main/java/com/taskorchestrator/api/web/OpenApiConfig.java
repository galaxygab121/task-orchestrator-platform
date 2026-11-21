package com.taskorchestrator.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orchestratorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Task Orchestrator API")
                .version("v1")
                .description("Submit and track bounded, allowlisted jobs (SLEEP, FIBONACCI, DATA_SUMMARY)."));
    }
}
