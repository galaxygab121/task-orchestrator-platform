package com.taskorchestrator.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Base class for integration tests; they are skipped (not failed) when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractContainersIT {

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry);
    }
}
