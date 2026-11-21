package com.taskorchestrator.core.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the shared entities/repositories for every service. Kept off the application classes so
 * web-slice tests ({@code @WebMvcTest}) do not try to bootstrap JPA.
 */
@Configuration
@EntityScan("com.taskorchestrator.core")
@EnableJpaRepositories("com.taskorchestrator.core")
public class CorePersistenceConfiguration {
}
