package com.taskorchestrator.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseCleaner {

    private DatabaseCleaner() {
    }

    public static void truncateAll(JdbcTemplate jdbc) {
        jdbc.execute("truncate table job_events, outbox_events, jobs restart identity cascade");
    }
}
