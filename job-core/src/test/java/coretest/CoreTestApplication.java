package coretest;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Minimal Boot app for testing the job-core library in isolation (lives outside com.taskorchestrator on purpose). */
@SpringBootApplication(scanBasePackages = "com.taskorchestrator.core")
public class CoreTestApplication {
}
